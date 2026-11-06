package com.example.source.impl

import com.example.source.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 爱丽丝书屋解析回归（2026-11-06 抓取的真实页面快照，正文文字已替换为中性占位）。
 *
 * 站点改版时这里会先红，避免线上解析悄悄失效：
 * - 搜索列表靠 `div.list-group div.list-group-item`；
 * - 详情靠 `div.novel_title` / `div.novel_info p` / `div.jianjie` / `div.tags_list`；
 * - 目录靠 `ul.mulu_list li a`（顺序即阅读顺序）；
 * - 正文靠 `div.read-content > p`。
 */
class AliceSwParserTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `search page yields ten books with author and tags`() {
        val books = AliceSwParser.books(resource("search.html"), AliceSwSource.SOURCE_ID)
        assertEquals(10, books.size)

        val first = books.first()
        assertEquals("35647", first.id)
        assertEquals("我是美母骑士(美母骑士爆改无绿版)", first.title)
        assertEquals("许仙曰过蛇", first.author)
        assertEquals("這才是许仙曰过蛇真作品", first.description)
        assertEquals("已完结", first.novelInfo?.status)
        assertEquals("12.12万", first.novelInfo?.wordCount)
        assertEquals("2025-06-08", first.novelInfo?.updatedAt)
        assertEquals(listOf("乱伦", "人妻", "母子", "小马拉大车"), first.novelInfo?.tags)
        assertTrue(books.all { AliceSwParser.isBookId(it.id) })
        assertTrue(books.all { it.sourceId == AliceSwSource.SOURCE_ID })
        assertEquals(books.size, books.distinctBy { it.id }.size)
    }

    @Test
    fun `detail page yields metadata, cover and synopsis`() {
        val book = AliceSwParser.detail(resource("detail.html"), "35647", AliceSwSource.SOURCE_ID)
        assertEquals("我是美母骑士(美母骑士爆改无绿版)", book.title)
        assertEquals("许仙曰过蛇", book.author)
        assertEquals("乱伦", book.novelInfo?.category)
        assertEquals("已完结", book.novelInfo?.status)
        assertEquals(11, book.novelInfo?.chapterCount)
        assertEquals("12.12万", book.novelInfo?.wordCount)
        assertEquals("第九章 实践班与进修班", book.novelInfo?.latestChapter)
        assertEquals("2025-06-07", book.novelInfo?.updatedAt)
        assertEquals("這才是许仙曰过蛇真作品", book.novelInfo?.synopsis)
        assertEquals(listOf("乱伦", "人妻", "母子", "小马拉大车"), book.novelInfo?.tags)
        assertTrue(book.cover.orEmpty().startsWith("https://img.321cdn.com/"))
        assertTrue(book.novelInfo?.notice.orEmpty().isNotBlank())
    }

    @Test
    fun `table of contents keeps site order and uses shelf book key`() {
        val chapters = AliceSwParser.chapters(resource("toc.html"), AliceSwSource.SOURCE_ID)
        assertEquals(11, chapters.size)
        assertEquals("序章", chapters.first().title)
        assertEquals("第九章 实践班与进修班", chapters.last().title)
        assertEquals(
            listOf(
                "37187/58d8b3fbe6791",
                "37187/6bb6d5bbfc8fc",
                "37187/202c8902a3c78"
            ),
            chapters.take(3).map { it.id }
        )
        assertEquals(chapters.map { it.order }, chapters.indices.map { (it + 1).toFloat() })
        assertTrue(chapters.all { AliceSwParser.parseChapterId(it.id).first == "37187" })
    }

    @Test
    fun `chapter page yields every paragraph as plain text`() {
        val text = AliceSwParser.chapterText(resource("chapter.html"))
        val paragraphs = text.split("\n\n")
        assertEquals(158, paragraphs.size)
        assertEquals("示例正文第1段。", paragraphs.first())
        assertEquals("示例正文第158段。", paragraphs.last())
        assertTrue(paragraphs.none { it.isBlank() })
        assertTrue(text.none { it == '\u0000' })
    }

    @Test
    fun `missing chapter page reports book not found instead of empty text`() {
        try {
            AliceSwParser.chapterText(resource("missing_chapter.html"))
            fail("下架章节页应该抛出 BookNotFound")
        } catch (expected: SourceException.BookNotFound) {
            // 站点返回“章节不存在或获取失败”提示页，必须当作缺书而不是空正文
        }
    }

    @Test
    fun `invalid ids are rejected before any request`() {
        assertTrue(AliceSwParser.isBookId("35647"))
        assertTrue(!AliceSwParser.isBookId("../etc/passwd"))
        assertTrue(!AliceSwParser.isBookId("35647.html"))

        val (bookKey, hash) = AliceSwParser.parseChapterId("37187/972abf63ec2c2")
        assertEquals("37187", bookKey)
        assertEquals("972abf63ec2c2", hash)
        try {
            AliceSwParser.parseChapterId("https://evil.example/book/1/2.html")
            fail("站外地址不能被当成章节标识")
        } catch (expected: SourceException.ParseError) {
            // 只接受 `书库id/章节hash`
        }
    }

    @Test
    fun `page without title reports parse error`() {
        try {
            AliceSwParser.detail("<html><body>暂无数据</body></html>", "1", AliceSwSource.SOURCE_ID)
            fail("没有标题应该抛 ParseError")
        } catch (expected: SourceException.ParseError) {
            // 页面结构变化必须报解析错误，不能编造一本空书
        }
    }
}
