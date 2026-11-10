package com.example.ui

import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coil.imageLoader
import com.example.download.DownloadState
import com.example.library.NovelDetailSheet
import com.example.source.ComicChapter
import com.example.source.SearchBook
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.reader.coverGalleryDestination
import com.example.ui.reader.openCoverGallery
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CoverGalleryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun coverOpensRealRouteAndReturnsToTheSameDetail() {
        val cover = makeCover()
        val albumTag = "WenkuCoverFixture${System.nanoTime()}"
        try {
            val book = SearchBook("fixture", "fixture", "封面 / # 漫画 $albumTag", "作者", cover = Uri.fromFile(cover).toString())
            compose.setContent {
                MyApplicationTheme {
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "detail") {
                        composable("detail") {
                            ComicChaptersScreen(
                                book = book,
                                chapters = listOf(ComicChapter("first", "测试章节")),
                                loading = false,
                                error = null,
                                downloadingChapters = emptySet(),
                                downloadProgress = emptyMap(),
                                pausedChapters = emptySet(),
                                onBack = {},
                                onRetry = {},
                                onChapterClick = {},
                                onDownloadChapter = {},
                                onDownloadAll = {},
                                onPauseDownload = {},
                                onResumeDownload = {},
                                onCancelDownload = {},
                                onOpenCover = { nav.openCoverGallery(it.cover.orEmpty(), it.title, "漫画封面") },
                            )
                        }
                        coverGalleryDestination(nav)
                    }
                }
            }

            compose.onNodeWithContentDescription("查看封面").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("漫画封面").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("IMAGE / 1 OF 1").assertIsDisplayed()
            val image = compose.onNodeWithContentDescription("漫画封面")
            val initialWidth = redWidth()
            image.performTouchInput { doubleClick(center) }
            image.assertIsDisplayed()
            assertTrue("Double tap should enlarge the fitted cover", redWidth() > initialWidth)
            image.performTouchInput { doubleClick(center) }
            assertEquals(initialWidth, redWidth())
            image.performTouchInput {
                pinch(Offset(width * .4f, center.y), Offset(width * .6f, center.y),
                    Offset(width * .18f, center.y), Offset(width * .82f, center.y), 480)
            }
            assertTrue("Pinch should enlarge the fitted cover", redWidth() > initialWidth)
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                compose.onNodeWithContentDescription("保存图片").performClick()
                compose.waitUntil(10_000) { savedImages(albumTag).isNotEmpty() }
                val saved = savedImages(albumTag).single()
                val decoded = compose.activity.contentResolver.openInputStream(saved)?.use { BitmapFactory.decodeStream(it) }
                try {
                    assertEquals(180, decoded?.width)
                    assertEquals(270, decoded?.height)
                } finally {
                    decoded?.recycle()
                }
            }
            image.performTouchInput { click(center) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("查看封面").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(book.title).assertIsDisplayed()
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                savedImages(albumTag).forEach { compose.activity.contentResolver.delete(it, null, null) }
            }
            cover.delete()
        }
    }

    @Test fun closingNovelCoverRestoresTheDetailSheetAndItsDownloadState() {
        val cover = makeCover()
        try {
            val book = SearchBook("fixture", "fixture", "测试小说", "作者", cover = Uri.fromFile(cover).toString())
            compose.setContent {
                MyApplicationTheme {
                    NovelDetailSheet(
                        book = book,
                        sourceName = "本地夹具",
                        imageLoader = compose.activity.imageLoader,
                        loading = false,
                        error = null,
                        state = DownloadState.Paused(downloadedBytes = 100, totalBytes = 200),
                        onDismiss = {},
                        onDownload = {},
                        onRead = {},
                        onPause = {},
                        onResume = {},
                        onCancel = {},
                    )
                }
            }
            compose.onNodeWithText("继续整本下载").assertIsDisplayed()
            compose.onNodeWithContentDescription("查看封面", substring = true).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("小说封面").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("返回").performClick()
            compose.onNodeWithContentDescription("小说封面").assertDoesNotExist()
            compose.onNodeWithText("继续整本下载").assertIsDisplayed()
            compose.onNodeWithText("测试小说").assertIsDisplayed()
        } finally {
            cover.delete()
        }
    }

    private fun savedImages(tag: String): List<Uri> {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return compose.activity.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Images.Media.IS_PENDING} = 0",
            arrayOf("%$tag%"),
            null,
        )?.use { rows ->
            buildList {
                while (rows.moveToNext()) add(ContentUris.withAppendedId(collection, rows.getLong(0)))
            }
        }.orEmpty()
    }

    private fun makeCover(): File {
        val cover = File.createTempFile("封面 + #", ".png", compose.activity.cacheDir)
        val bitmap = Bitmap.createBitmap(180, 270, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.RED)
            cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        return cover
    }

    private fun redWidth(): Int {
        val pixels = compose.onNodeWithContentDescription("漫画封面").captureToImage().toPixelMap()
        return (0 until pixels.width).count { x ->
            val pixel = pixels[x, pixels.height / 2]
            pixel.red > .9f && pixel.green < .2f && pixel.blue < .2f
        }
    }

}
