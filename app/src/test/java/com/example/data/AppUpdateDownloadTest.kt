package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateDownloadTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val asset = UpdateApkAsset("Ciallo-Reader-v1.2.4.apk", "$UPDATE_REPOSITORY/releases/download/v1.2.4/Ciallo-Reader-v1.2.4.apk")
    private fun destination() = File(context.cacheDir, "update-test-${UUID.randomUUID()}.part")
    private fun downloader(answer: (Request) -> Response) = AppUpdateDownload(context, OkHttpClient.Builder()
        .addInterceptor { answer(it.request()) }.build())
    private fun response(request: Request, code: Int = 200, body: ResponseBody = "apk bytes".toResponseBody(), location: String? = null) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body).apply { location?.let { header("Location", it) } }.build()

    @Test fun followsOfficialCdnAndReportsExactProgress() = runBlocking {
        val calls = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long, Long?>>()
        val file = destination()
        try {
            downloader { request ->
                calls += request.url.host
                assertEquals("identity", request.header("Accept-Encoding"))
                assertNull(request.header("Authorization"))
                if (request.url.host == "github.com") response(request, 302, location = "https://release-assets.githubusercontent.com/asset?token=public")
                else response(request)
            }.download(asset.copy(size = 9), "v1.2.4", file) { count, total -> progress += count to total }
            assertEquals(listOf("github.com", "release-assets.githubusercontent.com"), calls)
            assertEquals("apk bytes", file.readText())
            assertEquals(9L to 9L, progress.last())
        } finally { file.delete() }
    }

    @Test fun rejectsUnsafeRedirectBeforeContactingIt() = runBlocking {
        for (url in listOf("http://github.com/apk", "https://evil.example/apk", "https://github.com:444/apk", "https://user:password@github.com/apk")) {
            var calls = 0
            val file = destination()
            try {
                downloader { calls++; response(it, 302, location = url) }.download(asset, "v1.2.4", file) { _, _ -> }
                fail(url)
            } catch (_: IllegalArgumentException) { assertEquals(1, calls); assertFalse(file.exists()) }
        }
    }

    @Test fun rejectsWrongRepositoryBeforeNetworkAccess() = runBlocking {
        var calls = 0
        try {
            downloader { calls++; response(it) }.download(asset.copy(url = asset.url.replace("roxycon-dev", "attacker")), "v1.2.4", destination()) { _, _ -> }
            fail("Expected URL rejection")
        } catch (_: IllegalArgumentException) { assertEquals(0, calls) }
    }

    @Test fun wrongContentLengthDoesNotLeaveAnInstallableFile() = runBlocking {
        val file = destination()
        try {
            downloader { response(it) }.download(asset.copy(size = 10), "v1.2.4", file) { _, _ -> }
            fail("Expected length rejection")
        } catch (_: IllegalArgumentException) { assertFalse(file.exists()) }
    }

    @Test fun emptyDownloadAndHttpErrorsCanBeRetried() = runBlocking {
        for (code in listOf(200, 403, 404, 503)) {
            val file = destination()
            try {
                downloader { response(it, code, ByteArray(0).toResponseBody()) }.download(asset, "v1.2.4", file) { _, _ -> }
                fail("$code should fail")
            } catch (_: Exception) { assertFalse(file.exists()) }
        }
        val file = destination()
        try {
            downloader { response(it) }.download(asset, "v1.2.4", file) { _, _ -> }
            assertEquals(9L, file.length())
        } finally { file.delete() }
    }

    @Test fun unknownLengthUsesIndeterminateProgressThenFinalByteCount() = runBlocking {
        val file = destination()
        val totals = mutableListOf<Long?>()
        val body = object : ResponseBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = Buffer().writeUtf8("bytes")
        }
        try {
            downloader { response(it, body = body) }.download(asset, "v1.2.4", file) { _, total -> totals += total }
            assertNull(totals.first())
            assertEquals(5L, totals.last())
        } finally { file.delete() }
    }

    @Test fun truncatedStreamAndRedirectLoopAreCleaned() = runBlocking {
        for (loop in listOf(false, true)) {
            val file = destination()
            val body = object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = 100L
                override fun source(): BufferedSource = Buffer().writeUtf8("truncated")
            }
            try {
                downloader { if (loop) response(it, 302, location = asset.url) else response(it, body = body) }
                    .download(asset, "v1.2.4", file) { _, _ -> }
                fail("Expected failure")
            } catch (_: IllegalStateException) { assertFalse(file.exists()) }
        }
    }

    @Test fun cancellationDuringBodyCopyRemovesOnlyItsOwnPartialFile() = runBlocking {
        val file = destination()
        val unrelated = destination().apply { writeText("other download") }
        val job = async {
            downloader { response(it, body = ByteArray(200_000) { 1 }.toResponseBody()) }
                .download(asset, "v1.2.4", file) { received, _ -> if (received > 0) coroutineContext.cancel() }
        }
        try {
            job.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(file.exists())
            assertEquals("other download", unrelated.readText())
        } finally { unrelated.delete() }
    }
}
