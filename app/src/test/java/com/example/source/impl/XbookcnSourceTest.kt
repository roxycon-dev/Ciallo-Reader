package com.example.source.impl

import com.example.source.SourceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 解析层回归：全部用离线 HTML 片段，不发起网络请求。 */
class XbookcnSourceTest {

    private val searchHtml = """
        <html><body>
        <section id="search-books">
          <ul>
            <li>
              <div>
                <h3><a href="/book/b3068315c735-%E6%80%BB%E8%A3%81X%E6%80%BB%E8%A3%81">总<mark>裁</mark>X总裁</a></h3>
                <p class="mt-0.5 truncate text-xs">顾盼若浅</p>
                <p class="mt-1 text-[13px]">她，顾倾，霸道总裁。</p>
                <div><span>4 章 · 23.2 万字</span></div>
              </div>
            </li>
            <li>
              <div>
                <h3><a href="/book/5874be17d745-%E6%80%BB%E8%A3%81%E5%AD%A6">总裁学</a></h3>
                <p class="mt-0.5 truncate text-xs">某作者</p>
              </div>
            </li>
          </ul>
        </section>
        <section id="search-chapters">
          <ul><li><a href="/book/01ce6c28fc8d-%E6%80%BB%E8%A3%81%E6%83%B3%E6%88%91/1">第一章</a></li></ul>
        </section>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body><main>
          <nav>面包屑</nav>
          <header class="mt-6">
            <div>
              <h1 class="font-serif">总裁X总裁</h1>
              <p class="mt-2"><a href="/author/%E9%A1%BE">顾盼若浅</a> · 4 章 · 23.2 万字 · 2019-06-29 更新</p>
              <p class="mt-2.5"><a href="/novels?cat=%E9%83%BD%E5%B8%82">都市生活</a></p>
              <p class="mt-2"><span>标签</span><a href="/novels?tag=%E7%BB%8F%E5%95%86">经商</a><a href="/novels?tag=%E7%8B%97%E8%A1%80">狗血</a></p>
              <p class="mt-3 text-sm">顾倾裹着浴巾，头发湿漉漉往下滴着水。她拿起床头柜上的打火机。</p>
            </div>
          </header>
          <section class="mt-10">
            <h2>目录<span>共 4 章</span></h2>
            <div><ol>
              <li><a href="/book/b3068315c735-%E6%80%BB%E8%A3%81X%E6%80%BB%E8%A3%81/1"><span>1</span><span class="truncate">001</span></a></li>
              <li><a href="/book/b3068315c735-%E6%80%BB%E8%A3%81X%E6%80%BB%E8%A3%81/2"><span>2</span><span class="truncate">010</span></a></li>
            </ol></div>
          </section>
          <section class="mt-12">
            <h2>相关推荐</h2>
            <a href="/book/ee886a4fb420-%E8%88%8C%E5%B0%96/3">别的书</a>
          </section>
        </main></body></html>
    """.trimIndent()

    private val chapterHtml = """
        <html><body><main>
          <header><h1>坏话</h1><p>第 100 / 267 章 · 3,842 字</p></header>
          <article class="reading-text text-stone-800" style="font-size:19px">
            <p>第一段正文。</p>
            <p>第二段正文。</p>
            <p>　</p>
          </article>
        </main></body></html>
    """.trimIndent()

    @Test
    fun `search only reads book section and keeps author and intro`() {
        val books = XbookcnParser.books(searchHtml, XbookcnSource.SOURCE_ID)
        assertEquals(2, books.size)
        assertEquals("b3068315c735", books[0].id)
        assertEquals("总裁X总裁", books[0].title)
        assertEquals("顾盼若浅", books[0].author)
        assertEquals("她，顾倾，霸道总裁。", books[0].description)
        assertEquals("xbookcn", books[0].sourceId)
        // 章节搜索结果不能混进书籍列表
        assertTrue(books.none { it.id == "01ce6c28fc8d" })
    }

    @Test
    fun `detail reads title author intro counts and tags`() {
        val book = XbookcnParser.detail(detailHtml, "b3068315c735", XbookcnSource.SOURCE_ID)
        assertEquals("总裁X总裁", book.title)
        assertEquals("顾盼若浅", book.author)
        assertEquals("顾倾裹着浴巾，头发湿漉漉往下滴着水。她拿起床头柜上的打火机。", book.description)
        assertEquals(4, book.novelInfo?.chapterCount)
        assertEquals("23.2 万字", book.novelInfo?.wordCount)
        assertEquals("2019-06-29", book.novelInfo?.updatedAt)
        assertEquals(listOf("经商", "狗血"), book.novelInfo?.tags)
        assertEquals("都市生活", book.novelInfo?.category)
        assertEquals("010", book.novelInfo?.latestChapter)
    }

    @Test
    fun `chapters ignore recommendations and normalize ids`() {
        val chapters = XbookcnParser.chapters(detailHtml, "b3068315c735")
        assertEquals(2, chapters.size)
        assertEquals("b3068315c735/1", chapters[0].id)
        assertEquals("001", chapters[0].title)
        assertEquals(1f, chapters[0].order)
        assertEquals(2f, chapters[1].order)
    }

    @Test
    fun `chapter text joins paragraphs and drops blanks`() {
        assertEquals("第一段正文。\n\n第二段正文。", XbookcnParser.chapterText(chapterHtml))
    }

    @Test
    fun `empty or paid chapter is rejected instead of imported`() {
        assertThrows(SourceException.ParseError::class.java) {
            XbookcnParser.chapterText("<html><body><article class='reading-text'></article></body></html>")
        }
        assertThrows(SourceException.ParseError::class.java) {
            XbookcnParser.chapterText("<html><body><article class='reading-text'><p>购买本章后可阅读</p></article></body></html>")
        }
    }

    @Test
    fun `book and chapter ids reject path injection`() {
        assertThrows(SourceException.ParseError::class.java) {
            XbookcnSource.requireBookId("../etc/passwd")
        }
        assertEquals("b3068315c735" to 12, XbookcnSource.parseChapterId("b3068315c735/12"))
        assertThrows(SourceException.ParseError::class.java) {
            XbookcnSource.parseChapterId("b3068315c735/../../x")
        }
        assertThrows(SourceException.ParseError::class.java) {
            XbookcnSource.parseChapterId("b3068315c735/0")
        }
    }
}
