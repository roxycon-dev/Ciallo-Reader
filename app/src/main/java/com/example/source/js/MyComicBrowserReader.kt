package com.example.source.js

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Read public HTML with the same Chromium engine and cookie jar used for site verification. */
internal object MyComicBrowserReader {
    private const val MAX_HTML_CHARS = 2_000_000

    fun accepts(source: String, method: String, url: String, bytes: Boolean): Boolean =
        source == "mycomic" && method == "GET" && !bytes &&
            runCatching { Uri.parse(url).let { it.scheme == "https" && it.host == "mycomic.com" &&
                it.userInfo == null && (it.port == -1 || it.port == 443) } }.getOrDefault(false)

    fun response(url: String, title: String, html: String, httpStatus: Int): String {
        require(accepts("mycomic", "GET", url, false)) { "MYCOMIC 返回了站外地址" }
        require(html.length <= MAX_HTML_CHARS) { "MYCOMIC 页面超过读取上限" }
        val challenge = title.contains("Just a moment", true) || title.contains("Attention Required", true) ||
            html.contains("__cf_chl_") || html.contains("cf-browser-verification")
        val status = when {
            challenge -> 403
            httpStatus >= 400 -> httpStatus
            html.isBlank() || !html.contains("MYCOMIC", true) -> 502
            else -> 200
        }
        val headers = JSONObject().put("Content-Type", "text/html; charset=utf-8")
        if (challenge) headers.put("cf-mitigated", "challenge").put("server", "cloudflare")
        return JSONObject().put("status", status).put("url", url).put("headers", headers)
            .put("body", html).toString()
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun read(context: Context, url: String, userAgent: String, job: Job?): String {
        check(Looper.myLooper() != Looper.getMainLooper()) { "浏览器读取不能阻塞主线程" }
        require(accepts("mycomic", "GET", url, false))
        job?.ensureActive()
        val main = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        var browser: WebView? = null
        fun finish(value: String) {
            if (done.compareAndSet(false, true)) { result.set(value); latch.countDown() }
        }
        fun fail(message: String) = finish(JSONObject().put("error", message).toString())
        main.post {
            if (done.get()) return@post
            try {
                val cookies = CookieManager.getInstance().apply { setAcceptCookie(true) }
                val view = WebView(context.applicationContext)
                browser = view
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = userAgent
                    allowFileAccess = false
                    allowContentAccess = false
                }
                cookies.setAcceptThirdPartyCookies(view, false)
                var status = 200
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return request.url.scheme !in setOf("https", "about")
                        val allowed = accepts("mycomic", "GET", request.url.toString(), false)
                        if (!allowed) fail("MYCOMIC 跳转到了站外，请在书源管理中重新验证")
                        return !allowed
                    }
                    override fun onPageStarted(view: WebView, next: String?, icon: android.graphics.Bitmap?) {
                        status = 200
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (request.isForMainFrame) status = response.statusCode
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                        if (request.isForMainFrame) fail("MYCOMIC 浏览器读取失败：${error.description}")
                    }
                    override fun onPageFinished(view: WebView, current: String?) {
                        if (done.get()) return
                        if (!accepts("mycomic", "GET", current.orEmpty(), false)) { fail("MYCOMIC 页面地址无效"); return }
                        view.evaluateJavascript("""
                            (() => { const html=document.documentElement.outerHTML;
                            return JSON.stringify({url:location.href,title:document.title,
                                html:html.length<=$MAX_HTML_CHARS?html:null}); })()
                        """.trimIndent()) { value ->
                            if (done.get()) return@evaluateJavascript
                            try {
                                val payload = JSONObject(JSONArray("[$value]").getString(0))
                                check(!payload.isNull("html")) { "MYCOMIC 页面超过读取上限" }
                                val answer = response(payload.getString("url"), payload.optString("title"), payload.getString("html"), status)
                                cookies.flush()
                                CfWebViewSolver.syncToJsCookieJar(context, url)
                                finish(answer)
                            } catch (e: Exception) { fail(e.message ?: "MYCOMIC 页面读取失败") }
                        }
                    }
                }
                // Manual cookie import and normal website verification share this same jar.
                // Never overwrite a browser-issued cookie's HttpOnly/path attributes.
                if (cookies.getCookie(url).isNullOrBlank()) {
                    JsCookieJar.cookieHeader(context, url).split(';').map { it.trim() }.filter { it.isNotEmpty() }
                        .forEach { cookies.setCookie(url, "$it; Path=/; Secure; HttpOnly") }
                }
                view.loadUrl(url)
            } catch (e: Exception) { fail(e.message ?: "MYCOMIC 浏览器不可用") }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!latch.await(100, TimeUnit.MILLISECONDS)) {
                job?.ensureActive()
                if (System.nanoTime() >= deadline) { fail("MYCOMIC 读取超时，请在书源管理中完成站点验证"); break }
            }
            job?.ensureActive()
            return result.get() ?: JSONObject().put("error", "MYCOMIC 浏览器未返回页面").toString()
        } finally {
            done.set(true)
            main.post { browser?.let { it.stopLoading(); it.destroy() }; browser = null }
        }
    }
}
