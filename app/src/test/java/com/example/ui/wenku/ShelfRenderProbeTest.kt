package com.example.ui.wenku

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.Book
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 书架渲染探针（纯 JVM，不需要模拟器）：把 3D 书架画到一张位图上再存盘，
 * 用于肉眼/脚本核对"某本书是不是变成了没有封面的黑块"。
 *
 * 不用 `captureToImage()`：Robolectric 的窗口捕获路径会卡在 forceRedraw 上
 * （见 ShelfOpenSeamTest 的历史记录），这里直接 `decorView.draw(canvas)`，
 * 也就是 Roborazzi 抓图用的同一条路。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ShelfRenderProbeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun books(count: Int): List<Book> = List(count) { index ->
        Book(
            id = 100 + index,
            title = "测试书名${index + 1}",
            author = "作者${index + 1}",
            filePath = "",
            totalChapters = 40 + index * 60
        )
    }

    private fun dump(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val width = if (view.width > 0) view.width else 1080
        val height = if (view.height > 0) view.height else 800
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val file = File("build/tmp/probe/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PROBE $name -> ${file.absolutePath} ${width}x${height}")
    }

    private fun render(name: String, count: Int, selected: Int) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ArchiveShelf(
                        books = books(count),
                        onBookClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        initialIndex = selected,
                        showChrome = false,
                        scopeKey = "probe-$name"
                    )
                }
            }
        }
        repeat(240) { compose.mainClock.advanceTimeByFrame() }
        dump(name)
    }

    /**
     * 「退出阅读器后中间书右边某本变黑」的回归探针：
     * 读完之后回到书架，是以**刚读完那本**为中心的，旧的 ±5 预热窗口会把某一本挤出去，
     * 那本没有贴图 → 画成 `fallbackTint`（1/3 是深蓝灰）→ 看起来就是黑封面。
     */
    private fun renderReturning(name: String, count: Int, readBookId: Int) {
        ArchiveShelfMemory.remember("probe-$name", readBookId)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ArchiveShelf(
                        books = books(count),
                        onBookClick = {},
                        modifier = Modifier.fillMaxWidth(),
                        showChrome = false,
                        scopeKey = "probe-$name"
                    )
                }
            }
        }
        repeat(240) { compose.mainClock.advanceTimeByFrame() }
        dump(name)
    }

    @Test
    fun renderShelfAtSeveralPositions() {
        render("rest-first", 12, 0)
    }

    @Test
    fun renderBigShelf() {
        render("rest-big", 30, 15)
    }

    @Test
    fun renderAfterReading() {
        renderReturning("return-after-read", 12, 105)
    }
}
