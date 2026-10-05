package com.example.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GithubUpdateCheckerTest {
    private val web = "https://github.com/roxycon-dev/Ciallo-Reader/releases/latest"
    private fun checker(answer: (Request) -> Pair<Int,String>) = GithubUpdateChecker(client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = answer(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .apply { if (code == 302) header("Location",body) }
                .body((if (code == 302) "" else body).toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun comparesNumericVersionsAndDoesNotOfferAnOlderRelease() = runBlocking {
        for ((local,remote,newer) in listOf(Triple("1.2.2","v1.2.1",false),Triple("1.2.2","v1.2.2",false),
            Triple("1.2.9","v1.2.10",true),Triple("1.2.2","v1.3.0",true),Triple("1.2.2","v1.2.2.0",false))) {
            val result=checker { 200 to """{"tag_name":"$remote"}""" }.check(local)
            assertEquals("$local/$remote",newer,result.releaseUrl!=null)
            if (!newer) assertTrue(result.message.contains("最新版本"))
        }
    }

    @Test fun newVersionLinksToItsExactOfficialRelease() = runBlocking {
        val result=checker { request ->
            assertTrue(request.header("User-Agent")!!.startsWith("Ciallo-Reader/"))
            assertNull(request.header("Authorization"))
            200 to """{"tag_name":"v1.2.2","draft":false,"prerelease":false}"""
        }.check("1.2.1")
        assertEquals("https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.2",result.releaseUrl)
    }

    @Test fun apiRateLimitFallsBackToOfficialLatestRedirect() = runBlocking {
        val calls=mutableListOf<String>()
        val result=checker { request ->
            calls+=request.url.host
            if (request.url.host=="api.github.com") 403 to "{}" else 302 to "/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.2"
        }.check("1.2.1")
        assertEquals(listOf("api.github.com","github.com"),calls)
        assertNotNull(result.releaseUrl)
        assertTrue(result.message.contains("1.2.2"))
    }

    @Test fun apiTimeoutStillAllowsTheReleasePagePath() = runBlocking {
        val result=checker { request ->
            if (request.url.host=="api.github.com") throw java.net.SocketTimeoutException("fixture")
            302 to web.replace("latest","tag/v1.2.2")
        }.check("1.2.2")
        assertTrue(result.message.contains("最新版本"))
    }

    @Test fun failedPathsExposeReasonsAndAllowAnotherAttempt() = runBlocking {
        var failing=true
        val checker=checker { request ->
            if (failing) { if (request.url.host=="api.github.com") throw java.net.UnknownHostException("fixture"); 503 to "{}" }
            else 200 to """{"tag_name":"v1.2.2"}"""
        }
        val first=checker.check("1.2.2")
        assertTrue(first.message.contains("域名解析失败"))
        assertTrue(first.message.contains("HTTP 503"))
        failing=false
        assertTrue(checker.check("1.2.2").message.contains("最新版本"))
    }

    @Test fun fallbackDoesNotTrustAnExternalOrDifferentRepositoryRedirect() = runBlocking {
        for (location in listOf("https://example.com/releases/tag/v1.2.3",
            "https://github.com/another/repo/releases/tag/v1.2.3",
            "https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/not-a-version",
            "https://github.com/roxycon-dev/Ciallo-Reader/releases/tag/extra/v1.2.3")) {
            val result=checker { request -> if (request.url.host=="api.github.com") 500 to "{}" else 302 to location }.check("1.2.2")
            assertNull(result.releaseUrl)
            assertTrue(result.message.contains("失败"))
        }
    }

    @Test fun invalidOrPrereleaseApiMetadataUsesOnlyAStableFallback() = runBlocking {
        for (json in listOf("{}","broken","""{"tag_name":"v1.3.0-beta","prerelease":true}""")) {
            val result=checker { request -> if (request.url.host=="api.github.com") 200 to json
                else 302 to "/roxycon-dev/Ciallo-Reader/releases/tag/v1.2.2" }.check("1.2.1")
            assertTrue(result.message.contains("1.2.2"))
        }
    }

    @Test fun cancellationIsNotTurnedIntoAFailureMessageOrAFallbackRequest() = runBlocking {
        var calls=0
        try {
            checker { calls++; throw CancellationException("cancelled") }.check("1.2.2")
            fail("Cancellation must propagate")
        } catch (_:CancellationException) { assertEquals(1,calls) }
    }
}
