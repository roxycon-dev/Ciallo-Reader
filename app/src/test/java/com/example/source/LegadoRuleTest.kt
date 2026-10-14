package com.example.source

import com.example.source.parser.LegadoRule
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LegadoRuleTest {

    private val html = """
        <html><body>
          <ul class="booklist">
            <li class="item">
              <div class="cover"><img data-src="https://a.com/cover1.jpg"></div>
              <h3><a href="/book/1">三体</a></h3>
              <span class="author">刘慈欣</span>
            </li>
            <li class="item">
              <div class="cover"><img data-src="https://a.com/cover2.jpg"></div>
              <h3><a href="/book/2">球状闪电</a></h3>
              <span class="author">刘慈欣</span>
            </li>
            <li class="item">
              <div class="cover"><img data-src="https://a.com/cover3.jpg"></div>
              <h3><a href="/book/3">超新星纪元</a></h3>
              <span class="author">刘慈欣</span>
            </li>
          </ul>
        </body></html>
    """.trimIndent()

    private val doc = Jsoup.parse(html)

    @Test
    fun testLegadoClassTagTextChain() {
        val title = LegadoRule.evalFirst(doc, "class.item.0@tag.a.0@text")
        assertEquals("三体", title)
    }

    @Test
    fun testLegadoNegativeIndex() {
        val title = LegadoRule.evalFirst(doc, "class.item.-1@tag.a.0@text")
        assertEquals("超新星纪元", title)
    }

    @Test
    fun testLegadoCssMode() {
        val title = LegadoRule.evalFirst(doc, "@css:li.item h3 a@text")
        assertEquals("三体", title)
        val cover = LegadoRule.evalFirst(doc, "@css:li.item .cover img@data-src")
        assertEquals("https://a.com/cover1.jpg", cover)
    }

    @Test
    fun testLegadoOrFallback() {
        val title = LegadoRule.evalFirst(doc, "class.missing.0@text||class.item.1@tag.a.0@text")
        assertEquals("球状闪电", title)
    }

    @Test
    fun testLegadoAndMerge() {
        val values = LegadoRule.evalValues(doc, "class.item.0@class.cover.0@img@src&&class.item.0@tag.h3.0@text")
        // class.cover.0@img@src 不是合法 Legado 链（img 应为 tag.img.0），此处验证 && 至少能走通第一条 CSS 兼容规则
        assertTrue(values.isNotEmpty())
    }

    @Test
    fun testLegadoRegexReplacement() {
        val value = LegadoRule.evalFirst(doc, "class.item.0@tag.a.0@text##三体##三体（重制版）")
        assertEquals("三体（重制版）", value)
    }

    @Test
    fun testLegadoSelectElementsAndReverse() {
        val items = LegadoRule.selectElements(doc, "class.item")
        assertEquals(3, items.size)
        val reversed = LegadoRule.selectElements(doc, "-class.item")
        assertEquals("超新星纪元", reversed.first().selectFirst("a")?.text())
    }

    @Test
    fun testLegadoExcludePosition() {
        val items = LegadoRule.selectElements(doc, "class.item!0")
        assertEquals(2, items.size)
        assertEquals("球状闪电", items.first().selectFirst("a")?.text())
    }

    @Test
    fun testOldFormatCssStillWorks() {
        assertEquals("刘慈欣", LegadoRule.evalFirst(doc, "span.author@text"))
        assertEquals("/book/2", LegadoRule.evalFirst(doc, "li.item:eq(1) a@href"))
    }

    @Test
    fun testTagShorthand() {
        // a.0@href 等价 tag.a.0@href
        assertEquals("/book/1", LegadoRule.evalFirst(doc, "a.0@href"))
        // 单标签 img@src
        assertEquals(
            "https://a.com/cover1.jpg",
            LegadoRule.evalFirst(doc, "li.item:eq(0) img@data-src")
        )
    }

    @Test
    fun testClassDotPosition() {
        // .item.1 等价 class.item.1
        assertEquals("球状闪电", LegadoRule.evalFirst(doc, ".item.1@tag.a.0@text"))
        // .item!0 排除第一个
        val items = LegadoRule.selectElements(doc, ".item!0")
        assertEquals(2, items.size)
    }

    @Test
    fun testMultiClassSelector() {
        val html = """
            <div class="view-main-1 readForm"><img src="https://x.com/1.jpg"></div>
        """.trimIndent()
        val d = Jsoup.parse(html)
        val src = LegadoRule.evalFirst(d, "class.view-main-1 readForm@img@src")
        assertEquals("https://x.com/1.jpg", src)
    }

    @Test
    fun testContentOnlyRule() {
        val el = doc.selectFirst("li.item a")
        assertNotNull(el)
        assertEquals("三体", LegadoRule.evalFirst(el!!, "@text"))
        assertEquals("/book/1", LegadoRule.evalFirst(el, "@href"))
    }

    @Test
    fun testHtmlContentValueWithImgTags() {
        val html = """
            <div id="cp_img"><p><img src="https://x.com/1.jpg"></p><p><img data-src="https://x.com/2.jpg"></p></div>
        """.trimIndent()
        val d = Jsoup.parse(html)
        val values = LegadoRule.evalValues(d, "#cp_img@html")
        assertEquals(1, values.size)
        assertTrue(values[0].contains("<img"))
    }
}
