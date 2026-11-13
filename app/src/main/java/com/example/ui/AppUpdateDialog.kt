package com.example.ui

import com.example.i18n.tr

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.example.BuildConfig
import com.example.data.AppUpdateAutoCheck
import com.example.data.AppUpdateInstaller
import com.example.data.AppUpdateManager
import com.example.data.AppUpdateSource
import com.example.data.AppUpdateStage
import com.example.data.AppUpdateState
import com.example.data.PreferencesManager
import com.example.data.mirrorDownloadUrl
import com.example.data.officialDownloadUrl
import com.example.ui.components.AppToast
import com.example.ui.components.LocalOverlayClose
import com.example.ui.wenku.WenkuIconButton
import com.example.ui.wenku.WenkuSheetDialog
import com.example.ui.wenku.WenkuSymbol
import com.example.ui.wenku.WenkuTextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One root host keeps installer results alive when the user leaves the Settings tab. */
@Composable
internal fun AppUpdateDialogHost() {
    val context = LocalContext.current
    val manager = remember(context.applicationContext) { AppUpdateManager.get(context) }
    val platform = remember(context) { AppUpdateInstaller(context) }
    val prefs = remember(context.applicationContext) { PreferencesManager(context.applicationContext) }
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
                    check(manager.apkFile.isFile) { tr("安装包已不存在，请重新下载") }
                    context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                        manager.apkFile.inputStream().use { it.copyTo(output, 64 * 1024) }
                    } ?: error("无法写入选择的位置")
                }
                AppToast.makeText(context, tr("安装包已导出，可用文件管理器打开安装"), Toast.LENGTH_LONG).show()
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
    // Kazumi-like startup check: only after onboarding, only while foreground,
    // once per process. The manager refuses to replace an active download/install.
    LaunchedEffect(lifecycle) {
        if (lifecycle != Lifecycle.State.RESUMED) return@LaunchedEffect
        var waited = 0L
        while (!prefs.hasSeenOnboarding && waited < 10 * 60_000L) {
            delay(1_000)
            waited += 1_000
        }
        if (prefs.hasSeenOnboarding) {
            delay(8_000)
            manager.awaitInitialization()
            AppUpdateAutoCheck.maybeCheckOnStartup(
                context.applicationContext, manager, BuildConfig.VERSION_NAME,
            )
        }
    }
    val openBrowser: (String) -> Unit = { url ->
        runCatching { browser.openUri(url) }.onFailure {
            AppToast.makeText(context, tr("无法打开浏览器，可先导出安装包后用文件管理器安装"), Toast.LENGTH_LONG).show()
        }
    }
    if (state.visible) AppUpdateDialog(
        state = state,
        exporting = exporting,
        onDismiss = manager::hide,
        onDownload = { manager.startDownload(AppUpdateSource.GITHUB) },
        onCancel = manager::cancelDownload,
        onInstall = install,
        onPermission = {
            runCatching { permission.launch(platform.permissionIntent()) }.onFailure {
                manager.permissionNeeded("系统无法打开授权页面。请在系统设置中允许安装，或导出安装包、使用浏览器下载。")
            }
        },
        onExport = { state.release?.apk?.let { export.launch(it.name) } },
        onBrowser = { state.release?.let { release ->
            // Browser downloads always target the official same-repository asset.
            openBrowser(release.apk?.let { officialDownloadUrl(release.tag, it) } ?: release.releaseUrl)
        } },
        onReleasePage = { state.release?.let { openBrowser(it.releaseUrl) } },
        onDownloadMirror = { manager.startDownload(AppUpdateSource.MIRROR) },
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
    onDownloadMirror: () -> Unit = {},
) {
    val release = state.release ?: return
    // The mirror is offered only when the official release published a SHA-256
    // for the same-repository asset URL.
    val mirrorUrl = release.apk?.let { mirrorDownloadUrl(release.tag, it) }
    WenkuSheetDialog(onDismissRequest = onDismiss) {
        val close = LocalOverlayClose.current
        val title = when (state.stage) {
            AppUpdateStage.DOWNLOADING -> tr("正在下载更新")
            AppUpdateStage.VERIFYING -> tr("正在校验安装包")
            AppUpdateStage.NEED_PERMISSION -> tr("选择安装方式")
            AppUpdateStage.READY, AppUpdateStage.INSTALLING -> tr("更新已就绪")
            AppUpdateStage.FAILED -> tr("更新下载未完成")
            AppUpdateStage.AVAILABLE -> tr("发现新版本")
        }
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text("SYSTEM / UPDATE", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WenkuIconButton(Icons.Default.Close, "关闭更新窗口", onClick = { close {} })
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ciallo Reader ${release.version}", style = MaterialTheme.typography.titleMedium)
                if (state.transferring) {
                    if (state.stage == AppUpdateStage.DOWNLOADING && state.total != null && state.total > 0) {
                        val progress = (state.downloaded.toFloat() / state.total).coerceIn(0f, 1f)
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("update_progress"))
                        Text("${(progress * 100).toInt()}% · ${updateBytes(state.downloaded)} / ${updateBytes(state.total)}")
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update_progress"))
                        Text(if (state.stage == AppUpdateStage.VERIFYING) tr("校验完整性、版本和签名…") else "已下载 ${updateBytes(state.downloaded)}")
                    }
                    Text(
                        if (state.source == AppUpdateSource.MIRROR)
                            tr("正在通过 gh-proxy.com 镜像下载官方安装包；完成后仍校验大小、SHA-256、版本与签名。")
                        else tr("可收起窗口继续使用。下载完成后，在前台自动打开安装页面。"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    release.apk?.size?.let { Text("安装包 ${updateBytes(it)}", style = MaterialTheme.typography.bodySmall) }
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (release.apk != null) {
                        if (mirrorUrl == null) {
                            Text("官方发布未提供可验证的 SHA-256，HTTPS 镜像下载已禁用。请使用 GitHub 下载。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("镜像仅加速官方安装包（gh-proxy.com），不提供版本信息；版本、大小、SHA-256、包名与签名仍以 GitHub 官方为准。",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.stage == AppUpdateStage.AVAILABLE || state.stage == AppUpdateStage.FAILED) {
                        Text(release.notes.ifBlank { "更新说明可在 GitHub 发布页查看。" }, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.hasPackage) {
                        Text(tr("系统仍需你确认安装。升级成功后自动清理应用内安装包；导出的副本由你保留。"), style = MaterialTheme.typography.bodySmall)
                        WenkuTextButton(if (exporting) tr("导出中…") else tr("导出安装包"), onExport, enabled = !exporting, wenkuSymbol = WenkuSymbol.Share)
                    }
                    WenkuTextButton(tr("浏览器下载"), onBrowser, wenkuSymbol = WenkuSymbol.Globe)
                    WenkuTextButton(tr("查看完整更新说明"), onReleasePage, wenkuSymbol = WenkuSymbol.Document)
                }
            }

            val full = Modifier.fillMaxWidth()
            when (state.stage) {
                AppUpdateStage.DOWNLOADING, AppUpdateStage.VERIFYING ->
                    WenkuTextButton(tr("收起"), { close {} }, full, selected = true, wenkuSymbol = WenkuSymbol.Down)
                AppUpdateStage.NEED_PERMISSION ->
                    WenkuTextButton(tr("允许安装"), onPermission, full, selected = true, enabled = !exporting, wenkuSymbol = WenkuSymbol.Shield)
                AppUpdateStage.READY, AppUpdateStage.INSTALLING ->
                    WenkuTextButton(tr("安装"), onInstall, full, selected = true, enabled = !exporting, wenkuSymbol = WenkuSymbol.Upload)
                AppUpdateStage.AVAILABLE, AppUpdateStage.FAILED -> if (release.apk != null) {
                    Column(full, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        WenkuTextButton(
                            if (state.stage == AppUpdateStage.FAILED) tr("重新下载（GitHub）") else tr("GitHub 下载并安装"),
                            onDownload, full, selected = true,
                            wenkuSymbol = if (state.stage == AppUpdateStage.FAILED) WenkuSymbol.Refresh else WenkuSymbol.Download,
                        )
                        WenkuTextButton(
                            tr("HTTPS 镜像下载（gh-proxy.com）"),
                            onDownloadMirror, full,
                            enabled = mirrorUrl != null && !exporting,
                            wenkuSymbol = WenkuSymbol.Globe,
                        )
                    }
                } else {
                    WenkuTextButton(tr("打开发布页"), onReleasePage, full, selected = true, wenkuSymbol = WenkuSymbol.External)
                }
            }
            WenkuTextButton(
                if (state.transferring) tr("取消下载") else tr("稍后"),
                onClick = if (state.transferring) onCancel else ({ close {} }),
                modifier = full.padding(bottom = 6.dp),
                enabled = !exporting,
                wenkuSymbol = if (state.transferring) WenkuSymbol.Close else null,
            )
        }
    }
}

internal fun updateBytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
