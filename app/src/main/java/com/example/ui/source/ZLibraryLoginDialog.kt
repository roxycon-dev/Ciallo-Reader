package com.example.ui.source

import com.example.i18n.tr

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.CredentialVault
import com.example.data.VaultCredential
import com.example.library.ZLibraryWebLoginDialog
import com.example.source.BookSource
import com.example.source.LoginCredential
import com.example.source.SourceException
import com.example.source.SourceResult
import com.example.source.zlibrary.ZLibrarySource
import com.example.ui.components.AppIconButton
import com.example.ui.components.AppToast
import com.example.ui.components.SegmentedPillSelector
import com.example.ui.components.paperDismiss
import com.example.ui.components.rememberAsyncState
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.wenku.WenkuMotion
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 通用书源登录窗口（书源管理 / 各站点的“登录 / 验证”入口）。
 *
 * 2026-11 调整：
 * - 窗口垂直居中，键盘弹出时随 IME 上移（此前贴底被键盘盖住）；
 * - 账号密码模式自动从密码箱填入，成功后按需写回（随版本升级保留）；
 * - Z-Library 的 HTTP 登录失败时显示具体原因，并自动切到内置网页登录
 *   （站点把人机验证挡在 rpc.php / eapi 前面，只有网页登录能过）。
 */
