package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.ui.feedback.LocalReduceMotion
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SharedStateMotionTest {
    @get:Rule val compose=createComposeRule()
    @Test fun changingAnIconKeepsOneCurrentAccessibleAction() {
        var selected by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            PullOutIconButton({selected=!selected}) {
                AnimatedStateIcon(if(selected) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    if(selected) "取消喜欢" else "喜欢")
            }
        } }
        compose.mainClock.autoAdvance=false
        compose.onNodeWithContentDescription("喜欢").performClick()
        compose.waitForIdle();compose.mainClock.advanceTimeBy(80)
        compose.onAllNodesWithContentDescription("取消喜欢").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("喜欢").assertCountEquals(0)
        compose.mainClock.autoAdvance=true
    }
    @Test fun progressAndSelectionRemainAccurateWithReducedMotion() {
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalReduceMotion provides true) {
            AppProgressIndicator(progress={.63f})
            AnimatedSelectionMark(true)
        } } }
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
            ProgressBarRangeInfo(.63f,0f..1f))).assertExists()
        compose.onNodeWithContentDescription("已选中").assertExists()
    }
    @Test fun loadingClockDoesNotRecomposeItsParentContent() {
        var compositions=0
        compose.mainClock.autoAdvance=false
        compose.setContent { MaterialTheme {
            SideEffect {compositions++}
            AppProgressIndicator()
        } }
        compose.waitForIdle();val before=compositions
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnIdle {assertEquals(before,compositions)}
        compose.mainClock.autoAdvance=true
    }
    @Test fun morphingPlaybackControlKeepsItsActionInReducedMotion() {
        var playing by mutableStateOf(false)
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalReduceMotion provides true) {
            PlayPauseMorphButton(playing,{playing=!playing})
        } } }
        compose.onNode(hasClickAction()).performClick()
        compose.runOnIdle {assertTrue(playing)}
    }
}
