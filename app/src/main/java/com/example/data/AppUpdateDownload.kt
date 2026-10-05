package com.example.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.BuildConfig
import com.example.source.SharedHttpTransport
import com.example.source.executeCancellable
import com.example.source.zlibrary.network.SystemProxyResolver
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal class AppUpdateDownload(
    private val context: Context,
    private val client: OkHttpClient = SharedHttpTransport.builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build(),
) {
    suspend fun download(asset: UpdateApkAsset, tag: String, destination: File, onProgress: (Long, Long?) -> Unit) =
        withContext(Dispatchers.IO) {
            require(officialUpdateAsset(tag, asset.name, asset.url)) { "安装包地址不属于此版本的官方发布" }
            require(asset.size == null || asset.size in 1..MAX_UPDATE_BYTES) { "安装包大小异常" }
            val proxy = SystemProxyResolver.resolve(context.applicationContext)
            val transport = client.newBuilder().followRedirects(false).followSslRedirects(false)
                .apply { if (proxy != null) proxy(proxy) }.build()
            var next = asset.url.toHttpUrl()
            var finished = false
            try {
                for (redirect in 0..5) {
                    ensureActive()
                    val call = transport.newCall(Request.Builder().url(next)
                        .header("User-Agent", "Ciallo-Reader/${BuildConfig.VERSION_NAME}")
                        .header("Accept", "application/octet-stream").header("Accept-Encoding", "identity").build())
                    // Keep cancellation attached until the entire body is copied, not just until
                    // headers arrive. Cancelling closes a stalled socket immediately.
                    val moved = coroutineScope {
                        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
                            try { awaitCancellation() } finally { call.cancel() }
                        }
                        try {
                            call.executeCancellable().use { response ->
                                if (response.code in setOf(301, 302, 303, 307, 308)) {
                                    next = response.header("Location")?.let(next::resolve)
                                        ?: error("下载跳转缺少地址")
                                    require(next.scheme == "https" && next.port == 443 &&
                                        next.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com") &&
                                        next.username.isEmpty() && next.password.isEmpty()) { "下载跳转不是 GitHub 官方安全地址" }
                                    true
                                } else {
                                    check(response.code == 200) { "安装包下载失败（HTTP ${response.code}），可重试或使用浏览器下载" }
                                    val body = response.body ?: error("安装包下载为空")
                                    val length = body.contentLength().takeIf { it >= 0 }
                                    require(length == null || length in 1..MAX_UPDATE_BYTES) { "安装包大小异常" }
                                    if (asset.size != null && length != null) require(asset.size == length) { "安装包大小与发布信息不符" }
                                    val total = asset.size ?: length
                                    var received = 0L
                                    var lastProgress = 0L
                                    onProgress(0, total)
                                    FileOutputStream(destination).use { output ->
                                        body.byteStream().use { input ->
                                            val buffer = ByteArray(64 * 1024)
                                            while (true) {
                                                coroutineContext.ensureActive()
                                                val read = input.read(buffer)
                                                if (read < 0) break
                                                received += read
                                                require(received <= MAX_UPDATE_BYTES && (total == null || received <= total)) { "安装包大小异常" }
                                                output.write(buffer, 0, read)
                                                val now = System.nanoTime()
                                                if (now - lastProgress >= 100_000_000) {
                                                    onProgress(received, total)
                                                    lastProgress = now
                                                }
                                            }
                                        }
                                        output.fd.sync()
                                    }
                                    check(received > 0 && (total == null || received == total)) { "安装包下载不完整，请重试" }
                                    coroutineContext.ensureActive()
                                    onProgress(received, total ?: received)
                                    false
                                }
                            }
                        } finally { cancellation.cancel() }
                    }
                    if (!moved) { finished = true; break }
                }
                check(finished) { "下载跳转次数过多，请使用浏览器下载" }
            } finally {
                if (!finished) destination.delete()
            }
        }
}

internal fun updateFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/** A browser fallback remains available if these checks reject a legacy signing key. */
internal class UpdateApkVerifier(private val context: Context) {
    @Suppress("DEPRECATION")
    fun verify(file: File, release: UpdateRelease): Long {
        require(file.length() in 1..MAX_UPDATE_BYTES) { "安装包为空或过大" }
        val asset = release.apk ?: error("此版本没有可用安装包")
        require(asset.size == null || file.length() == asset.size) { "安装包大小校验失败" }
        if (asset.sha256 != null) require(updateFileSha256(file) == asset.sha256) { "安装包完整性校验失败，请重新下载" }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("下载文件不是有效的 Android 安装包")
        val installed = pm.getPackageInfo(context.packageName, flags)
        require(archive.packageName == context.packageName) { "安装包不属于 Ciallo Reader" }
        require(archive.versionName == release.version) { "安装包版本与更新信息不符" }
        require(code(archive) > code(installed)) { "安装包不是比当前更新的版本" }
        require((archive.applicationInfo?.minSdkVersion ?: 24) <= Build.VERSION.SDK_INT) { "新版本不支持当前 Android 系统" }
        val original = signatures(installed)
        require(original.isNotEmpty() && original == signatures(archive)) {
            "新版本签名与当前应用不一致，无法直接覆盖安装。请先备份数据，再查看发布页的迁移说明"
        }
        return code(archive)
    }

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            ?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
            ?.toSet().orEmpty()

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
}