@Composable
fun ZLibraryLoginDialog(
    source: BookSource,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
    onRegister: (suspend () -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val registrationUrl by rememberAsyncState<String?>(null, source) {
        value = withTimeoutOrNull(5_000) { source.getRegistrationUrl() }
    }
    val isJsSource = source.id.startsWith("js_")
    val siteVerification = source.id in setOf("js_mycomic", "js_bilimanga", "xbookcn")
    val savedCredential = remember(source.id) { CredentialVault.get(context, source.id) }
    var loginMode by remember(source.id) { mutableIntStateOf(0) }
    var username by remember(source.id) { mutableStateOf(savedCredential?.username.orEmpty()) }
    var password by remember(source.id) { mutableStateOf(savedCredential?.password.orEmpty()) }
    var cookie by remember(source.id) { mutableStateOf("") }
    var saveToVault by remember(source.id) { mutableStateOf(true) }
    var loading by remember(source.id) { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    var webLogin by remember(source.id) { mutableStateOf(false) }
    val title = if (siteVerification) "验证 ${source.name}" else "登录 ${source.name}"
    val closeAfterSuccess = paperDismiss(onSuccess)

    fun saveCredentialIfNeeded() {
        if (saveToVault && !siteVerification && loginMode == 0 &&
            username.isNotBlank() && password.isNotBlank()
        ) {
            CredentialVault.save(
                context,
                VaultCredential(
                    sourceId = source.id,
                    sourceName = source.name,
                    username = username.trim(),
                    password = password
                )
            )
        }
    }

    WenkuActionSheet(
        title,
        onDismiss,
        dismissEnabled = !loading,
        fitKeyboard = !siteVerification || source.id == "xbookcn",
        centerVertically = !siteVerification,
    ) {
        val keyboard = LocalSoftwareKeyboardController.current
        val xbookcnCookieMode = source.id == "xbookcn" && loginMode == 1
        val webFallbackAvailable = !siteVerification && source is ZLibrarySource

        val canSubmit = !loading && when {
            xbookcnCookieMode -> cookie.isNotBlank()
            siteVerification -> true
            loginMode == 0 -> username.isNotBlank() && password.isNotBlank()
            else -> cookie.isNotBlank()
        }
        val submit: () -> Unit = {
            if (canSubmit) {
                keyboard?.hide()
                loading = true
                error = null
                scope.launch {
                    val credential = if (siteVerification || loginMode == 1) {
                        LoginCredential(cookie = cookie)
                    } else {
                        LoginCredential(username = username.trim(), password = password)
                    }
                    try {
                        when (val result = source.login(credential)) {
                            is SourceResult.Success -> {
                                saveCredentialIfNeeded()
                                AppToast.makeText(context, if (siteVerification) tr("验证成功") else tr("登录成功"), Toast.LENGTH_SHORT).show()
                                closeAfterSuccess()
                            }
                            is SourceResult.Error -> {
                                loading = false
                                error = result.exception.message ?: "登录失败"
                                val kind = (result.exception as? SourceException.LoginError)?.kind
                                if (webFallbackAvailable && (kind == null || kind in setOf(
                                        SourceException.LoginFailureKind.CREDENTIALS,
                                        SourceException.LoginFailureKind.NEEDS_VERIFICATION,
                                        SourceException.LoginFailureKind.UNRECOGNIZED_RESPONSE,
                                        SourceException.LoginFailureKind.RATE_LIMITED
                                    ))
                                ) {
                                    webLogin = true
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        loading = false
                        error = e.message ?: "登录失败"
                    }
                }
            }
        }

        if (!isJsSource && !siteVerification) {
            SegmentedPillSelector(
                options = listOf(0 to tr("账号密码"), 1 to tr("粘贴 Cookie")),
                selected = loginMode,
                onSelect = { if (!loading) { loginMode = it; error = null } },
                fontSp = 14f,
            )
        }
        if (siteVerification && source.id == "xbookcn") {
            // 书阁 CF managed challenge：内置 WebView 真人过盾，或粘贴已过盾的 Cookie。
            val xbookcnReduceMotion = LocalReduceMotion.current
            SegmentedPillSelector(
                options = listOf(0 to tr("内置验证"), 1 to tr("粘贴 Cookie")),
                selected = loginMode,
                onSelect = { if (!loading) { loginMode = it; error = null } },
                fontSp = 14f,
            )
            AnimatedContent(
                targetState = loginMode,
                transitionSpec = {
                    if (xbookcnReduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(tween(WenkuMotion.ENTER_MS)) togetherWith fadeOut(tween(WenkuMotion.EXIT_MS))
                },
                label = "xbookcnVerifyMethod",
            ) { mode ->
                if (mode == 0) {
                    Text(
                        "点击「打开站点验证」，在内置浏览器中完成 Cloudflare 验证后会自动返回，验证凭据由应用保存。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("cf_clearance Cookie") },
                        placeholder = { Text("cf_clearance=…; cf_chl_rc_ni=…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        enabled = !loading,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
        } else if (siteVerification) {
            Text(
                "在应用内完成站点验证后会自动返回，浏览凭据由应用保存。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val reduceMotion = LocalReduceMotion.current
            AnimatedContent(
                targetState = loginMode,
                transitionSpec = {
                    if (reduceMotion) EnterTransition.None togetherWith ExitTransition.None
                    else {
                        val direction = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(tween(WenkuMotion.ENTER_MS, easing = WenkuMotion.ease)) { direction * it / 8 } +
                            fadeIn(tween(WenkuMotion.ENTER_MS))) togetherWith
                            (slideOutHorizontally(tween(WenkuMotion.EXIT_MS, easing = WenkuMotion.ease)) { -direction * it / 8 } +
                                fadeOut(tween(WenkuMotion.EXIT_MS)))
                    }
                },
                label = "sourceLoginMethod",
            ) { mode ->
                if (mode == 0) {
                    SourceCredentialFields(username, { username = it; error = null }, password, { password = it; error = null }, !loading, submit)
                } else {
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        label = { Text("Cookie") },
                        placeholder = { Text("remix_userid=…; remix_userkey=…") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                        enabled = !loading,
                        shape = RoundedCornerShape(16.dp),
                    )
                }
            }
            if (savedCredential != null && loginMode == 0 &&
                username == savedCredential.username && password == savedCredential.password
            ) {
                Text(
                    tr("已从密码箱自动填入账号；登录成功后会自动更新密码箱"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (loginMode == 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveToVault, onCheckedChange = { saveToVault = it }, enabled = !loading)
                    Column(Modifier.weight(1f)) {
                        Text(tr("保存到密码箱"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("加密保存在本机，升级 App 不会丢"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val action = when {
            xbookcnCookieMode -> tr("校验 Cookie")
            siteVerification && loading -> tr("等待站点验证")
            siteVerification -> tr("打开站点验证")
            loading -> tr("登录中")
            else -> tr("确认登录")
        }
        WenkuTextButton(
            action, submit, Modifier.fillMaxWidth(),
            selected = true, enabled = canSubmit,
            wenkuSymbol = if (siteVerification) WenkuSymbol.Shield else WenkuSymbol.Check,
        )
        if (webFallbackAvailable && !loading) {
            WenkuTextButton(
                tr("改用内置网页登录（可过人机验证）"),
                { error = null; webLogin = true },
                Modifier.fillMaxWidth(),
                enabled = true,
                compact = true,
                wenkuSymbol = WenkuSymbol.Shield,
            )
        }

        if (onRegister != null) {
            WenkuTextButton(
                "没有账号？注册新账号",
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            onRegister()
                            AppToast.makeText(context, tr("注册成功，已自动登录"), Toast.LENGTH_SHORT).show()
                            closeAfterSuccess()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            loading = false
                            error = e.message ?: "注册失败"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !loading,
                compact = true, wenkuSymbol = WenkuSymbol.PersonAdd,
            )
        }
        registrationUrl?.let { url ->
            WenkuTextButton(
                "没有账号？去注册",
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
                        AppToast.makeText(context, tr("无法打开注册页面"), Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !loading,
                compact = true, wenkuSymbol = WenkuSymbol.External,
            )
        }
    }

    if (webLogin) {
        ZLibraryWebLoginDialog(
            domain = com.example.library.ZLibraryNodeConfig.domain
                .ifBlank { com.example.library.ZLibraryNodeManager.DEFAULT_NODE },
            email = username.trim(),
            password = password,
            onOutcome = { cookies, host, message ->
                if (cookies != null) {
                    error = tr("网页登录成功，正在同步登录状态…")
                    scope.launch {
                        val result = source.login(
                            LoginCredential(
                                cookie = cookies,
                                extraData = mapOf("domain" to (host ?: "")),
                            )
                        )
                        when (result) {
                            is SourceResult.Success -> {
                                saveCredentialIfNeeded()
                                AppToast.makeText(context, tr("登录成功"), Toast.LENGTH_SHORT).show()
                                closeAfterSuccess()
                            }
                            is SourceResult.Error -> {
                                error = result.exception.message ?: tr("网页登录成功但会话同步失败，请重试")
                            }
                        }
                    }
                } else {
                    error = message
                }
            },
            onDismiss = { webLogin = false },
        )
    }
}

@Composable
internal fun SourceCredentialFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    enabled: Boolean,
    onSubmit: () -> Unit,
) {
    var passwordVisible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text(tr("邮箱 / 账号")) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text(tr("密码")) },
            singleLine = true,
            enabled = enabled,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = {
                AppIconButton(
                    if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    if (passwordVisible) tr("隐藏密码") else tr("显示密码"),
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = enabled,
                    wenkuSymbol = if (passwordVisible) WenkuSymbol.EyeOff else WenkuSymbol.Eye,
                )
            },
        )
    }
}
