package com.example.ui.source

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.source.BookSource
import com.example.source.LoginCredential
import com.example.source.SourceResult
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
    val siteVerification = source.id == "js_mycomic"
    var loginMode by remember(source.id) { mutableIntStateOf(0) }
    var username by remember(source.id) { mutableStateOf("") }
    var password by remember(source.id) { mutableStateOf("") }
    var cookie by remember(source.id) { mutableStateOf("") }
    var loading by remember(source.id) { mutableStateOf(false) }
    var error by remember(source.id) { mutableStateOf<String?>(null) }
    val title = if (siteVerification) "验证 ${source.name}" else "登录 ${source.name}"

    WenkuActionSheet(title, onDismiss, fitKeyboard = !siteVerification) {
        val keyboard = LocalSoftwareKeyboardController.current
        val closeAfterSuccess = paperDismiss(onSuccess)
        val canSubmit = !loading && (siteVerification ||
            if (loginMode == 0) username.isNotBlank() && password.isNotBlank() else cookie.isNotBlank())
        val submit: () -> Unit = {
            if (canSubmit) {
                keyboard?.hide()
                loading = true
                error = null
                scope.launch {
                    val credential = if (siteVerification || loginMode == 1) {
                        LoginCredential(cookie = cookie)
                    } else {
                        LoginCredential(username = username, password = password)
                    }
                    try {
                        when (val result = source.login(credential)) {
                            is SourceResult.Success -> {
                                AppToast.makeText(context, if (siteVerification) "验证成功" else "登录成功", Toast.LENGTH_SHORT).show()
                                closeAfterSuccess()
                            }
                            is SourceResult.Error -> {
                                loading = false
                                error = result.exception.message ?: "登录失败"
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

        if (!isJsSource) {
            SegmentedPillSelector(
                options = listOf(0 to "账号密码", 1 to "粘贴 Cookie"),
                selected = loginMode,
                onSelect = { if (!loading) { loginMode = it; error = null } },
                fontSp = 14f,
            )
        }
        if (siteVerification) {
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
                    SourceCredentialFields(username, { username = it }, password, { password = it }, !loading, submit)
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
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        val action = when {
            siteVerification && loading -> "等待站点验证"
            siteVerification -> "打开站点验证"
            loading -> "登录中"
            else -> "确认登录"
        }
        WenkuTextButton(
            action, submit, Modifier.fillMaxWidth(),
            selected = true, enabled = canSubmit,
            wenkuSymbol = if (siteVerification) WenkuSymbol.Shield else WenkuSymbol.Check,
        )

        if (onRegister != null) {
            WenkuTextButton(
                "没有账号？注册新账号",
                onClick = {
                    loading = true
                    error = null
                    scope.launch {
                        try {
                            onRegister()
                            AppToast.makeText(context, "注册成功，已自动登录", Toast.LENGTH_SHORT).show()
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
                        AppToast.makeText(context, "无法打开注册页面", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(), enabled = !loading,
                compact = true, wenkuSymbol = WenkuSymbol.External,
            )
        }
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
            label = { Text("邮箱 / 账号") },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("密码") },
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
                    if (passwordVisible) "隐藏密码" else "显示密码",
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = enabled,
                    wenkuSymbol = if (passwordVisible) WenkuSymbol.EyeOff else WenkuSymbol.Eye,
                )
            },
        )
    }
}
