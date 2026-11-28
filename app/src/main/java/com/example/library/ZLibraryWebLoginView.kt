package com.example.library

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.i18n.tr
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.URI

/**
 * 内置网页登录：把站点登录页装进 WebView，自动填入账号密码并代点 DiamWall 复选框；
 * 遇到验证码或页面改版时，用户可以直接在页面里手动完成。App 侧每 2 秒轮询
 * `remix_userid / remix_userkey` Cookie，建立会话后把整串 Cookie 交回书源
 * （走 Cookie 登录路径落到加密存储），随后自动关闭登录窗口。
 *
 * 为什么要它：站点把登录接口（rpc.php / eapi）挡在人机验证后面，HTTP 客户端过不去，
 * 而浏览器里登录是通的——这正是用户实测“网页登录可以成功、App 内登录总是失败”的原因。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ZLibraryWebLoginView(
    domain: String,
    email: String,
    password: String,
    onOutcome: (cookies: String?, host: String?, message: String) -> Unit
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(tr("正在打开站点登录页…")) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var submitted by remember { mutableStateOf(false) }
    var lastGateClickAt by remember { mutableLongStateOf(0L) }
    val done = remember { booleanArrayOf(false) }

    val injectJs = remember(email, password) { buildInjectJs(email, password) }

    val client = remember(domain) {
        object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                val wv = view ?: return
                wv.evaluateJavascript(INSPECT_JS) { raw ->
                    val flags = raw?.trim('"').orEmpty()
                    val challenge = flags.startsWith("1")
                    val hasForm = flags.length > 1 && flags[1] == '1'
                    when {
                        hasForm && !submitted -> {
                            submitted = true
                            status = tr("已自动填写账号密码并提交，等待站点响应…")
                            wv.evaluateJavascript(injectJs) { result ->
                                val text = result?.trim('"').orEmpty()
                                if (text == "nofields") {
                                    submitted = false
                                    status = tr("没有找到登录表单，请在下方网页中手动登录")
                                } else if (text == "noform") {
                                    submitted = false
                                    status = tr("登录表单的结构已变化，请在下方网页中手动登录")
                                }
                            }
                        }
                        challenge -> {
                            status = tr("站点在做人机验证，正在尝试自动通过；若页面出现复选框请点一下")
                            val now = System.currentTimeMillis()
                            if (now - lastGateClickAt > 6000) {
                                lastGateClickAt = now
                                wv.evaluateJavascript(GATE_CLICK_JS) {}
                            }
                        }
                        hasForm && submitted -> status = tr("已提交账号，等待站点建立会话…")
                        else -> status = tr("页面已加载，请在下方网页中完成登录（成功后自动返回）")
                    }
                }
            }
        }
    }

    // Cookie 轮询：登录成功的唯一判据是站点真的写了会话 Cookie。
    LaunchedEffect(domain) {
        repeat(150) {
            if (done[0]) return@LaunchedEffect
            val host = webView?.url?.let { runCatching { URI(it).host }.getOrNull() }
            for (candidate in listOfNotNull(host, domain).distinct()) {
                val cookies = runCatching {
                    CookieManager.getInstance().getCookie("https://$candidate/")
                }.getOrNull().orEmpty()
                if (cookies.contains("remix_userid") || cookies.contains("remix_userkey")) {
                    done[0] = true
                    status = tr("已建立站点会话，正在同步登录状态…")
                    runCatching { CookieManager.getInstance().flush() }
                    onOutcome(cookies, candidate, tr("登录成功"))
                    return@LaunchedEffect
                }
            }
            delay(2000)
        }
        if (!done[0]) {
            done[0] = true
            onOutcome(null, null, tr("长时间没有检测到登录会话：若页面一直停在验证，请稍后再试"))
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(340.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.userAgentString = MOBILE_UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = client
                    webView = this
                    loadUrl("https://$domain/login")
                }
            }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.let { wv ->
                runCatching { wv.stopLoading() }
                runCatching { (wv.parent as? ViewGroup)?.removeView(wv) }
                runCatching { wv.destroy() }
            }
            webView = null
        }
    }
}

private fun buildInjectJs(email: String, password: String): String {
    val jEmail = JSONObject.quote(email)
    val jPass = JSONObject.quote(password)
    return "var e=document.querySelector('input[name=email],input[name=login],input[type=email]');" +
        "var p=document.querySelector('input[name=password],input[type=password]');" +
        "if(e&&p){" +
        "e.value=$jEmail;p.value=$jPass;" +
        "e.dispatchEvent(new Event('input',{bubbles:true}));" +
        "p.dispatchEvent(new Event('input',{bubbles:true}));" +
        "var f=e.form||p.form;" +
        "if(f){var sb=f.querySelector('button[type=submit],input[type=submit]');" +
        "if(sb){sb.click();'submitted'}else{f.submit();'submitted'}}" +
        "else{'noform'}}" +
        "else{'nofields'}"
}

/** 返回两字符标记：第 1 位=验证页特征，第 2 位=登录表单是否存在。 */
private const val INSPECT_JS = """
    (function(){
        try{
            var h=document.documentElement.innerHTML.slice(0,40000);
            var challenge=/diamwall|Verifying your browser|Checking your browser|Just a moment|cf-chl|turnstile/i.test(h);
            var e=document.querySelector('input[name=email],input[name=login],input[type=email]');
            var p=document.querySelector('input[name=password],input[type=password]');
            return (challenge?'1':'0')+((e&&p)?'1':'0');
        }catch(err){return '00'}
    })()
"""

/** DiamWall 复选框代点（挑战 iframe 与主文档同源，可跨 contentDocument 操作）。 */
private const val GATE_CLICK_JS = """
    (function(){
        try{
            var ifr=document.querySelector('iframe');
            if(!ifr) return 'no-iframe';
            var doc=ifr.contentDocument;
            if(!doc||!doc.body) return 'no-doc';
            var gate=doc.querySelector('#pane-gate, .dw-gate, [class*="dw-gate"]');
            if(!gate) return 'no-gate';
            gate.click();
            return 'clicked';
        }catch(e){return 'err'}
    })()
"""

private const val MOBILE_UA =
    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
