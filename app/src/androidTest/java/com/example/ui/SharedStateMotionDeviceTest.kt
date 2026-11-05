package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import com.example.ui.help.FaqExpandableItem
import com.example.ui.theme.MyApplicationTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedStateMotionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun sharedControlsKeepTheirActionsAndThemeAcrossTransitions() {
        val drawing = androidx.test.platform.graphics.HardwareRendererCompat.isDrawingEnabled()
        androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(true)
        var favorite by mutableStateOf(false)
        var checked by mutableStateOf(false)
        var selected by mutableStateOf(false)
        var playing by mutableStateOf(false)
        var dark by mutableStateOf(false)
        try {
            compose.mainClock.autoAdvance = false
            compose.setContent { MyApplicationTheme(darkTheme = dark) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()
                    .padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("统一状态反馈", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        PullOutIconButton({favorite=!favorite}) {
                            AnimatedStateIcon(if(favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                if(favorite) "取消喜欢" else "喜欢", tint = MaterialTheme.colorScheme.primary)
                        }
                        PlayPauseMorphButton(playing,{playing=!playing})
                        AppProgressIndicator(Modifier.size(28.dp))
                        AppProgressIndicator(Modifier.size(28.dp),progress={.63f})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        AppSwitch(checked,{checked=it},Modifier.testTag("shared-switch"))
                        PullOutButton({selected=!selected}) {
                            AnimatedSelectionMark(selected)
                            Text("章节选择")
                        }
                    }
                    AppActionButton("统一操作",{},fullWidth=true)
                    AppActionButton("处理中",{},loading=true,fullWidth=true)
                    FaqExpandableItem("展开与收起","保留毛玻璃和抽纸，统一状态图标、反馈节奏和加载指示。")
                }
            } }
            fun screenshot(name: String) {
                val bitmap=compose.onAllNodes(isRoot()).onLast().captureToImage().asAndroidBitmap()
                val context=InstrumentationRegistry.getInstrumentation().targetContext
                val dir=File(context.filesDir,"shared-motion").apply {mkdirs()}
                File(dir,"$name.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
            compose.mainClock.advanceTimeBy(400)
            screenshot("light-rest")
            compose.onNodeWithContentDescription("喜欢").performClick()
            compose.onNodeWithContentDescription("播放").performClick()
            compose.onNodeWithTag("shared-switch").performClick()
            compose.onNodeWithText("章节选择").performClick()
            compose.onNodeWithText("展开与收起").performClick()
            compose.waitForIdle();compose.mainClock.advanceTimeBy(1600)
            compose.runOnIdle {assertTrue(favorite && checked && selected && playing)}
            compose.onNodeWithContentDescription("取消喜欢").assertExists()
            compose.onNodeWithContentDescription("暂停").assertExists()
            screenshot("light-selected")
            compose.runOnIdle {dark=true};compose.waitForIdle();compose.mainClock.advanceTimeBy(600)
            screenshot("dark-selected")
        } finally {
            compose.mainClock.autoAdvance=true
            androidx.test.platform.graphics.HardwareRendererCompat.setDrawingEnabled(drawing)
        }
    }
}
