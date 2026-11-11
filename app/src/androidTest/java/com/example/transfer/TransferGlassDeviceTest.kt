package com.example.transfer

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TransferGlassDeviceTest {
    @get:Rule val compose=createAndroidComposeRule<ComicReaderTestActivity>()
    @Test fun transferUsesTheSourceManagementGlassOnAndroid() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            com.example.ui.theme.MyApplicationTheme(darkTheme=false) {
                val backdrop=rememberThemedGlassBackdrop()
                CompositionLocalProvider(LocalGlassBackdrop provides backdrop,LocalRenderQuality provides RenderQuality.HIGH,
                    com.example.ui.feedback.LocalReduceMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        TransferScreen(TransferManager.get(context),BackupManager(context,PreferencesManager(context)))
                    }
                }
            }
        }
        compose.onNodeWithText("发送数据").assertIsDisplayed()
        compose.onNodeWithText("接收数据").assertIsDisplayed()
        compose.onNodeWithText("传输内容").assertIsDisplayed()
        compose.onRoot().captureToImage().asAndroidBitmap().let {bitmap ->
            File(context.getExternalFilesDir(null),"transfer-glass.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithText("全部 · 调整").performClick()
        val category = compose.onNodeWithText("阅读设置与预设")
        category.performScrollTo().assertIsOn().performClick()
        category.assertIsOff().performClick()
        category.assertIsOn()
        compose.onNodeWithText("收起").performScrollTo().performClick()
        compose.onNodeWithText("阅读设置与预设").assertDoesNotExist()
        compose.onNodeWithContentDescription("传输记录").performClick()
        compose.onNodeWithText("最近传输").assertIsDisplayed()
        compose.onNodeWithText("关闭").performScrollTo().performClick()
        compose.onNodeWithText("最近传输").assertDoesNotExist()
    }

    @Test fun backupPreviewCanDismissReplacementAndCancelWithoutImporting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TransferManager.get(context)
        val directory = File(context.filesDir, "transfer_sessions")
        val existing = directory.list()?.filter { it.startsWith("shared_") }?.toSet().orEmpty()
        val archive = File(File(context.cacheDir, "share_temp").apply { mkdirs() }, "wenku-empty-backup.ciallo")
        ZipOutputStream(archive.outputStream()).use { zip ->
            for ((name, content) in listOf("database.rows" to "{\"version\":1,\"schema\":12}\n", "settings.json" to "{}")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", archive)
        try {
            compose.setContent {
                com.example.ui.theme.MyApplicationTheme {
                    TransferScreen(manager, BackupManager(context, PreferencesManager(context)))
                }
            }
            compose.runOnIdle { manager.openShared(uri) }
            compose.waitUntil(10_000) { manager.state.value.phase == TransferPhase.RECEIVED }
            compose.onNodeWithText("已识别的内容").assertIsDisplayed()
            compose.onNodeWithText("整机迁移，替换所选数据").performScrollTo().performClick()
            compose.onNodeWithText("确认整机迁移").assertIsDisplayed()
            compose.onNodeWithText("取消").performScrollTo().performClick()
            compose.onNodeWithText("确认整机迁移").assertDoesNotExist()
            assertEquals(TransferPhase.RECEIVED, manager.state.value.phase)
            compose.onNodeWithText("取消恢复").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                manager.state.value.phase == TransferPhase.CANCELLED &&
                    directory.list()?.filter { it.startsWith("shared_") }?.toSet().orEmpty() == existing
            }
            compose.onNodeWithText("已识别的内容").assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                if (manager.state.value.phase == TransferPhase.RECEIVED || manager.state.value.busy) manager.cancel()
                manager.reset()
                manager.hide()
            }
            archive.delete()
        }
    }
}
