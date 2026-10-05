package com.example.data

import android.content.Context
import com.example.BuildConfig
import com.example.source.SharedHttpTransport
import com.example.source.executeCancellable
import com.example.source.zlibrary.network.SystemProxyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class UpdateCheckResult(
    val message: String,
    val releaseUrl: String? = null,
    val release: UpdateRelease? = null,
)

internal data class UpdateApkAsset(val name: String, val url: String, val size: Long? = null, val sha256: String? = null)

internal data class UpdateRelease(val tag: String, val releaseUrl: String, val notes: String = "", val apk: UpdateApkAsset? = null) {
    val version: String get() = tag.removePrefix("v").removePrefix("V")
}

internal const val UPDATE_REPOSITORY = "https://github.com/roxycon-dev/Ciallo-Reader"
internal const val MAX_UPDATE_BYTES = 192L * 1024 * 1024

/** Only an APK belonging to this exact official release can enter the download path. */
internal fun officialUpdateAsset(tag: String, name: String, url: String): Boolean {
    if (!Regex("[vV]?\\d+(?:\\.\\d+){1,3}").matches(tag) || name.length !in 5..200 || !name.endsWith(".apk", true)) return false
    val expected = "$UPDATE_REPOSITORY/releases/download/$tag/$name"
    return runCatching {
        val parsed = url.toHttpUrl()
            parsed == expected.toHttpUrl() && parsed.pathSegments == listOf("roxycon-dev", "Ciallo-Reader", "releases", "download", tag, name) &&
            parsed.username.isEmpty() && parsed.password.isEmpty() &&
            parsed.query == null && parsed.fragment == null && name.none { it == '/' || it == '\\' } &&
            !name.contains("..")
    }.getOrDefault(false)
}

