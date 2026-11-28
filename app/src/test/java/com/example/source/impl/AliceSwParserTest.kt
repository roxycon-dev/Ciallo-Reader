package com.example.source.impl

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 爱丽丝书屋 2026-11 新站解析回归：JSON 接口优先、页面 HTML 兜底，全部用真实页面
 * 快照（正文已由发布流程脱敏成占位段落）。站点改版时这些用例先红。
 */
@RunWith(RobolectricTestRunner::class)
class AliceSwParserTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw-net/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val base = "https://alicesw1.homes"

    @Test
    fun `search api json parses items`() {
        val books = AliceSwParser.searchFromApi(JSONObject(resource("api_search.json")), "alicesw", base)
        assertEquals(3, books.size)
        val first = books.first()
        assertEquals("7361", first.id)
        assertEquals("骑士王子恋爱冒险", first.title)
        assertEquals("Akuma_Maiden", first.author)
        assertEquals("已完结", first.novelInfo?.status)
        assertEquals("4.9万", first.novelInfo?.wordCount)
        assertTrue(first.novelInfo?.tags.orEmpty().contains("后宫"))
        assertNotNull(first.description)
    }

    @Test
    fun `book detail api json parses with category and bookNo`() {
        val book = AliceSwParser.detailFromApi(JSONObject(resource("api_book_7361.json")), "alicesw", base)
        assertEquals("7361", book.id)
        assertEquals("骑士王子恋爱冒险", book.title)
        assertEquals("搬运机器人", book.author)
        assertEquals("反差", book.novelInfo?.category)
        assertEquals(8, book.novelInfo?.chapterCount)
        assertEquals("已完结", book.novelInfo?.status)
        assertEquals("512134", AliceSwParser.bookNoFor("7361"))
    }

    @Test
    fun `chapters api json keeps reading order`() {
        val chapters = AliceSwParser.chaptersFromApi(JSONObject(resource("api_chapters_7361.json")), "alicesw")
        assertEquals(8, chapters.size)
        assertEquals("99187399", chapters.first().id)
        assertTrue(chapters.first().title.contains("序章"))
        assertEquals("27116718", chapters[2].id)
        assertEquals(3f, chapters[2].order)
    }

    @Test
    fun `chapter content api json splits paragraphs`() {
        val text = AliceSwParser.chapterTextFromApi(JSONObject(resource("api_chapter_99187399.json")))
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }
        assertTrue("应解析出多段正文：$paragraphs", paragraphs.size >= 2)
        assertTrue(text.contains("示例正文第1段。"))
        assertTrue(paragraphs.all { it.isNotBlank() })
    }

    @Test
    fun `categories api json maps id to name`() {
        val categories = AliceSwParser.categoriesFromApi(JSONObject(resource("api_categories.json")))
        assertTrue("分类数应充足：${categories.size}", categories.size >= 25)
        assertTrue(categories.contains(5 to "反差"))
    }

    @Test
    fun `search html page parses book feature cards`() {
        val books = AliceSwParser.booksFromHtml(resource("search_q.html"), "alicesw", base)
        assertTrue("应解析出整页卡片：${books.size}", books.size >= 15)
        val first = books.first()
        assertEquals("512134", first.id)
        assertEquals("骑士王子恋爱冒险", first.title)
        assertEquals("Akuma_Maiden", first.author)
        assertEquals("已完结", first.novelInfo?.status)
        assertEquals("4.9万", first.novelInfo?.wordCount)
        assertTrue(first.novelInfo?.tags.orEmpty().contains("中国语"))
    }

    @Test
    fun `book page html parses toc rows`() {
        val chapters = AliceSwParser.chaptersFromHtml(resource("book_512134.html"), "alicesw")
        assertEquals(8, chapters.size)
        assertEquals("99187399", chapters.first().id)
        assertTrue(chapters.first().title.contains("序章"))
    }

    @Test
    fun `read page html extracts indented paragraphs`() {
        val text = AliceSwParser.chapterTextFromHtml(resource("read_99187399.html"))
        assertTrue(text.contains("示例正文第1段。"))
        assertTrue(text.split("\n\n").size >= 2)
    }

    @Test
    fun `book id must be digits`() {
        assertTrue(AliceSwParser.isBookId("512134"))
        assertTrue(!AliceSwParser.isBookId("book/512134"))
    }
}
