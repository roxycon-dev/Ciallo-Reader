package com.example.transfer

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.LocalRenderQuality
import com.example.ui.components.RenderQuality
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="w390dp-h844dp-420dpi")
class TransferUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComicReaderTestActivity>()
    private lateinit var context:Context
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext()
        for(key in listOf(Settings.Global.ANIMATOR_DURATION_SCALE,Settings.Global.TRANSITION_ANIMATION_SCALE,Settings.Global.WINDOW_ANIMATION_SCALE))Settings.Global.putFloat(context.contentResolver,key,0f)
        context.getSharedPreferences("device_transfer",0).edit().clear().commit()
    }
    @Test fun landingExplainsSendingAndReceivingAndRendersWithoutClipping() {
        val prefs=PreferencesManager(context)
        compose.setContent {com.example.ui.theme.MyApplicationTheme(darkTheme=false) {CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL) {
            TransferScreen(TransferManager.get(context),BackupManager(context,prefs))
        }}}
        compose.onNodeWithText("设备互传").assertIsDisplayed()
        compose.onNodeWithText("发送数据").assertIsDisplayed();compose.onNodeWithText("接收数据").assertIsDisplayed()
        compose.onRoot().captureRoboImage(File("../artifacts/device-transfer/landing.png").absolutePath)
        compose.onNodeWithText("全部 · 调整").performClick()
        compose.onNodeWithText("书籍与离线原文件").assertExists()
        compose.onNodeWithText("把阅读记忆，轻轻带走").assertDoesNotExist()
        compose.onNodeWithText("让两台设备，靠近一点").assertDoesNotExist()
    }
    @Test fun discoveryTransportAndCompletionArtHaveDistinctHonestStates() {
        compose.setContent {com.example.ui.theme.MyApplicationTheme(darkTheme=false) {Surface {Column {
            TransferArtwork(TransferSessionState(TransferPhase.DISCOVERING))
            TransferArtwork(TransferSessionState(TransferPhase.TRANSFERRING,progress=TransferProgress(TransferPhase.TRANSFERRING,1024,2048)))
            TransferArtwork(TransferSessionState(TransferPhase.COMPLETED))
        }}}}
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onRoot().captureRoboImage(File("../artifacts/device-transfer/discovery-transfer-completion.png").absolutePath)
    }
    @Test fun settingsEntryUsesTheWholeCardWithoutAnExtraOpenButton() {
        val prefs=PreferencesManager(context)
        val manager=TransferManager.get(context)
        compose.setContent {com.example.ui.theme.MyApplicationTheme(darkTheme=false) {
            CompositionLocalProvider(LocalRenderQuality provides RenderQuality.MINIMAL) {
                com.example.ui.SettingsTabScreen(prefs,BackupManager(context,prefs),emptyList(),{})
            }
        }}
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag("device_transfer_entry"))
        compose.onNodeWithText("设备互传与备份").assertIsDisplayed()
        compose.onNodeWithText("打开设备互传").assertDoesNotExist()
        compose.onRoot().captureRoboImage(File("../artifacts/device-transfer/settings-transfer-entry.png").absolutePath)
        compose.onNodeWithTag("device_transfer_entry").performClick()
        org.junit.Assert.assertTrue(manager.openPage.value)
        manager.hide()
    }
    @Test fun captureRealMotionFramesForReview() {
        for(key in listOf(Settings.Global.ANIMATOR_DURATION_SCALE,Settings.Global.TRANSITION_ANIMATION_SCALE,Settings.Global.WINDOW_ANIMATION_SCALE))Settings.Global.putFloat(context.contentResolver,key,1f)
        compose.mainClock.autoAdvance=false
        compose.setContent {com.example.ui.theme.MyApplicationTheme(darkTheme=false) {Surface {
            TransferArtwork(TransferSessionState(TransferPhase.TRANSFERRING,progress=TransferProgress(TransferPhase.TRANSFERRING,1024,2048)))
        }}}
        compose.mainClock.advanceTimeBy(500)
        for(index in 0 until 16) {
            compose.mainClock.advanceTimeBy(160)
            compose.onNodeWithTag("transfer_animation").captureRoboImage(File("../artifacts/device-transfer/motion/frame-${index.toString().padStart(2,'0')}.png").absolutePath)
        }
    }
}
