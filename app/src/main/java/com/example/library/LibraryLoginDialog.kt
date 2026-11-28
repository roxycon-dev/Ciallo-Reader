package com.example.library

import com.example.i18n.tr

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.example.data.CredentialVault
import com.example.data.VaultCredential
import com.example.source.BookSource
import com.example.source.LoginCredential
import com.example.source.SourceException
import com.example.source.SourceResult
import com.example.ui.components.AppToast
import com.example.ui.source.SourceCredentialFields
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import kotlinx.coroutines.launch

/**
 * Z-Library 登录窗口（书库页与书源管理共用）。
 *
 * 交互约定：
 * - 窗口垂直居中，键盘弹出时随 IME 上移，不再贴底被键盘盖住；
 * - 打开时自动填入密码箱里保存的账号（《密码箱》入口在设置 → 数据管理）；
 * - 先走书源内置的 HTTP 登录；失败时把失败原因（密码错、网络不通、站点要人机验证、
 *   接口改版等）显示出来，并在“站点要求验证/接口改版/被拒绝”这几类情况下
 *   自动切换到内置网页登录——网页登录是站点自己认可、能过人机验证的路径；
 * - 登录成功后按需写入密码箱，随版本升级保留。
 */
@Composable
fun LibraryLoginDialog(
    initialMessage: String,
    source: BookSource?,
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember(source?.id) { source?.id?.let { CredentialVault.get(context, it) } }
    var email by remember(source?.id) { mutableStateOf(saved?.username.orEmpty()) }
    var password by remember(source?.id) { mutableStateOf(saved?.password.orEmpty()) }
    var saveToVault by remember(source?.id) { mutableStateOf(true) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(initialMessage) }
    var webLogin by remember { mutableStateOf(false) }

    fun finishSuccess() {
        val src = source
        if (saveToVault && email.isNotBlank() && password.isNotBlank()) {
            CredentialVault.save(
                context,
                VaultCredential(
                    sourceId = src?.id ?: "zlibrary",
                    sourceName = src?.name ?: "Z-Library",
                    username = email.trim(),
                    password = password
                )
            )
        }
        loading = false
        message = tr("登录成功")
        onAuthenticated()
    }

    fun submit() {
        val src = source
        if (src == null) {
            message = tr("当前书源不可用")
            return
        }
        loading = true
        message = tr("正在用内置接口登录…")
        scope.launch {
            when (val result = src.login(LoginCredential(username = email.trim(), password = password))) {
                is SourceResult.Success -> finishSuccess()
                is SourceResult.Error -> {
                    loading = false
                    message = result.exception.message ?: tr("登录失败")
                    val kind = (result.exception as? SourceException.LoginError)?.kind
                    if (kind == null || kind in setOf(
                            SourceException.LoginFailureKind.CREDENTIALS,
                            SourceException.LoginFailureKind.NEEDS_VERIFICATION,
                            SourceException.LoginFailureKind.UNRECOGNIZED_RESPONSE,
                            SourceException.LoginFailureKind.RATE_LIMITED
                        )
                    ) {
                        webLogin = true
                    }
                }
            }
        }
    }

    WenkuActionSheet(
        tr("登录 Z-Library"),
        onDismiss,
        dismissEnabled = !loading,
        fitKeyboard = !webLogin,
        centerVertically = true,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current

        if (webLogin) {
            Text(
                tr("内置接口登录没有成功，已自动改用站点网页登录。账号密码会自动填入；如果页面出现人机验证，按页面提示点一下即可。"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (message.isNotBlank()) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            ZLibraryWebLoginView(
                domain = ZLibraryNodeConfig.domain.ifBlank { ZLibraryNodeManager.DEFAULT_NODE },
                email = email.trim(),
                password = password,
                onOutcome = { cookies, host, outcomeMessage ->
                    val src = source
                    if (cookies != null && src != null) {
                        message = tr("网页登录成功，正在同步登录状态…")
                        scope.launch {
                            val result = src.login(
                                LoginCredential(
                                    cookie = cookies,
                                    extraData = mapOf("domain" to (host ?: ZLibraryNodeConfig.domain))
                                )
                            )
                            when (result) {
                                is SourceResult.Success -> finishSuccess()
                                is SourceResult.Error -> message = result.exception.message
                                    ?: tr("网页登录成功但会话同步失败，请重试")
                            }
                        }
                    } else {
                        message = outcomeMessage
                    }
                }
            )
            WenkuTextButton(
                tr("返回账号密码登录"),
                { webLogin = false },
                Modifier.fillMaxWidth(),
                enabled = !loading,
                compact = true,
                wenkuSymbol = WenkuSymbol.Check,
            )
        } else {
            SourceCredentialFields(
                email,
                { email = it; message = "" },
                password,
                { password = it; message = "" },
                !loading,
                { if (!loading && email.isNotBlank() && password.isNotBlank()) { keyboard?.hide(); submit() } },
            )
            if (saved != null && email == saved.username && password == saved.password) {
                Text(
                    tr("已从密码箱自动填入账号；登录成功后会自动更新密码箱"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = saveToVault, onCheckedChange = { saveToVault = it }, enabled = !loading)
                Column(Modifier.weight(1f)) {
                    Text(tr("保存到密码箱"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("加密保存在本机，升级 App 不会丢"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (message.isNotBlank()) {
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (loading) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                )
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            WenkuTextButton(
                if (loading) tr("登录中") else tr("确认登录"),
                { if (!loading && email.isNotBlank() && password.isNotBlank()) { keyboard?.hide(); submit() } },
                Modifier.fillMaxWidth(),
                selected = true,
                enabled = !loading && email.isNotBlank() && password.isNotBlank(),
                wenkuSymbol = WenkuSymbol.Check,
            )
            WenkuTextButton(
                tr("改用内置网页登录（可过人机验证）"),
                { webLogin = true },
                Modifier.fillMaxWidth(),
                enabled = !loading,
                compact = true,
                wenkuSymbol = WenkuSymbol.Shield,
            )
            WenkuTextButton(
                tr("没有账号？去注册"),
                onClick = {
                    val url = "https://${ZLibraryNodeConfig.domain}/registration"
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
                        AppToast.makeText(context, tr("无法打开注册页面"), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading,
                compact = true,
                wenkuSymbol = WenkuSymbol.External,
            )
        }
    }
}
