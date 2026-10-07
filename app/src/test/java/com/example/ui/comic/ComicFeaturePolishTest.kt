package com.example.ui.comic

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.test.core.app.ApplicationProvider
import com.example.mangatranslate.LlmBubbleTranslator
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.lang.reflect.Modifier

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComicFeaturePolishTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun pages(count: Int) = List(count) { ComicPageRef.Local("page$it", "$it.png") }

    @Test fun everySettingHasAPersistedKeyAndRoundTripsTogether() {
        val config = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.LTR,
            webtoonSnap = false, fit = ComicFit.CUSTOM, customFitBase = ComicFit.FIT_HEIGHT,
            customFitScale = 1.75f, pageSpacingDp = 22f, doubleGapDp = 18f, doubleFirstAlone = true,
            doubleAlign = ComicDoubleAlign.BOTTOM, doubleShiftXDp = -12f, doubleShiftYDp = 17f,
            doubleTapZoom = false, longPressZoom = false, zoomWhileTurn = true, bookRotation = 270,
            cropMode = ComicCropMode.AUTO, manualCrop = listOf(.1f, .15f, .9f, .85f),
            splitWide = true, splitReverse = true, splitPosition = .6f, enhanceMode = ComicEnhanceMode.ANIME4K,
            enhanceStrength = 77, filterBrightness = -12, filterContrast = 25, filterSaturation = -17,
            filterHue = 46, filterGamma = 1.4f, filterSharpen = 55, filterShadow = 15, filterBW = true,
            bgType = ComicBgType.PAPER, paperIntensity = 72, scene = ComicScene.SAKURA,
            sceneSound = false, sceneEffect = false, sceneVolume = 32, pageAnim = ComicPageAnim.FADE,
            autoPageIntervalSec = 19f, autoScrollSpeedDp = 125f, gestureTapLeft = ComicGestureAction.TOC,
            gestureTapCenter = ComicGestureAction.SETTINGS, gestureTapRight = ComicGestureAction.EXIT,
            gestureSwipe = false, gesturePinchClose = false, gestureEdgeSwipe = false,
            gestureLongPressPanel = true, volumeKeyTurn = false, showThumbPreview = false,
            hideSystemBars = false, translationEnabled = true, translationLang = "ko",
            translationTextScale = 1.3f, translationEngine = "ai")
        val fields = ComicReaderConfig::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.map { it.name }.toSet()
        assertEquals(fields, config.toJson().keys().asSequence().toSet())
        assertEquals(config, ComicReaderConfig.fromJson(config.toJson()))
        val store = ComicSettingsStore(context)
        store.saveBookConfig("all-functions", config)
        assertEquals(config, store.effectiveConfig("all-functions").config)
    }

    @Test fun nonFiniteAndOutOfRangeSettingsCannotReachLayout() {
        val normalized = ComicReaderConfig(customFitScale = Float.NaN, customFitBase = ComicFit.CUSTOM,
            pageSpacingDp = -800f, doubleGapDp = Float.POSITIVE_INFINITY, doubleShiftXDp = 500f,
            doubleShiftYDp = Float.NaN, filterGamma = Float.NaN, bookRotation = -90,
            splitPosition = Float.NaN, autoPageIntervalSec = Float.NaN, autoScrollSpeedDp = Float.POSITIVE_INFINITY,
            manualCrop = listOf(0f, 0f, Float.NaN, 1f)).normalized()
        assertEquals(ComicFit.FIT_PAGE, normalized.customFitBase)
        assertEquals(1f, normalized.customFitScale, 0f)
        assertEquals(0f, normalized.pageSpacingDp, 0f)
        assertEquals(8f, normalized.doubleGapDp, 0f)
        assertEquals(40f, normalized.doubleShiftXDp, 0f)
        assertEquals(0f, normalized.doubleShiftYDp, 0f)
        assertEquals(1f, normalized.filterGamma, 0f)
        assertEquals(270, normalized.bookRotation)
        assertEquals(6f, normalized.autoPageIntervalSec, 0f)
        assertEquals(40f, normalized.autoScrollSpeedDp, 0f)
        assertNull(normalized.manualCrop)
        assertEquals(normalized, ComicReaderConfig.fromJson(normalized.toJson()))
    }

    @Test fun malformedImportedCropIsDiscardedInsteadOfCrashingTheEditor() {
        for (crop in listOf(listOf(0f), listOf(.8f, .1f, .3f, .9f), listOf(.1f, .7f, .9f, .72f))) {
            assertNull(ComicReaderConfig(manualCrop = crop).normalized().manualCrop)
        }
        assertEquals(listOf(0f, 0f, 1f, 1f), ComicReaderConfig(manualCrop = listOf(-1f, -1f, 2f, 2f)).normalized().manualCrop)
    }

    @Test fun settingsStoreRepairsInvalidConfigBeforeJsonSerialization() {
        val store = ComicSettingsStore(context)
        store.saveGlobalConfig(ComicReaderConfig(filterGamma = Float.NaN))
        assertEquals(1f, store.loadGlobalConfig().filterGamma, 0f)
        val preset = store.createPreset("  ", "SC", ComicReaderConfig(customFitScale = Float.NaN))
        assertEquals("我的预设", preset.name)
        assertEquals(1f, preset.config.customFitScale, 0f)
        val fit = store.saveCustomFitPreset("  ", ComicFit.CUSTOM, 100)
        assertEquals(ComicFit.FIT_PAGE, fit.base)
    }

    @Test fun manualMergeOverridesSplittingOnBothPagesWithoutLosingContent() {
        val refs = pages(4)
        val sizes = refs.associate { it.id to SizeI(2400, 1200) }
        for (direction in ComicDirection.entries) {
            val layout = ComicPageLayout.build(refs, sizes,
                ComicReaderConfig(splitWide = true, direction = direction), ComicBookState(mergeAnchors = setOf(0)))
            assertEquals(listOf(0, 1), layout.spreads.first().slots.map { it.rawIndex })
            assertTrue(layout.spreads.first().slots.all { it.half == ComicSplitHalf.FULL })
            assertEquals(setOf(0, 1, 2, 3), layout.spreads.flatMap { it.slots }.map { it.rawIndex }.toSet())
        }
    }

    @Test fun overlappingAndLastPageMergeAnchorsAreRepairedDeterministically() {
        val layout = ComicPageLayout.build(pages(6), emptyMap(), ComicReaderConfig(),
            ComicBookState(mergeAnchors = setOf(-1, 0, 1, 3, 5, 99)))
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4), listOf(5)),
            layout.spreads.map { it.slots.map { slot -> slot.rawIndex } })
    }

    @Test fun verticalModesNeverDropAMergedPage() {
        for (mode in listOf(ComicMode.WEBTOON, ComicMode.CONTINUOUS)) {
            val layout = ComicPageLayout.build(pages(5), emptyMap(), ComicReaderConfig(mode = mode),
                ComicBookState(mergeAnchors = setOf(0, 2)))
            assertEquals(listOf(0, 1, 2, 3, 4), layout.spreads.map { it.slots.single().rawIndex })
        }
    }

    @Test fun zeroStrengthIsIdentityForEveryEnhancementEngine() {
        val src = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffa2b3c4.toInt()) }
        for (mode in ComicEnhanceMode.entries) {
            val tone = ComicImagePipeline.Toning(enhanceMode = mode, enhanceStrength = 0)
            assertFalse(ComicImagePipeline.toningHasWork(tone))
            assertSame(src, ComicImagePipeline.process(src, ComicImagePipeline.Geometry(), tone))
            assertEquals(0.0, ComicImagePipeline.enhanceEstimateSec(mode, 0, 2800), 0.0)
        }
    }

    @Test fun zeroPaperIntensityIsFlatAndHigherIntensityAddsTexture() {
        val flat = ComicReaderBackgrounds.paperTextureRaw(0)
        val sample = flat.getPixel(0, 0)
        for (y in 0 until flat.height step 13) for (x in 0 until flat.width step 11) assertEquals(sample, flat.getPixel(x, y))
        val textured = ComicReaderBackgrounds.paperTextureRaw(100)
        assertTrue((0 until 128).map { textured.getPixel(it, it) }.toSet().size > 3)
    }

    @Test fun doublePageGeometryHonorsEveryFitAndCustomMultiplier() {
        val spread = ComicSpread(0, pages(2).mapIndexed { i, page -> ComicSlot(page, i) })
        val intrinsic = Size(600f, 1200f)
        for (fit in ComicFit.entries) {
            val cfg = ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.LTR,
                doubleGapDp = 16f, fit = fit, customFitScale = 1.5f, customFitBase = ComicFit.FIT_HEIGHT)
            val rects = curlPageRects(true, spread, cfg, 1200f, 800f, 1f) { intrinsic }!!
            val expected = fittedSize(intrinsic, Size(592f, 800f), fit, 1.5f, ComicFit.FIT_HEIGHT)
            assertEquals(expected.width, rects.first.width(), .01f)
            assertEquals(expected.height, rects.first.height(), .01f)
            assertEquals(16f, rects.second.left - rects.first.right, .01f)
        }
    }

    @Test fun compatibleAiEndpointsAcceptBaseVersionAndCompletePaths() {
        val translator = LlmBubbleTranslator(context)
        assertEquals("https://api.example.com/chat/completions", translator.compatibleEndpoint(" https://api.example.com/ "))
        assertEquals("https://api.example.com/v1/chat/completions", translator.compatibleEndpoint("https://api.example.com/v1/"))
        assertEquals("https://api.example.com/v1/chat/completions", translator.compatibleEndpoint("https://api.example.com/v1/chat/completions"))
        assertFalse(LlmBubbleTranslator.LlmConfig("garbage", "", "model", false).isValid())
    }

    @Test fun aiResponsesWithBlankTranslationsAreRejectedForRetry() {
        val translator = LlmBubbleTranslator(context)
        assertNull(translator.parseStrict("""{"items":[{"id":0,"translation":" "}]}""", listOf(LlmBubbleTranslator.Item(0, "hello"))))
    }
    @Test fun mergeFromEitherEntryRemovesAdjacentAnchorsAndCanBeCancelled() {
        val state = ComicBookState(mergeAnchors = setOf(0, 2, 4)).toggleMerge(1, 6)
        assertEquals(setOf(1, 4), state.mergeAnchors)
        assertEquals(setOf(4), state.toggleMerge(1, 6).mergeAnchors)
    }

    @Test fun mergeOnTheLastPageAndInvalidIndicesCannotCreateAnInvisibleMerge() {
        val state = ComicBookState()
        for (index in listOf(-1, 5, 6)) assertSame(state, state.toggleMerge(index, 6))
        assertSame(state, state.toggleMerge(0, 0))
    }

    @Test fun validMinimumCropSurvivesFloatingPointRoundingOnSave() {
        val crop = listOf(.3f, .4f, .35f, .45f)
        val config = ComicReaderConfig(cropMode = ComicCropMode.OFF, manualCrop = crop)
        assertEquals(crop, config.normalized().manualCrop)
        val store = ComicSettingsStore(context)
        store.saveBookConfig("minimum-crop", config)
        assertEquals(crop, store.loadBookConfig("minimum-crop")!!.manualCrop)
    }

}
