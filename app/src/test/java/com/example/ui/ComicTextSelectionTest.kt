package com.example.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.example.source.SearchBook
import com.example.source.ComicInfo
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "zh-rCN-w360dp-h780dp-420dpi", shadows = [ComicTextSelectionTest.MagnifierShadow::class])
class ComicTextSelectionTest {
    // Robolectric has no Surface for the platform magnifier. Keep selection and
    // toolbar callbacks real while omitting only this unsupported popup rendering.
    @Implements(android.widget.Magnifier::class)
    class MagnifierShadow {
        @Implementation fun show(x: Float, y: Float) {}
        @Implementation fun show(x: Float, y: Float, windowX: Float, windowY: Float) {}
        @Implementation fun dismiss() {}
        @Implementation fun update() {}
    }

    @get:Rule val compose = createComposeRule()
    private class Toolbar : TextToolbar {
        override var status = TextToolbarStatus.Hidden
        var copy: (() -> Unit)? = null
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            status = TextToolbarStatus.Shown; copy = onCopyRequested
        }
        override fun hide() { status = TextToolbarStatus.Hidden }
    }

    @Test fun ordinaryTapSearchesThePlainTagOnce() {
        val searches = mutableListOf<String>()
        compose.setContent { MyApplicationTheme {
            ComicMetadataBlock(SearchBook("id", "source", "漫画", "", comicInfo = ComicInfo(tags = listOf("属性：眼镜"))), searches::add)
        } }
        compose.onNodeWithText("属性：眼镜").performTouchInput { click() }
        compose.runOnIdle { assertEquals(listOf("眼镜"), searches) }
        compose.onNodeWithText("聚合搜索").assertDoesNotExist()
    }

    @Test fun longPressSelectsTagForNativeCopyWithoutAlsoSearching() {
        val toolbar = Toolbar()
        val searches = mutableListOf<String>()
        var copied = ""
        lateinit var clipboard: androidx.compose.ui.platform.ClipboardManager
        compose.setContent { MyApplicationTheme {
            clipboard = LocalClipboardManager.current
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                ComicMetadataBlock(SearchBook("id", "source", "漫画", "", comicInfo = ComicInfo(tags = listOf("属性：眼镜"))), searches::add)
            }
        } }
        compose.onNodeWithText("属性：眼镜").performTouchInput { longClick() }
        compose.runOnIdle {
            assertTrue("Long press must not search", searches.isEmpty())
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
            assertNotNull(toolbar.copy)
            toolbar.copy!!.invoke()
            copied = clipboard.getText()?.text.orEmpty()
        }
        assertTrue("Native copy must contain selected text", copied.isNotBlank() && "属性：眼镜".contains(copied))
        compose.onNodeWithText("聚合搜索").assertDoesNotExist()
    }

    @Test fun titleAuthorAndNumberShareTapAndNativeSelectionGestures() {
        val toolbar = Toolbar()
        val searches = mutableListOf<String>()
        compose.setContent { MyApplicationTheme {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SelectionContainer { Column {
                    listOf("作品名称", "作者姓名", "123456").forEach { value ->
                        Text(value, Modifier.comicSearchActions("名称", value, searches::add))
                    }
                } }
            }
        } }
        compose.onNodeWithText("作品名称").performTouchInput { click() }
        compose.onNodeWithText("作者姓名").performTouchInput { click() }
        compose.onNodeWithText("123456").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf("作品名称", "作者姓名"), searches)
            assertEquals(TextToolbarStatus.Shown, toolbar.status)
        }
    }
}
