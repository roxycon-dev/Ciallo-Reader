package com.example.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import com.example.data.AppUpdateStage
import com.example.data.AppUpdateState
import com.example.data.UpdateApkAsset
import com.example.data.UpdateRelease
import com.example.data.UPDATE_REPOSITORY
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "zh-rCN-w411dp-h891dp-420dpi")
class AppUpdateDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val release = UpdateRelease("v1.2.4", "$UPDATE_REPOSITORY/releases/tag/v1.2.4", "更新说明",
        UpdateApkAsset("Ciallo-Reader-v1.2.4.apk", "$UPDATE_REPOSITORY/releases/download/v1.2.4/Ciallo-Reader-v1.2.4.apk", 20 * 1024 * 1024))
    private var action: String? = null
    private var visible by mutableStateOf(true)
    private var dialogGeneration by mutableIntStateOf(0)
    private fun show(stage: AppUpdateStage) {
        compose.setContent { MaterialTheme {
            if (visible) key(dialogGeneration) {
            AppUpdateDialog(AppUpdateState(release, stage, visible = true, downloaded = 10 * 1024 * 1024, total = 20 * 1024 * 1024),
                onDismiss = { visible = false; action = "dismiss" }, onDownload = { action = "download" }, onCancel = { action = "cancel" },
                onInstall = { action = "install" }, onPermission = { action = "permission" }, onExport = { action = "export" },
                onBrowser = { action = "browser" }, onReleasePage = { action = "release" })
            }
        } }
    }

    private fun clickAndAwait(label: String, expected: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitUntil(5_000) { action == expected }
        compose.waitForIdle()
        assertEquals(expected, action)
    }

    private fun reopen() {
        compose.runOnIdle { action = null; dialogGeneration++; visible = true }
        compose.waitForIdle()
    }

    @Test fun newerReleaseRequiresConfirmationBeforeDownload() {
        show(AppUpdateStage.AVAILABLE)
        compose.onNodeWithText("Ciallo Reader 1.2.4").assertExists()
        compose.onNodeWithText("更新说明").assertExists()
        assertEquals(null, action)
        clickAndAwait("下载并安装", "download")
    }

    @Test fun downloadingShowsProgressAndCanBeCancelled() {
        show(AppUpdateStage.DOWNLOADING)
        compose.onNodeWithTag("update_progress").assertExists()
        compose.onNodeWithText("50% · 10.0 MB / 20.0 MB").assertExists()
        snapshot("download-progress")
        clickAndAwait("取消下载", "cancel")
    }

    @Test fun permissionDeniedOffersAuthorizationExportAndBrowserWithoutLoop() {
        show(AppUpdateStage.NEED_PERMISSION)
        assertEquals(null, action)
        snapshot("permission-fallback")
        clickAndAwait("允许安装", "permission")
        reopen()
        clickAndAwait("导出安装包", "export")
        reopen()
        clickAndAwait("浏览器下载", "browser")
    }

    @Test fun readyPackageCanBeInstalledWithoutRedownloading() {
        show(AppUpdateStage.READY)
        clickAndAwait("安装", "install")
    }

    private fun snapshot(name: String) {
        val root = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "gradlew.bat").exists() }
        val file = File(root, "artifacts/update-flow-2026-10-05/screenshots/$name.png")
        file.parentFile!!.mkdirs()
        compose.onNode(hasAnyDescendant(hasText("Ciallo Reader 1.2.4")).and(isRoot())).captureRoboImage(file.absolutePath)
    }
}
