package com.example.library

import com.example.i18n.tr

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.example.ui.components.AppToast
import com.example.ui.components.paperDismiss
import com.example.ui.wenku.WenkuActionSheet
import com.example.ui.source.SourceCredentialFields
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton

@Composable
fun LibraryLoginDialog(
    message: String,
    loading: Boolean,
    onLogin: (String, String, closeAfterSuccess: () -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    WenkuActionSheet(tr("登录 Z-Library"), onDismiss, dismissEnabled = !loading, fitKeyboard = true) {
        val keyboard = LocalSoftwareKeyboardController.current
        val closeAfterSuccess = paperDismiss {}
        val canSubmit = !loading && email.isNotBlank() && password.isNotBlank()
        val submit = {
            if (canSubmit) {
                keyboard?.hide()
                onLogin(email, password, closeAfterSuccess)
            }
        }
        SourceCredentialFields(email, { email = it }, password, { password = it }, !loading, submit)
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        WenkuTextButton(
            if (loading) tr("登录中") else tr("确认登录"),
            submit,
            Modifier.fillMaxWidth(),
            selected = true,
            enabled = canSubmit,
            wenkuSymbol = WenkuSymbol.Check,
        )
        WenkuTextButton(
            com.example.i18n.tr("没有账号？去注册"),
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
