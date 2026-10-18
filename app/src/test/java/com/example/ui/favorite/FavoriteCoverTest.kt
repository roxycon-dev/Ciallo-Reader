package com.example.ui.favorite

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.ImageLoader
import coil.request.SuccessResult
import com.example.library.GenericCoverLoader
import com.example.source.BookSource
import com.example.source.ComicSource
import com.example.source.SourceResult
import com.example.source.impl.MockBookSource
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FavoriteCoverTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun <T> imageLoad(block: suspend CoroutineScope.() -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = scope.async(block = block)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        try {
            while (!task.isCompleted) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                check(System.nanoTime() < deadline) { "Image load timed out" }
                Thread.sleep(10)
            }
            return runBlocking { task.await() }
        } finally { scope.cancel() }
    }

    @Test fun removedEmptyAndRejectedThumbnailsFallBackToTheOnlineCover() {
        val local = File.createTempFile("favorite-cover", ".png", context.cacheDir)
        try {
            assertEquals("https://example.test/cover", favoriteCoverData(local.path, "https://example.test/cover"))
            local.writeBytes(byteArrayOf(1))
            assertEquals(local.path, favoriteCoverData(local.path, "https://example.test/cover"))
            assertEquals("https://example.test/cover", favoriteCoverData(local.path, "https://example.test/cover", skipLocal = true))
            assertEquals("file://${local.path}", favoriteCoverData("file://${local.path}", null))
        } finally { local.delete() }
        assertEquals("https://example.test/cover", favoriteCoverData(local.path, "https://example.test/cover"))
        assertNull(favoriteCoverData("", ""))
    }

    @Test fun sourceHeadersReachTheActualCoverImageRequest() = imageLoad {
        val source = object : ComicSource, BookSource by MockBookSource(context) {
            override suspend fun getChapters(bookId: String) = SourceResult.Success(emptyList<com.example.source.ComicChapter>())
            override suspend fun getChapterImages(chapterId: String) = SourceResult.Success(emptyList<String>())
            override suspend fun getCoverHeaders(url: String) = mapOf("Referer" to "https://example.test/", "Cookie" to "session=cover", "User-Agent" to "SourceCoverAgent")
        }
        val bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
            output.toByteArray()
        }
        var accepted = false
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            accepted = request.header("Referer") == "https://example.test/" &&
                request.header("Cookie") == "session=cover" && request.header("User-Agent") == "SourceCoverAgent"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (accepted) 200 else 403)
                .message("cover").body(bytes.toResponseBody()).header("Content-Type", "image/png").build()
        }.build()
        val loader = ImageLoader.Builder(context).okHttpClient(client).build()
        try {
            val request = favoriteCoverRequest(context, "https://example.test/cover.png", source, 0)
            assertTrue(loader.execute(request) is SuccessResult)
            assertTrue(accepted)
            assertNotEquals(request, favoriteCoverRequest(context, "https://example.test/cover.png", source, 1))
            assertSame(GenericCoverLoader.get(context), GenericCoverLoader.get(context))
        } finally { loader.shutdown() }
    }

    @Test fun novelFavoritesAlsoKeepTheirSourceReferer() = runBlocking {
        for ((sourceId, referer) in mapOf("ixdzs8" to "https://ixdzs8.com/", "wenku8_library" to "https://wenku8.ywy.moe/")) {
            val source = object : BookSource by MockBookSource(context) { override val id = sourceId }
            val request = favoriteCoverRequest(context, "https://example.test/cover.png", source, 0)
            assertEquals(referer, request.headers["Referer"])
        }
    }
}
