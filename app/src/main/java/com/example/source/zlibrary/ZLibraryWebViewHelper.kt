package com.example.source.zlibrary

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.source.SearchBook
import com.example.source.zlibrary.parser.ZLibraryParserManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONTokener
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * DiamWall/Cloudflare 验证兜底：HTTP 客户端解不了的交互式验证，
 * 用真实 WebView（Chrome UA）像浏览器一样加载搜索页，让验证 JS 自动跑，
 * 然后取出渲染后的 HTML 解析搜索结果。
 *
 * 2026-09-04 增强（1lib.sk 实测校准）：
 * - DiamWall 挑战页的"验证复选框"位于主文档的同源 iframe
 *   （/.well-known/diamwall/...）内，此前只被动等待导致挑战挂死到超时；
 *   现在轮询时主动代点复选框，PoW 由挑战页 JS 自动算，点击后即可放行；
 * - 挑战通过/页面成功后，把 WebView CookieManager 里的会话 Cookie
 *   （dwid / c_token / __diamwall / remix_*）回传给调用方，同步进 OkHttp
 *   的 CookieJar 后，后续详情页与下载请求全程免挑战。
 */
data class WebViewSearchResult(
    val books: List<SearchBook> = emptyList(),
    val pageHasZlibMarkers: Boolean = false,
    val stillChallenged: Boolean = false,
    /** WebView 侧拿到的该域完整 Cookie 串（含 DiamWall 验证 Cookie），可为 null。 */
    val cookies: String? = null,
    val domain: String? = null
)

object ZLibraryWebViewHelper {

    data class DownloadHandoff(val url: String, val cookies: String)

