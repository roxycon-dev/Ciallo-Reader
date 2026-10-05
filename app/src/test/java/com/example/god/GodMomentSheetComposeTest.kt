package com.example.god

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsProperties
import coil.ImageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.ui.comic.comicRemoteCacheKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 神回窗口组合回归：复刻「阅读页末页松手 → 打开神回窗口」这条路径的组合输入
 * （hazeState 非 null = 走真毛玻璃分支，与阅读页一致）。
 *
 * 任一处组合/绘制期硬崩溃（IllegalStateException / IllegalArgumentException /
 * RenderEffect 相关）都会在这里冒出来，不用真机手动滑到末页。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class GodMomentSheetComposeTest {

    @Test
    fun pageHeadersKeepReaderReferer() {
        assertEquals("https://reader.example/", godPageHeaders(emptyMap(), "https://reader.example/")["Referer"])
        assertEquals(
            "https://source.example/",
            godPageHeaders(mapOf("referer" to "https://source.example/"), "https://reader.example/")["referer"],
        )
    }

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sheetComposesWithoutCrash() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GodMomentViewModel(app)
        val request = GodMomentRequest(
            contentType = GodContentType.COMIC,
            bookId = "local_1",
            chapterId = "1",
            bookTitle = "测试漫画",
            chapterTitle = "测试漫画",
            chapterNumber = 1,
            pages = (0 until 30).map { GodPageRef(id = "p$it", source = "", remote = false) },
            initialPageIndex = 29,
        )

        var composed = false
        compose.setContent {
            GodMomentSheet(
                request = request,
                existing = null,
                viewModel = vm,
                remoteLoader = null,
                onDismiss = {},
                onSaved = {},
            )
            composed = true
        }
        compose.waitForIdle()
        assertTrue("神回窗口未完成组合", composed)
    }

    @Test
    fun sheetComposesWithoutCrash_noHaze() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = GodMomentViewModel(app)
        val request = GodMomentRequest(
            contentType = GodContentType.COMIC,
            bookId = "local_1",
            chapterId = "1",
            bookTitle = "测试漫画",
            chapterTitle = "测试漫画",
            chapterNumber = 1,
            pages = (0 until 5).map { GodPageRef(id = "p$it", source = "", remote = false) },
            initialPageIndex = 4,
        )
        compose.setContent {
            GodMomentSheet(
                request = request,
                existing = null,
                viewModel = vm,
                remoteLoader = null,
                onDismiss = {},
                onSaved = {},
            )
        }
        compose.waitForIdle()
    }

    private fun <T> imageLoad(block: suspend CoroutineScope.() -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val task = scope.async(block = block)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        try {
            while (!task.isCompleted) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                check(System.nanoTime() < deadline) { "神回图片测试超时" }
                Thread.sleep(10)
            }
            return runBlocking { task.await() }
        } finally { scope.cancel() }
    }

    private fun imageBytes(): ByteArray = ByteArrayOutputStream().also { stream ->
        Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }.toByteArray()

    @Test fun readerMemoryAndDiskCachesServeGodThumbnailsWithoutAnotherDownload() = imageLoad {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = AtomicInteger()
        val bytes = imageBytes()
        val loader = ImageLoader.Builder(context).respectCacheHeaders(false).diskCache {
            coil.disk.DiskCache.Builder().directory(java.nio.file.Files.createTempDirectory("god-cache").toFile())
                .maxSizeBytes(4L * 1024 * 1024).build()
        }.okHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/png").body(bytes.toResponseBody()).build()
        }.build()).build()
        val ref = GodPageRef("cached", "https://god.example/cached.png", true,
            mapOf("Referer" to "https://god.example/reader", "Cookie" to "session=test"))
        try {
            val key = comicRemoteCacheKey(ref.source, ref.headers)
            val read = loader.execute(ImageRequest.Builder(context).data(ref.source).size(1600)
                .memoryCacheKey(key).diskCacheKey(key).scale(coil.size.Scale.FIT)
                .precision(coil.size.Precision.INEXACT).allowHardware(false).build()) as SuccessResult
            val thumb = GodCoverEngine.loadPage(context, ref, 320, loader)
            assertSame((read.drawable as BitmapDrawable).bitmap, thumb)
            assertEquals(1, calls.get())
            loader.memoryCache?.clear()
            assertNotNull(GodCoverEngine.loadPage(context, ref, 320, loader))
            assertEquals("阅读器磁盘缓存也应复用", 1, calls.get())
        } finally { loader.shutdown() }
    }

    @Test fun simultaneousThumbnailAndCoverOnlyDownloadOnce() = imageLoad {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val calls = AtomicInteger()
        val bytes = imageBytes()
        val loader = ImageLoader.Builder(context).respectCacheHeaders(false).diskCache {
            coil.disk.DiskCache.Builder().directory(java.nio.file.Files.createTempDirectory("god-dedupe").toFile())
                .maxSizeBytes(4L * 1024 * 1024).build()
        }.okHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Thread.sleep(80)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("Content-Type", "image/png").body(bytes.toResponseBody()).build()
        }.build()).build()
        try {
            val ref = GodPageRef("parallel", "https://god.example/parallel.png", true)
            val results = listOf(320, 1600, 320).map { edge ->
                async { GodCoverEngine.loadPage(context, ref, edge, loader) }
            }.awaitAll()
            assertTrue(results.all { it != null })
            assertEquals("同页大小图不能同时下载", 1, calls.get())
        } finally { loader.shutdown() }
    }

    @Test fun selectingUnloadedPagesKeepsLoadedImageInItsOriginalSlotAndRequestsRunning() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Global.putFloat(app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val slow = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val loader = ImageLoader.Builder(app).components {
            add(coil.intercept.Interceptor { chain ->
                if (chain.request.networkCachePolicy == CachePolicy.DISABLED) {
                    ErrorResult(null, chain.request, java.io.IOException("cache miss"))
                } else {
                    val url = chain.request.data.toString()
                    calls.getOrPut(url) { AtomicInteger() }.incrementAndGet()
                    if (!url.endsWith("0.png")) slow.await()
                    val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(if (url.endsWith("0.png")) android.graphics.Color.RED else android.graphics.Color.GREEN)
                    }
                    SuccessResult(BitmapDrawable(app.resources, bitmap), chain.request, coil.decode.DataSource.NETWORK)
                }
            })
        }.build()
        val pages = (0..2).map { GodPageRef("select$it", "https://select-${System.identityHashCode(loader)}.example/$it.png", true) }
        val vm = GodMomentViewModel(app)
        try {
            compose.setContent {
                GodMomentSheet(GodMomentRequest(GodContentType.COMIC, "select-book", "select-chapter",
                    "测试漫画", "第一话", 1, pages, 0), null, vm, loader, {}, {})
            }
            val loaded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已加载")
            val pending = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "加载中")
            try {
                compose.waitUntil(5000) { calls[pages[1].source]?.get() == 1 && calls[pages[2].source]?.get() == 1 }
            } catch (failure: Exception) {
                throw AssertionError("启动的请求：${calls.mapValues { it.value.get() }}", failure)
            }
            compose.waitUntil(5000) {
                loaded.matches(compose.onNodeWithContentDescription("第1页封面").fetchSemanticsNode())
            }
            val originalLeft = compose.onNodeWithContentDescription("第1页封面").fetchSemanticsNode().boundsInRoot.left
            for (page in listOf(1, 2, 3, 2, 1, 3)) {
                compose.onNodeWithContentDescription("第${page}页封面").performClick()
                compose.onNodeWithContentDescription("第1页封面").assert(loaded)
                compose.onNodeWithContentDescription("第2页封面").assert(pending)
                compose.onNodeWithContentDescription("第3页封面").assert(pending)
                assertEquals(originalLeft,
                    compose.onNodeWithContentDescription("第1页封面").fetchSemanticsNode().boundsInRoot.left, .1f)
            }
            assertEquals(1, calls[pages[1].source]?.get())
            assertEquals(1, calls[pages[2].source]?.get())
            slow.complete(Unit)
            compose.waitUntil(5000) {
                loaded.matches(compose.onNodeWithContentDescription("第2页封面").fetchSemanticsNode()) &&
                    loaded.matches(compose.onNodeWithContentDescription("第3页封面").fetchSemanticsNode())
            }
        } finally { slow.complete(Unit); loader.shutdown() }
    }

    @Test fun longStripThumbnailsKeepTheMiddleAndBoundTheirMemory() {
        val raw = Bitmap.createBitmap(800, 8000, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
            android.graphics.Canvas(this).drawRect(0f, 3000f, 800f, 5000f,
                android.graphics.Paint().apply { color = android.graphics.Color.GREEN })
        }
        val thumb = GodCoverEngine.thumbnailOf(raw)
        assertEquals(320, thumb.height)
        assertEquals(200, thumb.width)
        assertEquals(android.graphics.Color.GREEN, thumb.getPixel(100, 160))
    }

    @Test fun failedThumbnailCanBeRetriedByTappingItsOwnSlot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val attempts = AtomicInteger()
        val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888)
        val loader = ImageLoader.Builder(app).components {
            add(coil.intercept.Interceptor { chain ->
                if (attempts.incrementAndGet() == 1) ErrorResult(null, chain.request, java.io.IOException("offline"))
                else SuccessResult(BitmapDrawable(app.resources, bitmap), chain.request, coil.decode.DataSource.NETWORK)
            })
        }.build()
        try {
            compose.setContent {
                GodCoverSection(null, false,
                    listOf(GodPageRef("retry", "https://retry-${System.identityHashCode(loader)}.example/page.png", true)),
                    0, false, 0, loader, true, {}, {}, {})
            }
            val failed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "加载失败，点按重试")
            val loaded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已加载")
            compose.waitUntil(5000) { failed.matches(compose.onNodeWithContentDescription("第1页封面").fetchSemanticsNode()) }
            compose.onNodeWithContentDescription("第1页封面").performClick()
            compose.waitUntil(5000) { loaded.matches(compose.onNodeWithContentDescription("第1页封面").fetchSemanticsNode()) }
            assertEquals(2, attempts.get())
        } finally { loader.shutdown() }
    }

    @Test fun editingFailedPageDoesNotBorrowSavedCoverAndRetryRestoresBothImages() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        android.provider.Settings.Global.putFloat(app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val available = java.util.concurrent.atomic.AtomicBoolean(false)
        val loader = ImageLoader.Builder(app).components {
            add(coil.intercept.Interceptor { chain ->
                if (chain.request.data.toString().endsWith("1.png") && !available.get()) {
                    ErrorResult(null, chain.request, java.io.IOException("offline"))
                } else {
                    val bitmap = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(android.graphics.Color.GREEN)
                    }
                    SuccessResult(BitmapDrawable(app.resources, bitmap), chain.request, coil.decode.DataSource.NETWORK)
                }
            })
        }.build()
        val saved = java.io.File(app.cacheDir, "saved-god-cover.png").apply { writeBytes(imageBytes()) }
        val pages = (0..1).map { GodPageRef("edit$it", "https://edit-${System.identityHashCode(loader)}.example/$it.png", true) }
        val vm = GodMomentViewModel(app)
        val existing = GodMomentEntity(bookId = "edit-book", chapterId = "edit-chapter", bookTitle = "测试漫画",
            chapterNumber = 1, coverPath = saved.absolutePath, coverSource = CoverSource.ComicPage(0).toTag())
        try {
            compose.setContent {
                GodMomentSheet(GodMomentRequest(GodContentType.COMIC, "edit-book", "edit-chapter", "测试漫画",
                    "第一话", 1, pages, 0), existing, vm, loader, {}, {})
            }
            val failed = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "加载失败，点按重试")
            val loaded = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已加载")
            compose.waitUntil(5000) { failed.matches(compose.onNodeWithContentDescription("第2页封面").fetchSemanticsNode()) }
            compose.onNodeWithContentDescription("第2页封面").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("封面不可用").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("保存").assertIsNotEnabled()
            available.set(true)
            compose.onNodeWithContentDescription("第2页封面").performClick()
            compose.waitUntil(5000) {
                loaded.matches(compose.onNodeWithContentDescription("第2页封面").fetchSemanticsNode()) &&
                    compose.onAllNodesWithText("封面不可用").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText("保存").assertIsEnabled()
        } finally { loader.shutdown() }
    }
}
