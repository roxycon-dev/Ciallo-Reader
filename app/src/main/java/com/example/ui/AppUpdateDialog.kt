package com.example.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.AppUpdateInstaller
import com.example.data.AppUpdateManager
import com.example.data.AppUpdateStage
import com.example.data.AppUpdateState
import com.example.ui.components.AppToast
import com.example.ui.design.DesignTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One root host keeps installer results alive when the user leaves the Settings tab. */
@Composable
internal fun AppUpdateDialogHost() {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { AppUpdateManager.get(context) }
    val platform = remember(context) { AppUpdateInstaller(context) }
    val state by manager.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val browser = LocalUriHandler.current
    var exporting by remember { mutableStateOf(false) }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        manager.installationReturned()
    }
    val install: () -> Unit = {
        if (!platform.canInstall()) manager.permissionNeeded()
        else runCatching {
            installer.launch(platform.installationIntent(manager.apkFile))
            manager.installing()
        }.onFailure {
            manager.installationFailed("无法打开系统安装页面。可以导出安装包，用文件管理器安装，或使用浏览器下载。")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        scope.launch {
            manager.awaitInitialization()
            if (manager.state.value.hasPackage) {
                if (platform.canInstall()) install()
                else manager.permissionNeeded("未获得安装权限。安装包已保留，可再次授权、导出后用文件管理器安装，或通过浏览器下载。")
            }
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri ->
        if (uri != null && !exporting) scope.launch {
            exporting = true
            try {
                withContext(Dispatchers.IO) {
                    check(manager.apkFile.isFile) { "安装包已不存在，请重新下载" }
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.apkFile.inputStream().use { it.copyTo(output, 64 * 1024) }
                    } ?: error("无法写入选择的位置")
                }
                AppToast.makeText(context, "安装包已导出，可用文件管理器打开安装", Toast.LENGTH_LONG).show()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                AppToast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally { exporting = false }
        }
    }
    LaunchedEffect(state.autoInstall, lifecycle) {
        if (state.autoInstall && lifecycle == Lifecycle.State.RESUMED) {
            manager.consumeAutoInstall()
            install()
        }
    }
    val openBrowser: (String) -> Unit = { url ->
        runCatching { browser.openUri(url) }.onFailure {
            AppToast.makeText(context, "无法打开浏览器，可先导出安装包后用文件管理器安装", Toast.LENGTH_LONG).show()
        }
    }
    if (state.visible) AppUpdateDialog(
        state = state,
        exporting = exporting,
        onDismiss = manager::hide,
        onDownload = manager::startDownload,
        onCancel = manager::cancelDownload,
        onInstall = install,
        onPermission = {
            runCatching { permission.launch(platform.permissionIntent()) }.onFailure {
                manager.permissionNeeded("系统无法打开授权页面。请在系统设置中允许安装，或导出安装包、使用浏览器下载。")
            }
        },
        onExport = { state.release?.apk?.let { export.launch(it.name) } },
        onBrowser = { state.release?.let { openBrowser(it.apk?.url ?: it.releaseUrl) } },
        onReleasePage = { state.release?.let { openBrowser(it.releaseUrl) } },
    )
}

@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    exporting: Boolean = false,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
    onPermission: () -> Unit,
    onExport: () -> Unit,
    onBrowser: () -> Unit,
    onReleasePage: () -> Unit,
) {
    val release = state.release ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = DesignTokens.shape(DesignTokens.RadiusXl),
        title = { Text(when (state.stage) {
            AppUpdateStage.DOWNLOADING -> "正在下载更新"
            AppUpdateStage.VERIFYING -> "正在校验安装包"
            AppUpdateStage.NEED_PERMISSION -> "选择安装方式"
            AppUpdateStage.READY, AppUpdateStage.INSTALLING -> "更新已就绪"
            AppUpdateStage.FAILED -> "更新下载未完成"
            AppUpdateStage.AVAILABLE -> "发现新版本"
        }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(DesignTokens.SpaceMd)) {
                Text("Ciallo Reader ${release.version}", style = MaterialTheme.typography.titleMedium)
                if (state.transferring) {
                    if (state.stage == AppUpdateStage.DOWNLOADING && state.total != null && state.total > 0) {
                        val progress = (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                        Text("${(progress * 100).toInt()}% · ${updateBytes(state.downloaded)} / ${updateBytes(state.total)}")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update_progress"))
                        Text(if (state.stage == AppUpdateStage.VERIFYING) "校验完整性、版本和签名…" else "已下载 ${updateBytes(state.downloaded)}")
                    }
                    Text("可收起窗口继续使用。下载完成后，在前台自动打开安装页面。", style = MaterialTheme.typography.bodySmall)
                } else {
                    release.apk?.size?.let { Text("安装包 ${updateBytes(it)}", style = MaterialTheme.typography.bodySmall) }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (state.stage == AppUpdateStage.AVAILABLE || state.stage == AppUpdateStage.FAILED) {
                        Text(release.notes.ifBlank { "更新说明可在 GitHub 发布页查看。" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.hasPackage) {
                        Text("系统仍需你确认安装。升级成功后自动清理应用内安装包；导出的副本由你保留。", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !exporting, onClick = onExport) { Text(if (exporting) "导出中…" else "导出安装包") }
                    }
                    TextButton(onClick = onBrowser) { Text("浏览器下载") }
                    TextButton(onClick = onReleasePage) { Text("查看完整更新说明") }
                }
            }
        },
        confirmButton = {
            when (state.stage) {
                AppUpdateStage.DOWNLOADING, AppUpdateStage.VERIFYING -> TextButton(onClick = onDismiss) { Text("收起") }
                AppUpdateStage.NEED_PERMISSION -> TextButton(enabled = !exporting, onClick = onPermission) { Text("允许安装") }
                AppUpdateStage.READY, AppUpdateStage.INSTALLING -> TextButton(enabled = !exporting, onClick = onInstall) { Text("安装") }
                AppUpdateStage.AVAILABLE, AppUpdateStage.FAILED -> if (release.apk != null)
                    TextButton(onClick = onDownload) { Text(if (state.stage == AppUpdateStage.FAILED) "重新下载" else "下载并安装") }
                else TextButton(onClick = onReleasePage) { Text("打开发布页") }
            }
        },
        dismissButton = {
            TextButton(enabled = !exporting, onClick = if (state.transferring) onCancel else onDismiss) {
                Text(if (state.transferring) "取消下载" else "稍后")
            }
        },
    )
}

internal fun updateBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
