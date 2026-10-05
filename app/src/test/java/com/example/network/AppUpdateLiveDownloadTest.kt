package com.example.network

import android.content.Context
import android.net.ProxyInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppUpdateDownload
import com.example.data.GithubUpdateChecker
import com.example.data.updateFileSha256
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [KeywordLookupLiveTest.ProxyConnectivityShadow::class])
class AppUpdateLiveDownloadTest {
    @Test fun downloadsTheOfficialApkThroughProductionProxyAndFallbackPath() = runBlocking {
        val port = System.getenv("CIALLO_KEYWORD_LIVE_PROXY_PORT")?.toIntOrNull()
        assumeTrue("Live network check must be explicitly enabled", port != null)
        val context = ApplicationProvider.getApplicationContext<Context>()
        KeywordLookupLiveTest.ProxyConnectivityShadow.systemProxy = ProxyInfo.buildDirectProxy("127.0.0.1", port!!)
        val api = GithubUpdateChecker(context).check("0.0.0").release
        val fallback = GithubUpdateChecker(context, apiUrl = "https://api.github.com/repos/roxycon-dev/Ciallo-Reader/releases/tags/__missing_download_probe__")
            .check("0.0.0").release
        assertNotNull(api)
        assertNotNull(fallback)
        assertEquals(api!!.tag, fallback!!.tag)
        val asset = fallback.apk!!
        assertNotNull(api.apk)
        assertEquals(api.apk!!.url, asset.url)
        val file = File(context.cacheDir, "live-update-${UUID.randomUUID()}.part")
        val progress = mutableListOf<Long>()
        try {
            AppUpdateDownload(context).download(asset, fallback.tag, file) { count, _ -> progress += count }
            val hash = updateFileSha256(file)
            api.apk!!.size?.let { assertEquals(it, file.length()) }
            api.apk!!.sha256?.let { assertEquals(it, hash) }
            assertEquals(file.length(), progress.last())
            assertTrue(progress.zipWithNext().all { (a, b) -> b >= a })
            val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
                .first { File(it, "gradlew.bat").exists() }
            val formal = File(root, "artifacts/release-${fallback.version}/${asset.name}")
            if (formal.exists()) assertEquals(updateFileSha256(formal), hash)
            val result = File(root, "artifacts/update-flow-2026-10-05/live-download.json")
            result.parentFile.mkdirs()
            result.writeText(JSONObject().put("tag", fallback.tag).put("url", asset.url)
                .put("bytes", file.length()).put("sha256", hash).put("matchesLocalFormalApk", formal.exists())
                .put("progressEvents", progress.size).put("apiAssetMetadataPresent", api.apk!!.size != null)
                .put("environment", "Real HTTPS + production downloader via system proxy; no phone installation test")
                .toString(2))
        } finally { file.delete() }
    }
}
