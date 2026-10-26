package com.example.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.BuildConfig
import com.example.data.StartupDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun StartupDiagnosticsExportButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            exporting = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val report = StartupDiagnostics.read(context)
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "No output stream" }
                            .bufferedWriter(Charsets.UTF_8).use { it.write(report) }
                    }
                    AppToast.makeText(context, "诊断日志已保存", Toast.LENGTH_SHORT).show()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) {
                    withContext(Dispatchers.IO) { StartupDiagnostics.record(context, "diagnostic-export", failure) }
                    AppToast.makeText(context, "无法保存诊断日志，请重新选择保存位置", Toast.LENGTH_LONG).show()
                } finally { exporting = false }
            }
        }
    }
    AppActionButton(
        text = if (exporting) "正在导出…" else "导出启动诊断",
        onClick = {
            try { save.launch("Ciallo-Reader-diagnostic-${BuildConfig.VERSION_NAME}-${System.currentTimeMillis()}.txt") }
            catch (failure: Exception) {
                scope.launch(Dispatchers.IO) { StartupDiagnostics.record(context, "diagnostic-picker", failure) }
                AppToast.makeText(context, "无法打开文件保存窗口", Toast.LENGTH_LONG).show()
            }
        },
        enabled = !exporting,
        icon = Icons.Filled.BugReport,
        variant = AppButtonVariant.Secondary,
        fullWidth = true,
        modifier = modifier.testTag("export_startup_diagnostics_button"),
    )
}