/** Public GitHub metadata only; no account token or third-party update mirror. */
internal class GithubUpdateChecker(
    private val context: Context? = null,
    private val client: OkHttpClient = SharedHttpTransport.builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build(),
    private val apiUrl: String = "https://api.github.com/repos/roxycon-dev/Ciallo-Reader/releases/latest",
    private val webUrl: String = "https://github.com/roxycon-dev/Ciallo-Reader/releases/latest",
) {
    private class CheckFailure(val reason: String) : Exception(reason)

    suspend fun check(currentVersion: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        val proxy = SystemProxyResolver.resolve(context?.applicationContext)
        val transport = client.newBuilder().apply { if (proxy != null) proxy(proxy) }.build()
        val release = try { apiRelease(transport) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            val apiFailure = failureReason(e)
            try {
                val tag = webTag(transport)
                // Our release publishing convention is stable. Archive identity and signing
                // certificates are still verified before installation when API metadata is absent.
                val name = "Ciallo-Reader-$tag.apk"
                UpdateRelease(tag, releaseUrl(tag), apk = UpdateApkAsset(name, "$UPDATE_REPOSITORY/releases/download/$tag/$name"))
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (webFailure: Exception) {
                val reasons = listOf(apiFailure, failureReason(webFailure)).distinct().joinToString("；")
                return@withContext UpdateCheckResult("检查更新失败：$reasons。可稍后重试或打开 GitHub 发布页。")
            }
        }
        val tag = release.tag
        val latest = versionParts(tag) ?: return@withContext UpdateCheckResult("无法识别 GitHub 发布版本，请打开发布页查看。")
        val current = versionParts(currentVersion) ?: return@withContext UpdateCheckResult("无法识别当前版本，请打开 GitHub 发布页查看。")
        val newer = latest.zip(current).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> a > b } == true
        val display = tag.removePrefix("v").removePrefix("V")
        if (newer) UpdateCheckResult("发现新版本 $display，可下载并安装。", releaseUrl(tag), release)
        else UpdateCheckResult("当前已是最新版本（GitHub 正式版 $display）。")
    }

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Ciallo-Reader/${BuildConfig.VERSION_NAME} (https://github.com/roxycon-dev/Ciallo-Reader)")
        .header("Accept", "application/vnd.github+json").build()

    private suspend fun apiRelease(transport: OkHttpClient): UpdateRelease {
        transport.newCall(request(apiUrl)).executeCancellable().use { response ->
            if (!response.isSuccessful) throw CheckFailure(httpFailure(response.code))
            val text = response.body?.byteStream()?.use { it.readImportBytes(512 * 1024).toString(Charsets.UTF_8) }
                ?: throw CheckFailure("GitHub 返回了空数据")
            val data = JSONObject(text)
            val tag = data.optString("tag_name")
            if (data.optBoolean("draft") || data.optBoolean("prerelease") || stableTag(tag) == null)
                throw CheckFailure("GitHub 返回的正式版本资料不完整")
            val assets = data.optJSONArray("assets")
            val candidates = (0 until (assets?.length() ?: 0)).mapNotNull { index ->
                val asset = assets?.optJSONObject(index) ?: return@mapNotNull null
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size", -1)
                if (!officialUpdateAsset(tag, name, url) || size !in 1..MAX_UPDATE_BYTES ||
                    asset.optString("state", "uploaded") != "uploaded") return@mapNotNull null
                val digest = asset.optString("digest").takeIf { it.isNotEmpty() && it != "null" }
                if (digest != null && !Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) return@mapNotNull null
                UpdateApkAsset(name, url, size, digest?.substringAfter(':')?.lowercase())
            }
            val apk = candidates.singleOrNull { it.name == "Ciallo-Reader-$tag.apk" }
                ?: candidates.singleOrNull()
            return UpdateRelease(tag, releaseUrl(tag), if (data.isNull("body")) "" else data.optString("body").take(24_000), apk)
        }
    }

    private suspend fun webTag(transport: OkHttpClient): String {
        // /releases/latest redirects to the canonical stable release. Read only
        // that same-repository Location; do not scrape HTML or follow other hosts.
        val noRedirect = transport.newBuilder().followRedirects(false).followSslRedirects(false).build()
        noRedirect.newCall(request(webUrl).newBuilder().header("Accept", "text/html").build())
            .executeCancellable().use { response ->
                if (response.code !in setOf(301, 302, 303, 307, 308)) throw CheckFailure(httpFailure(response.code))
                val base = webUrl.toHttpUrl()
                val target = response.header("Location")?.let(base::resolve)
                    ?: throw CheckFailure("GitHub 发布页未提供版本地址")
                val prefix = base.encodedPath.removeSuffix("latest") + "tag/"
                if (target.scheme != "https" || target.host != base.host || target.port != base.port ||
                    !target.encodedPath.startsWith(prefix) || target.pathSegments.size != base.pathSegments.size + 1 || target.query != null)
                    throw CheckFailure("GitHub 发布页返回了非项目版本地址")
                return target.pathSegments.last().takeIf { stableTag(it) != null }
                    ?: throw CheckFailure("GitHub 发布页版本格式异常")
            }
    }

    private fun releaseUrl(tag: String): String = webUrl.toHttpUrl().newBuilder()
        .encodedPath(webUrl.toHttpUrl().encodedPath.removeSuffix("latest") + "tag/")
        .addPathSegment(tag).build().toString()

    private fun stableTag(tag: String): List<Long>? = if (Regex("[vV]?\\d+(?:\\.\\d+){1,3}").matches(tag)) versionParts(tag) else null

    private fun versionParts(version: String): List<Long>? {
        val text = version.removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        if (!Regex("\\d+(?:\\.\\d+){1,3}").matches(text)) return null
        val parts = text.split('.').map { it.toLongOrNull() ?: return null }
        return parts + List(4 - parts.size) { 0L }
    }

    private fun httpFailure(code: Int) = when (code) {
        403, 429 -> "GitHub 接口访问受限或请求过多（HTTP $code）"
        404 -> "尚未找到正式发布版本（HTTP 404）"
        else -> "GitHub 请求未成功（HTTP $code）"
    }

    private fun failureReason(e: Exception): String = when (e) {
        is CheckFailure -> e.reason
        is java.net.UnknownHostException -> "GitHub 域名解析失败"
        is java.io.InterruptedIOException -> "连接 GitHub 超时"
        is java.net.ConnectException -> "无法连接 GitHub，请检查网络或系统代理"
        is javax.net.ssl.SSLException -> "GitHub 安全连接失败"
        is org.json.JSONException -> "GitHub 版本资料格式异常"
        else -> "无法读取 GitHub 更新信息"
    }
}
