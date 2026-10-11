package com.example.ui.comic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.reader.LocalReadingGlass
import com.example.ui.reader.ReadingGlassStyle
import com.example.ui.theme.MyApplicationTheme
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Shader
import org.junit.Assert.assertNotNull

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class ComicGlassPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    private fun probe(name: String, frozen: Boolean = false) {
        val loader = ComicPageLoader(compose.activity)
        var config by mutableStateOf(ComicReaderConfig(pageAnim = if (frozen) ComicPageAnim.CURL else ComicPageAnim.SLIDE))
        val page = File(compose.activity.cacheDir, "motion-glass-page.png")
        val slots = if (frozen) {
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawRect(0f, 0f, 480f, 800f, Paint().apply {
                shader = LinearGradient(0f, 0f, 480f, 800f, intArrayOf(0xFF367FBC.toInt(), 0xFFDBAB79.toInt()), null, Shader.TileMode.CLAMP)
            })
            page.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            listOf(ComicSlot(ComicPageRef.Local("glass-source", page.absolutePath), 0))
        } else emptyList()
        try {
            compose.setContent { MyApplicationTheme(darkTheme = true) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Brush.linearGradient(listOf(Color(0xFF4B586F),Color(0xFFAE9780))))) {
                        repeat(12) { Text("光影与旅途   星海图书", fontSize = 28.sp, color = Color.White.copy(alpha = .7f), modifier = Modifier.padding(12.dp)) }
                    }
                    if (frozen) ComicFrozenGlassBackdrop(backdrop, slots, config, loader, ComicBookState(), null)
                    CompositionLocalProvider(LocalReadingGlass provides ReadingGlassStyle(backdrop)) {
                        ComicSettingsSheet(config, ComicSettingsStore(compose.activity), false, {}, { config = it }, {}, {},
                            slots.map { it.ref }, loader, 0, ComicBookState(), {}, {}, glassBackdrop = backdrop)
                    }
                }
            } }
            compose.waitForIdle()
            if (frozen) {
                compose.waitUntil(10_000) { loader.peekProcessed("glass-source|FULL|${config.imagePipelineFingerprint()}|r0") != null }
                compose.waitForIdle()
                assertNotNull(loader.peekProcessed("glass-source|FULL|${config.imagePipelineFingerprint()}|r0"))
            }
            compose.onNodeWithText("阅读设置").assertExists()
            val root = generateSequence(File(System.getProperty("user.dir")!!)) { it.parentFile }.first { File(it,"gradlew.bat").exists() }
            val path = File(root,"artifacts/motion-polish/$name.png").apply { parentFile!!.mkdirs() }
            compose.onRoot().captureRoboImage(path.absolutePath)
            compose.onNodeWithText("显示").performClick()
            compose.onNodeWithText("适应宽度").assertIsSelected()
            compose.onNodeWithText("整页").performClick()
            compose.runOnIdle { org.junit.Assert.assertEquals(ComicFit.FIT_PAGE, config.fit) }
        } finally { loader.shutdown() }
    }
    @Test fun phoneSettingsUseGlassSectionsAndKeepModeChoicesOperable() = probe("comic-glass-phone")
    @Test @Config(qualifiers = "zh-rCN-w1280dp-h800dp-160dpi")
    fun tabletSettingsShowAllSevenGlassTabs() = probe("comic-glass-tablet")
    @Test fun curlSettingsSampleTheProcessedCurrentPageWithoutCapturingASurfaceView() = probe("comic-glass-curl", frozen = true)
}
