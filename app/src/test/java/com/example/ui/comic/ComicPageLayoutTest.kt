package com.example.ui.comic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面布局引擎测试：单页/双页配对、宽页拆分与方向顺序、临时合页、首页单独、映射表。
 */
class ComicPageLayoutTest {

    private fun pages(n: Int): List<ComicPageRef> =
        (0 until n).map { ComicPageRef.Local("p$it", "/mock/$it.jpg") }

    private fun sizes(n: Int, aspect: (Int) -> Float): Map<String, SizeI> =
        (0 until n).associate { i -> "p$i" to SizeI((aspect(i) * 1000).toInt(), 1000) }

    @Test
    fun `single mode - every page is its own spread`() {
        val layout = ComicPageLayout.build(pages(7), emptyMap(), ComicReaderConfig(mode = ComicMode.SINGLE))
        assertEquals(7, layout.spreadCount)
        layout.spreads.forEachIndexed { i, s -> assertEquals(i, s.slots.first().rawIndex) }
    }

    @Test
    fun `double mode - pages pair into spreads`() {
        val layout = ComicPageLayout.build(pages(6), emptyMap(), ComicReaderConfig(mode = ComicMode.DOUBLE))
        assertEquals(3, layout.spreadCount)
        assertTrue(layout.spreads.all { it.isDouble })
        assertEquals(listOf(0, 1), layout.spreads[0].slots.map { it.rawIndex })
        assertEquals(listOf(4, 5), layout.spreads[2].slots.map { it.rawIndex })
    }

    @Test
    fun `double mode - odd tail becomes single spread`() {
        val layout = ComicPageLayout.build(pages(5), emptyMap(), ComicReaderConfig(mode = ComicMode.DOUBLE))
        assertEquals(3, layout.spreadCount)
        assertFalse(layout.spreads[2].isDouble)
        assertEquals(4, layout.spreads[2].firstRawIndex)
    }

    @Test
    fun `double mode - first page alone when enabled`() {
        val layout = ComicPageLayout.build(
            pages(5), emptyMap(),
            ComicReaderConfig(mode = ComicMode.DOUBLE, doubleFirstAlone = true)
        )
        assertEquals(3, layout.spreadCount)
        assertFalse(layout.spreads[0].isDouble)
        assertTrue(layout.spreads[1].isDouble)
    }

    @Test
    fun `wide split RTL - right half first`() {
        val sizes = mapOf("p0" to SizeI(2000, 1000))
        val layout = ComicPageLayout.build(
            pages(1), sizes,
            ComicReaderConfig(mode = ComicMode.SINGLE, direction = ComicDirection.RTL, splitWide = true)
        )
        assertEquals(2, layout.spreadCount)
        assertEquals(ComicSplitHalf.RIGHT, layout.spreads[0].slots[0].half)
        assertEquals(ComicSplitHalf.LEFT, layout.spreads[1].slots[0].half)
    }

    @Test
    fun `wide split LTR - left half first`() {
        val sizes = mapOf("p0" to SizeI(2000, 1000))
        val layout = ComicPageLayout.build(
            pages(1), sizes,
            ComicReaderConfig(mode = ComicMode.SINGLE, direction = ComicDirection.LTR, splitWide = true)
        )
        assertEquals(2, layout.spreadCount)
        assertEquals(ComicSplitHalf.LEFT, layout.spreads[0].slots[0].half)
        assertEquals(ComicSplitHalf.RIGHT, layout.spreads[1].slots[0].half)
    }

    @Test
    fun `wide split - splitReverse flips order`() {
        val sizes = mapOf("p0" to SizeI(2000, 1000))
        val layout = ComicPageLayout.build(
            pages(1), sizes,
            ComicReaderConfig(mode = ComicMode.SINGLE, direction = ComicDirection.RTL, splitWide = true, splitReverse = true)
        )
        assertEquals(ComicSplitHalf.LEFT, layout.spreads[0].slots[0].half)
    }

    @Test
    fun `wide page without split occupies own spread in double mode`() {
        val sizes = mapOf("p1" to SizeI(2000, 1000))
        val layout = ComicPageLayout.build(
            pages(3), sizes,
            ComicReaderConfig(mode = ComicMode.DOUBLE, splitWide = false)
        )
        // p0+p1 宽页独占 → p0 单独, p1 单独, p2 单独
        assertEquals(3, layout.spreadCount)
        assertFalse(layout.spreads[0].isDouble)
        assertFalse(layout.spreads[1].isDouble)
    }

    @Test
    fun `merge anchors combine two pages into one spread`() {
        val state = ComicBookState(mergeAnchors = setOf(1))
        val layout = ComicPageLayout.build(
            pages(4), emptyMap(),
            ComicReaderConfig(mode = ComicMode.SINGLE), state
        )
        assertEquals(3, layout.spreadCount)
        // anchor=1 → p0 单独、p1+p2 合并、p3 单独
        assertFalse(layout.spreads[0].isDouble)
        assertTrue(layout.spreads[1].isDouble)
        assertEquals(listOf(1, 2), layout.spreads[1].slots.map { it.rawIndex })
    }

    @Test
    fun `merge anchor on last page is ignored`() {
        val state = ComicBookState(mergeAnchors = setOf(3))
        val layout = ComicPageLayout.build(
            pages(4), emptyMap(),
            ComicReaderConfig(mode = ComicMode.SINGLE), state
        )
        assertEquals(4, layout.spreadCount)
    }

    @Test
    fun `rawToSpread maps every raw page`() {
        val layout = ComicPageLayout.build(
            pages(6), emptyMap(),
            ComicReaderConfig(mode = ComicMode.DOUBLE)
        )
        assertEquals(0, layout.spreadOfRawPage(0))
        assertEquals(0, layout.spreadOfRawPage(1))
        assertEquals(2, layout.spreadOfRawPage(4))
        assertEquals(2, layout.spreadOfRawPage(5))
    }

    @Test
    fun `split pages in double mode pair naturally`() {
        // p0 是宽页（拆成两半），后续为普通页
        val sizes = mapOf("p0" to SizeI(2000, 1000))
        val layout = ComicPageLayout.build(
            pages(3), sizes,
            ComicReaderConfig(mode = ComicMode.DOUBLE, direction = ComicDirection.RTL, splitWide = true)
        )
        // p0 右半 + 左半 成一组，p1+p2 成一组
        assertEquals(2, layout.spreadCount)
        assertTrue(layout.spreads[0].isDouble)
        assertEquals(0, layout.spreads[0].slots[0].rawIndex)
        assertTrue(layout.spreads[1].isDouble)
    }

    @Test
    fun `empty pages produce empty layout`() {
        val layout = ComicPageLayout.build(emptyList(), emptyMap(), ComicReaderConfig())
        assertEquals(0, layout.spreadCount)
        assertNotNull(layout)
    }

    @Test
    fun `narrow page never splits even when splitWide on`() {
        val sizes = mapOf("p0" to SizeI(700, 1000)) // aspect 0.7
        val layout = ComicPageLayout.build(
            pages(1), sizes,
            ComicReaderConfig(splitWide = true)
        )
        assertEquals(1, layout.spreadCount)
        assertEquals(ComicSplitHalf.FULL, layout.spreads[0].slots[0].half)
    }
}
