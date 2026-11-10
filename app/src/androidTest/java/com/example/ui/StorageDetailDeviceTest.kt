package com.example.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.comic.ComicReaderTestActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StorageDetailDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComicReaderTestActivity>()

    @Test fun cancelKeepsSelectionAndOnlyEligibleFilesAreDeleted() {
        val root = java.nio.file.Files.createTempDirectory(compose.activity.cacheDir.toPath(), "storage-detail").toFile()
        val share = File(root, "share_temp").apply { mkdirs() }
        val recent = File(share, "active-share.cbz").apply { writeText("still in use") }
        val old = File(share, "expired-share.cbz").apply {
            writeText("expired")
            check(setLastModified(System.currentTimeMillis() - 20 * 60_000L))
        }
        try {
            compose.setContent {
                MaterialTheme {
                    StorageDetailDialog("temp", "测试存储明细", listOf(share), "仅清理测试文件。",
                        onDismiss = {}, onChanged = {})
                }
            }
            compose.waitUntil(5000) { compose.onAllNodesWithText("全选").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("取消全选").assertIsDisplayed().performClick()
            compose.onNodeWithText("删除所选").assertIsNotEnabled()
            compose.onNodeWithText("全选").performClick()
            compose.onNodeWithText("删除所选").performClick()
            compose.onNodeWithText("确认清理 1 项").assertIsDisplayed()
            compose.onNodeWithText("取消").performClick()
            compose.onNodeWithText("取消全选").assertIsDisplayed()
            assertTrue(old.exists())
            compose.onNodeWithText("删除所选").performClick()
            compose.onNodeWithText("清理").performClick()
            compose.waitUntil(5000) { !old.exists() }
            assertFalse(old.exists())
            assertTrue(recent.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
