package com.example.network

import android.content.Context
import android.net.ProxyInfo
import androidx.test.core.app.ApplicationProvider
import com.example.data.GithubUpdateChecker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],shadows=[KeywordLookupLiveTest.ProxyConnectivityShadow::class])
class GithubUpdateLiveTest {
    @Test fun productionClientChecksTheRealApiAndOfficialWebFallback() = runBlocking {
        val port=System.getenv("CIALLO_KEYWORD_LIVE_PROXY_PORT")?.toIntOrNull()
        assumeTrue("Live network check must be explicitly enabled",port!=null)
        val context=ApplicationProvider.getApplicationContext<Context>()
        KeywordLookupLiveTest.ProxyConnectivityShadow.systemProxy=ProxyInfo.buildDirectProxy("127.0.0.1",port!!)
        val api=GithubUpdateChecker(context).check("1.2.2")
        val fallback=GithubUpdateChecker(context,apiUrl="https://api.github.com/repos/roxycon-dev/Ciallo-Reader/releases/tags/__missing_update_probe__")
            .check("1.2.2")
        assertTrue(api.message,api.message.contains("最新版本"))
        assertTrue(fallback.message,fallback.message.contains("最新版本"))
        assertNull(api.releaseUrl);assertNull(fallback.releaseUrl)
        val root=generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it,"gradlew.bat").exists() }
        val file=File(root,"artifacts/release-1.2.2/live-update-results.json")
        file.parentFile.mkdirs()
        file.writeText(JSONObject().put("apiResult",api.message).put("officialFallbackResult",fallback.message)
            .put("environment","Real HTTPS requests through Android system proxy getter in Robolectric; no user phone attached")
            .toString(2))
    }
}
