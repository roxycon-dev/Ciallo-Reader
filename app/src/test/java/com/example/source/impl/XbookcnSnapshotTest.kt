package com.example.source.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实页面快照回归（2026-10-19 抓取，离线资源）。
 * 站点改版时这里会先红，避免线上解析悄悄失效。
 */
class XbookcnSnapshotTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/xbookcn/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `real search page yields twenty books`() {
        val books = XbookcnParser.books(resource("search.html"), XbookcnSource.SOURCE_ID)
        assertEquals(20, books.size)
        val first = books.first()
        assertEquals("b3068315c735", first.id)
        assertEquals("总裁X总裁", first.title)
        assertEquals("顾盼若浅", first.author)
        assertEquals("她，顾倾，霸道总裁。", first.description)
        assertTrue(books.all { it.id.matches(Regex("[0-9a-f]{12}")) })
    }

    @Test
    fun `real detail page yields metadata and four chapters`() {
        val book = XbookcnParser.detail(resource("detail.html"), "b3068315c735", XbookcnSource.SOURCE_ID)
        assertEquals("总裁X总裁", book.title)
        assertEquals("顾盼若浅", book.author)
        assertTrue(book.description.orEmpty().contains("顾倾裹着浴巾"))
        assertEquals(4, book.novelInfo?.chapterCount)
        assertEquals("23.2 万字", book.novelInfo?.wordCount)
        assertEquals("2019-06-29", book.novelInfo?.updatedAt)
        assertEquals("都市生活", book.novelInfo?.category)
        assertTrue(book.novelInfo?.tags.orEmpty().contains("经商"))

        val chapters = XbookcnParser.chapters(resource("detail.html"), "b3068315c735")
        assertEquals(4, chapters.size)
        assertEquals(listOf("b3068315c735/1", "b3068315c735/2", "b3068315c735/3", "b3068315c735/4"),
            chapters.map { it.id })
        assertEquals(listOf("001", "010", "029", "048"), chapters.map { it.title })
    }

    @Test
    fun `real chapter page yields complete body`() {
        val text = XbookcnParser.chapterText(resource("chapter.html"))
        val paragraphs = text.split("\n\n")
        assertEquals(81, paragraphs.size)
        assertTrue(paragraphs.first().startsWith("陆思羡"))
        assertTrue(text.length > 3000)
        assertTrue(text.none { it == '\u0000' })
    }
}
