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
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.components.*
import org.junit.Rule
import org.junit.Test
import java.io.File

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
    }
}
