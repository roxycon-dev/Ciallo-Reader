package com.example.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

internal class AppUpdateInstaller(
    private val context: Context,
    private val contentUri: (File) -> Uri = {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
    },
) {
    @Suppress("DEPRECATION")
    fun canInstall(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26) context.packageManager.canRequestPackageInstalls()
        else Settings.Secure.getInt(context.contentResolver, Settings.Secure.INSTALL_NON_MARKET_APPS, 0) == 1
    }.getOrDefault(false)

    fun permissionIntent(): Intent = if (Build.VERSION.SDK_INT >= 26)
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    else Intent(Settings.ACTION_SECURITY_SETTINGS)

    fun installationIntent(file: File): Intent {
        check(file.isFile) { "安装包已不存在，请重新下载" }
        val uri = contentUri(file)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("Ciallo Reader 安装包", uri) }
    }
}