    /** Give JavaScript verification one bounded browser attempt before changing mirrors. */
    suspend fun resolveDownload(context: Context, entryUrl: String, downloadUrl: String,
        cookies: String?): DownloadHandoff? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var view: WebView? = null
            var finished = false
            var openedDownload = false
            fun finish(result: DownloadHandoff?) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                view?.stopLoading()
                view?.destroy()
                if (cont.isActive) cont.resume(result)
            }
            try {
                val node = entryUrl.toHttpUrlOrNull()?.host ?: return@suspendCancellableCoroutine finish(null)
                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                cookies?.split(';')?.filter { it.contains('=') }?.forEach {
                    manager.setCookie("https://$node/", it.trim() + "; Path=/; Secure")
                }
                manager.flush()
                view = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = com.example.source.zlibrary.network.ZLibraryHttpClient.DEFAULT_USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(webView: WebView, url: String) {
                            if (!openedDownload) {
                                openedDownload = true
                                handler.postDelayed({ if (!finished) webView.loadUrl(downloadUrl) }, 800)
                            }
                        }
                    }
                    setDownloadListener { url, _, _, _, _ ->
                        if (url.toHttpUrlOrNull() != null)
                            finish(DownloadHandoff(url, manager.getCookie("https://$node/").orEmpty()))
                    }
                }
                view?.loadUrl(entryUrl)
                handler.postDelayed({ finish(null) }, 15_000)
            } catch (e: Exception) { finish(null) }
            cont.invokeOnCancellation { handler.post { finish(null) } }
        }
    }

    private const val CHROME_UA =
        "Mozilla/5.0 (Linux; Android 12; TGR-W10G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** DiamWall 复选框代点：挑战 iframe 与主文档同源，可跨 contentDocument 操作。 */
    private const val DIAMWALL_GATE_CLICK_JS = """
        (function(){
            try {
                var ifr = document.querySelector('iframe');
                if (!ifr) return 'no-iframe';
                var doc = ifr.contentDocument;
                if (!doc || !doc.body) return 'no-doc';
                var gate = doc.querySelector('#pane-gate, .dw-gate, [class*="dw-gate"]');
                if (!gate) return 'no-gate';
                gate.click();
                return 'clicked';
            } catch (e) { return 'err'; }
        })()
    """

    suspend fun searchViaWebView(
        context: Context,
        node: String,
        keyword: String,
        cookies: String? = null
    ): WebViewSearchResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            var webView: WebView? = null
            val handler = Handler(Looper.getMainLooper())
            var attempts = 0
            var finished = false
            val maxAttempts = 24 // 约 1.5s/次，最长 ~36s
            var reloadedForCookies = false
            var lastGateClickAt = 0L

            fun finish(result: WebViewSearchResult) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                runCatching { webView?.stopLoading() }
                runCatching { webView?.destroy() }
                if (cont.isActive) cont.resume(result)
            }

            fun extractCookies(): String? = runCatching {
                CookieManager.getInstance().getCookie(webView?.url ?: "https://$node")
            }.getOrNull()

            fun clickDiamWallGate() {
                val wv = webView ?: return
                val now = System.currentTimeMillis()
                if (now - lastGateClickAt < 6000) return
                wv.evaluateJavascript(DIAMWALL_GATE_CLICK_JS) { result ->
                    if (result != null && result.contains("clicked")) {
                        lastGateClickAt = now
                        Log.d("ZLibWebView", "DiamWall gate auto-clicked on $node")
                    }
                }
            }

            fun poll() {
                val wv = webView ?: return
                wv.evaluateJavascript("(function(){return document.documentElement.outerHTML;})();") { raw ->
                    if (finished) return@evaluateJavascript
                    val html = raw?.let {
                        try {
                            JSONTokener(it).nextValue() as String
                        } catch (e: Exception) {
                            it
                        }
                    } ?: ""

                    val isChallenge = html.contains("Just a moment", ignoreCase = true) ||
                        html.contains("Verifying your browser", ignoreCase = true) ||
                        html.contains("Checking your browser", ignoreCase = true) ||
                        html.contains("solve this captcha", ignoreCase = true) ||
                        html.contains("cpt.lib")

                    val hasMarkers = html.contains("/book/") ||
                        html.contains("z-bookcard") ||
                        html.contains("resItemBox") ||
                        html.contains("book-item")

                    if (isChallenge) {
                        // DiamWall 复选框在挑战 iframe 里，被动等永远不会过：主动代点
                        clickDiamWallGate()
                    }

                    if (!isChallenge) {
                        val pageTitle = runCatching { Jsoup.parse(html).title() }.getOrDefault("")
                        Log.w(
                            "ZLibWebView",
                            "sample node=$node title=$pageTitle len=${html.length} head=${html.take(240).replace('\n', ' ')}"
                        )
                        if (!reloadedForCookies && html.contains("Cookies are required", ignoreCase = true)) {
                            reloadedForCookies = true
                            Log.w("ZLibWebView", "cookie error page, reloading $node")
                            wv.reload()
                            return@evaluateJavascript
                        }
                        val books = runCatching {
                            val effective = webView?.url?.toHttpUrlOrNull()?.host ?: node
                            ZLibraryParserManager.parseSearchPage(html, "https://$effective", "zlibrary")
                        }.getOrDefault(emptyList())
                        if (books.isNotEmpty()) {
                            finish(WebViewSearchResult(books = books, pageHasZlibMarkers = true, cookies = extractCookies(), domain = webView?.url?.toHttpUrlOrNull()?.host ?: node))
                            return@evaluateJavascript
                        }
                        if (hasMarkers) {
                            finish(WebViewSearchResult(pageHasZlibMarkers = true, cookies = extractCookies()))
                            return@evaluateJavascript
                        }
                    }

                    attempts++
                    if (attempts >= maxAttempts) {
                        finish(WebViewSearchResult(stillChallenged = isChallenge, cookies = extractCookies()))
                    } else {
                        handler.postDelayed({ poll() }, 1500)
                    }
                }
            }

            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = CHROME_UA
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            handler.postDelayed({ poll() }, 1200)
                        }
                    }
                }
                if (!cookies.isNullOrBlank()) {
                    runCatching {
                        CookieManager.getInstance().setCookie("https://$node", cookies)
                        CookieManager.getInstance().flush()
                    }
                }
                val url = "https://$node/s/" + URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
                webView?.loadUrl(url)
                handler.postDelayed({ poll() }, 2200)
            } catch (e: Exception) {
                finish(WebViewSearchResult())
            }

            cont.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                runCatching { webView?.destroy() }
            }
        }
    }
}
