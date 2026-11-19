package com.example.source.js

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 用真实 Chromium WebView 直接取回页面 HTML 的兜底通道。
 *
 * 部分站点（如爱下电子书 ixdzs8.com 的 Tengine WAF）会按 TLS 指纹 / Cookie 拦截
 * OkHttp 请求并返回 403，但同一网络下真实浏览器（WebView 的 Chromium 指纹）却能
 * 正常打开。此工具在无界面 WebView 里加载目标 URL，等页面落到“非拦截页”后抓取
 * `document.documentElement.outerHTML` 返回，并把 WebView Cookie 同步进 JS 源共享
 * 存储（[JsCookieJar]），供后续 OkHttp 请求携带。
 *
 * 与 [CfWebViewSolver] 的区别：后者只认 Cloudflare 的 cf_clearance；本工具不绑定
 * 任何特定 WAF，只要最终页面不是拦截页就取回 HTML，适配非 CF 的指纹/Cookie 门。
 * 同一 host 失败后 5 分钟内不再重试，避免死循环烧流量。
 */
object WebViewPageLoader {

    private const val TAG = "WebViewPageLoader"
    private const val DEFAULT_TIMEOUT_MS = 20_000L
    private const val FAIL_RETRY_INTERVAL_MS = 5 * 60_000L

    private val lastFailAt = HashMap<String, Long>()

    /** 常见拦截页特征（命中即视为尚未过门）。 */
    private fun looksBlocked(html: String): Boolean {
        if (html.isBlank()) return true
        val sample = html.take(32_768)
        return sample.contains("403 Forbidden", true) ||
            sample.contains("Sorry for the inconvenience", true) ||
            sample.contains("Just a moment", true) ||
            sample.contains("Checking your browser", true) ||
            sample.contains("Attention Required", true) ||
            sample.contains("/cdn-cgi/challenge-platform/", true) ||
            sample.contains("search_challenge", true)
    }

    /**
     * 阻塞调用线程，最多 [timeoutMs]，返回过门后的页面 HTML；失败返回 null。
     * @param contentMarker 可选 CSS 选择器：页面须含该元素才算成功（用于确认拿到的是目标内容而非空白壳）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun loadHtml(
        context: Context,
        url: String,
        userAgent: String,
        contentMarker: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): String? {
        val host = runCatching { URL(url).host?.lowercase() }.getOrNull() ?: return null
        synchronized(lastFailAt) {
            lastFailAt[host]?.let {
                if (System.currentTimeMillis() - it < FAIL_RETRY_INTERVAL_MS) return null
            }
        }
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>(null)
        val main = Handler(Looper.getMainLooper())
        var webview: WebView? = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() {
            if (finished.compareAndSet(false, true)) latch.countDown()
        }
        try {
            val cookieManager = CookieManager.getInstance().apply { setAcceptCookie(true) }
            main.post {
                try {
                    val wv = WebView(context.applicationContext)
                    webview = wv
                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        userAgentString = userAgent
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, viewUrl: String) {
                            view.evaluateJavascript(
                                "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
                            ) { value ->
                                val html = decodeJsString(value)
                                if (html != null && !looksBlocked(html) &&
                                    (contentMarker == null || html.contains(contentMarker))
                                ) {
                                    result.set(html)
                                    finish()
                                }
                            }
                        }

                        override fun onReceivedHttpError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            errorResponse: WebResourceResponse?
                        ) {
                            // 主帧 403/503 时挑战脚本可能随后异步过门；不立即放弃，等超时或后续 onPageFinished。
                        }
                    }
                    wv.layout(0, 0, 1080, 1920)
                    wv.loadUrl(url)
                    // 有些站点过门后不重新触发 onPageFinished：周期性主动抓一次 HTML。
                    main.postDelayed(object : Runnable {
                        override fun run() {
                            if (finished.get()) return
                            webview?.evaluateJavascript(
                                "(function(){return document.documentElement ? document.documentElement.outerHTML : '';})()"
                            ) { value ->
                                val html = decodeJsString(value)
                                if (html != null && !looksBlocked(html) &&
                                    (contentMarker == null || html.contains(contentMarker))
                                ) {
                                    result.set(html)
                                    finish()
                                }
                            }
                            if (!finished.get()) main.postDelayed(this, 500)
                        }
                    }, 700)
                } catch (e: Exception) {
                    Log.w(TAG, "webview setup failed: ${e.message}")
                    finish()
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            finish()
            main.post {
                try {
                    webview?.stopLoading()
                    webview?.destroy()
                } catch (_: Exception) {}
            }
            val html = result.get()
            if (html != null) {
                CfWebViewSolver.syncToJsCookieJar(context, url)
                Log.i(TAG, "page loaded via webview for $host")
            } else {
                synchronized(lastFailAt) { lastFailAt[host] = System.currentTimeMillis() }
                Log.w(TAG, "webview page load failed for $host")
            }
            return html
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
    }

    /** evaluateJavascript 返回的是 JSON 编码字符串（带引号与转义），还原为原始 HTML。 */
    private fun decodeJsString(value: String?): String? {
        if (value == null || value == "null" || value.isBlank()) return null
        return try {
            org.json.JSONTokener(value).nextValue() as? String
        } catch (_: Exception) {
            null
        }
    }
}