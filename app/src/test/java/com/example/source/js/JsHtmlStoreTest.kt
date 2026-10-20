package com.example.source.js

import org.junit.Assert.*
import org.junit.Test

class JsHtmlStoreTest {
    @Test fun disposingADocumentAlsoReleasesElementsAndChildNodes() {
        val store = JsHtmlStore()
        store.parse(0, "<p>old<span>child</span></p>")
        val element = store.querySelector(0, "p")!!
        val children = store.getNodes(element)
        assertEquals("oldchild", store.getText(element))
        assertEquals("element", store.nodeType(children.last()))
        assertNotNull(store.nodeToElement(children.last()))
        store.dispose(0)
        assertEquals("", store.getText(element))
        children.forEach { assertEquals("unknown", store.nodeType(it)) }
    }

    @Test fun countBoundEvictsTheOldDocumentAndItsHandles() {
        val store = JsHtmlStore()
        store.parse(0, "<p>old</p>")
        val old = store.querySelector(0, "p")!!
        (1..8).forEach { store.parse(it, "<p>new$it</p>") }
        assertNull(store.querySelector(0, "p"))
        assertEquals("", store.getText(old))
        assertEquals("new8", store.getText(store.querySelector(8, "p")!!))
    }

    @Test fun frequentlyUsedDocumentsSurviveCountEviction() {
        val store = JsHtmlStore()
        (0..7).forEach { store.parse(it, "<p>$it</p>") }
        store.querySelector(0, "p")
        store.parse(8, "<p>8</p>")
        assertNotNull(store.querySelector(0, "p"))
        assertNull(store.querySelector(1, "p"))
    }

    @Test fun sizeBudgetBoundsRetainedPagesBeforeTheCountLimit() {
        val store = JsHtmlStore()
        repeat(6) { store.parse(it, "<p>" + "x".repeat(1_200_000) + "</p>") }
        assertNull(store.querySelector(0, "p"))
        assertNotNull(store.querySelector(5, "p"))
        val retained = (0..5).count { store.querySelector(it, "p") != null }
        assertTrue("Retained $retained oversized documents", retained <= 3)
    }

    @Test fun weeklyUpdateCatalogueFitsWhileOversizedDocumentsAreStillRejected() {
        val store = JsHtmlStore()
        val card = "<div class='common-comic-item'><a><img></a><h3><a>作品</a></h3><p><a>最新话</a></p></div>"
        store.parse(0, card.repeat(1041))
        assertEquals(1041, store.querySelectorAll(0, ".common-comic-item").size)
        try {
            store.parse(1, "<i></i>".repeat(20_000))
            fail("Pathological DOM must retain a bounded budget")
        } catch (_: IllegalArgumentException) { }
        assertNull(store.querySelector(1, "i"))
        assertNotNull(store.querySelector(0, ".common-comic-item"))
    }


    @Test
    fun scriptElementTextReturnsData() {
        val html = """
            <html><body>
              <script id="sv-data" type="application/json">{"data":[{"id":1,"title":"One Piece"}]}</script>
              <p>普通文本</p>
            </body></html>
        """.trimIndent()
        val store = JsHtmlStore()
        val docId = store.parse(0, html)
        val elemId = store.getElementById(docId, "sv-data")
        assertNotNull(elemId)
        val text = store.getText(elemId!!)
        assertEquals("{\"data\":[{\"id\":1,\"title\":\"One Piece\"}]}", text)
    }

    @Test
    fun normalElementTextStillWorks() {
        val html = "<html><body><p id=\"p1\">hello</p></body></html>"
        val store = JsHtmlStore()
        val docId = store.parse(0, html)
        val elemId = store.getElementById(docId, "p1")
        assertNotNull(elemId)
        assertEquals("hello", store.getText(elemId!!))
    }

    @Test
    fun secondDocumentLookupWorks() {
        val store = JsHtmlStore()
        val doc0 = store.parse(0, "<html><body><p id=\"a\">first</p></body></html>")
        val doc1 = store.parse(1, """
            <html><body>
              <script type="application/json" id="comic-data">{"title":"One Piece"}</script>
            </body></html>
        """.trimIndent())
        assertEquals(0, doc0)
        assertEquals(1, doc1)
        val elem = store.getElementById(doc1, "comic-data")
        assertNotNull("second doc lookup failed", elem)
        assertEquals("{\"title\":\"One Piece\"}", store.getText(elem!!))
    }
}
