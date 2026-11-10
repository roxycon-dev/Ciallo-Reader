package com.example.ui.wenku

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WenkuPlaneTabSwitcherTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rapidReversalsRetainBothPanesAndOnlyTheSelectedPaneStaysStarted() {
        val target = mutableStateOf(0)
        val visible = mutableMapOf<Int, Boolean>()
        val lifecycles = mutableMapOf<Int, Lifecycle>()
        compose.setContent {
            MyApplicationTheme {
                val holder = rememberSaveableStateHolder()
                WenkuPlaneTabSwitcher(target.value, Modifier.fillMaxSize(), { from, to -> to - from }) { tab ->
                    holder.SaveableStateProvider(tab) {
                        val shown = LocalWenkuPaneVisible.current
                        val lifecycle = LocalLifecycleOwner.current.lifecycle
                        SideEffect {
                            visible[tab] = shown
                            lifecycles[tab] = lifecycle
                        }
                        Text("Pane $tab")
                    }
                }
            }
        }

        compose.mainClock.autoAdvance = false
        fun select(tab: Int, elapsedMillis: Long) {
            compose.runOnIdle { target.value = tab }
            compose.mainClock.advanceTimeBy(elapsedMillis)
            compose.waitForIdle()
        }
        select(1, 144)
        repeat(8) { index ->
            select(index % 2, 64)
            compose.runOnIdle {
                assertEquals(mapOf(0 to true, 1 to true), visible)
                assertEquals(1, lifecycles.values.count { it.currentState.isAtLeast(Lifecycle.State.STARTED) })
            }
        }
        // A third target can arrive before the reversal completes.
        select(2, 640)
        compose.onNodeWithText("Pane 2").assertExists()
        compose.runOnIdle {
            assertEquals(mapOf(0 to false, 1 to false, 2 to true), visible)
            assertTrue(lifecycles.getValue(2).currentState.isAtLeast(Lifecycle.State.STARTED))
            assertEquals(Lifecycle.State.CREATED, lifecycles.getValue(0).currentState)
            assertEquals(Lifecycle.State.CREATED, lifecycles.getValue(1).currentState)
        }
        compose.mainClock.autoAdvance = true
    }
}
