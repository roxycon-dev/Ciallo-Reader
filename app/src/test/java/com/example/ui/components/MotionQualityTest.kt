package com.example.ui.components

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MotionQualityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun nativeControls(view: View): Int =
        (if (view is LiquidSliderView || view is LiquidSegmentView) 1 else 0) +
            (if (view is ViewGroup) (0 until view.childCount).sumOf { nativeControls(view.getChildAt(it)) } else 0)

    @Test fun ordinaryTiersKeepControlActionsWithoutNativeLiquidViews() {
        var quality by mutableStateOf(RenderQuality.MID)
        var selected by mutableIntStateOf(0)
        var value by mutableFloatStateOf(.25f)
        var checked by mutableStateOf(false)
        var clicks = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides quality) {
                Column {
                    LiquidSlider(value, { value = it }, formatLabel = { "value:$it" })
                    SegmentedPillSelector(listOf(0 to "First", 1 to "Second"), selected, { selected = it })
                    AppSwitch(checked, { checked = it }, Modifier.testTag("switch"))
                    AppActionButton("Action", { clicks++ })
                }
            }
        } }
        for (tier in listOf(RenderQuality.MID, RenderQuality.LOW, RenderQuality.MINIMAL)) {
            compose.runOnIdle { quality = tier }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(0, nativeControls(compose.activity.window.decorView)) }
            compose.onNodeWithText("Second").performClick()
            compose.onNodeWithTag("switch").performClick()
            compose.onNodeWithText("Action").performClick()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
                .performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
            compose.runOnIdle { assertEquals(1, selected); assertEquals(.75f, value, .001f) }
        }
        assertEquals(3, clicks)
        assertTrue(checked)
    }

    @Test fun switchingTiersKeepsValuesAndReleasesNativeRenderers() {
        var quality by mutableStateOf(RenderQuality.HIGH)
        var value by mutableFloatStateOf(.4f)
        var selected by mutableIntStateOf(1)
        var callbacks = 0
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides quality) {
                Column {
                    LiquidSlider(value, { value = it; callbacks++ })
                    SegmentedPillSelector(listOf(0 to "First", 1 to "Second"), selected, { selected = it; callbacks++ })
                }
            }
        } }
        for (tier in listOf(RenderQuality.HIGH, RenderQuality.MAX, RenderQuality.MID, RenderQuality.LOW, RenderQuality.HIGH)) {
            compose.runOnIdle { quality = tier }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(if (tier.enhancedMotion) 2 else 0, nativeControls(compose.activity.window.decorView))
                assertEquals(.4f, value, 0f)
                assertEquals(1, selected)
                assertEquals(0, callbacks)
            }
        }
    }

    @Test fun ordinaryRangeStillEnforcesItsMinimumGap() {
        var start by mutableFloatStateOf(.2f)
        var end by mutableFloatStateOf(.8f)
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MID) {
                LiquidSlider(start, {}, rangeEnd = end, minGap = .2f, onRangeChange = { a, b -> start = a; end = b })
            }
        } }
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))[0]
            .performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        compose.runOnIdle {
            assertEquals(.6f, start, .001f)
            assertTrue(end - start >= .1999f)
            assertEquals(.8f, end, .001f)
        }
    }

    @Test fun loweringQualityWhilePaperIsOpenUsesAnOrdinaryExit() {
        var quality by mutableStateOf(RenderQuality.HIGH)
        val state = MutableTransitionState(false)
        var life: PaperLifecycle? = null
        compose.setContent { MaterialTheme {
            CompositionLocalProvider(LocalRenderQuality provides quality) {
                PullOutVisibility(state) {
                    val owner = LocalPaperOwner.current
                    SideEffect { life = owner }
                    Text("Paper content")
                }
            }
        } }
        compose.runOnIdle {
            PullOutSources.activate(PullOutSource().apply { bounds = Rect(20f, 20f, 120f, 70f) })
            state.targetState = true
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requireNotNull(life).softPaper); quality = RenderQuality.MID }
        compose.waitForIdle()
        compose.runOnIdle { assertFalse(requireNotNull(life).softPaper); state.targetState = false }
        compose.waitForIdle()
        compose.onNodeWithText("Paper content").assertDoesNotExist()
        compose.runOnIdle { assertTrue(requireNotNull(life).closed) }
    }
}
