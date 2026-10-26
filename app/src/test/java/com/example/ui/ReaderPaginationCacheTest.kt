package com.example.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReaderPaginationCacheTest {
    @Before fun reset() = ReaderPaginationCache.clear()
    @After fun cleanup() = ReaderPaginationCache.clear()
    private fun key(text: String, width: Int = 600) = PaginationKey(text, width, 800, 24f, 32f, FontFamily.Default, false, 0)

    @Test fun byteBudgetEvictsOldestButPreservesExactCachedPages() {
        val text = "文".repeat(800_000)
        val a = key(text, 600); val b = key(text, 601); val c = key(text, 602)
        val pages = listOf(text.substring(0, 400_000), text.substring(400_000))
        ReaderPaginationCache.put(a, pages)
        ReaderPaginationCache.put(b, pages)
        assertSame(pages, ReaderPaginationCache.get(a)) // a becomes most recently used
        ReaderPaginationCache.put(c, pages)
        assertNull(ReaderPaginationCache.get(b))
        assertSame(pages, ReaderPaginationCache.get(a))
        assertSame(pages, ReaderPaginationCache.get(c))
        assertTrue(ReaderPaginationCache.retainedBytes() <= ReaderPaginationCache.MAX_BYTES)
    }

    @Test fun oversizedEntryCannotFlushTheUsefulCache() {
        val small = key("正文")
        val pages = listOf("正文")
        ReaderPaginationCache.put(small, pages)
        val huge = "x".repeat(2_200_000)
        ReaderPaginationCache.put(key(huge), listOf(huge))
        assertNull(ReaderPaginationCache.get(key(huge)))
        assertSame(pages, ReaderPaginationCache.get(small))
        ReaderPaginationCache.clear()
        assertEquals(0L, ReaderPaginationCache.retainedBytes())
    }

    @Test fun replacingSameKeyDoesNotDoubleCountAndEntryLimitStillApplies() {
        val first = key("first")
        ReaderPaginationCache.put(first, listOf("first"))
        val size = ReaderPaginationCache.retainedBytes()
        repeat(10) { ReaderPaginationCache.put(first, listOf("first")) }
        assertEquals(size, ReaderPaginationCache.retainedBytes())
        repeat(8) { ReaderPaginationCache.put(key("page$it"), listOf("page$it")) }
        assertNull(ReaderPaginationCache.get(first))
    }
    @Test fun allLayoutAttributesAndDensityParticipateButDrawOnlyColorsReusePages() {
        val resolverToken = Any()
        val style = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, color = Color.Black)
        fun environment(value: TextStyle = style, density: Float = 1f, scale: Float = 1f, token: Any = resolverToken) =
            PaginationLayoutEnvironment(PaginationTextLayout(value), density, scale, token)
        val original = key("正文").copy(layoutEnvironment = environment())
        val pages = listOf("正文")
        ReaderPaginationCache.put(original, pages)
        assertSame(pages, ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(style.copy(color = Color.Red)))))
        assertNull(ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(style.copy(letterSpacing = 4.sp)))))
        assertNull(ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(style.copy(fontWeight = FontWeight.Bold)))))
        assertNull(ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(density = 2f))))
        assertNull(ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(scale = 1.5f))))
        assertNull(ReaderPaginationCache.get(original.copy(layoutEnvironment = environment(token = Any()))))
    }

    @Test fun resolverIdentityTokensRemainStableWithoutSharingAcrossResolvers() {
        val first = Any()
        val second = Any()
        assertSame(PaginationFontEnvironments.tokenFor(first), PaginationFontEnvironments.tokenFor(first))
        assertNotSame(PaginationFontEnvironments.tokenFor(first), PaginationFontEnvironments.tokenFor(second))
    }
}
