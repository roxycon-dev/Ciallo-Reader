package com.example.library

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.data.favorite.FavoriteEntity
import com.example.source.*
import com.example.source.impl.*
import com.example.source.js.JsComicSource
import kotlinx.coroutines.*
import org.json.JSONArray
import org.junit.*
import org.junit.runner.RunWith
import org.junit.Assert.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FavoriteShareHelperTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    @Before fun setup() { app.getSharedPreferences("work_detail_share_links",Context.MODE_PRIVATE).edit().clear().commit() }
    private fun favorite(source: String, id: String, title: String = "同名作品") = FavoriteEntity(sourceId=source,comicId=id,title=title)
    private fun source(id: String, resolve: suspend (String) -> String?) = object : BookSource by MockBookSource(app) {
        override val id = id
        override suspend fun getShareUrl(bookId: String) = resolve(bookId)
    }

    @Test fun absoluteFavoriteLinkSharesOfflineWithoutSourceOrFile() = runBlocking {
        val intent = FavoriteShareHelper.prepareShareIntent(app,listOf(favorite("removed","https://source.test/work/42"))) { error("No source needed") }
        assertEquals(Intent.ACTION_SEND,intent.action)
        assertEquals("text/plain",intent.type)
        assertEquals("《同名作品》\nhttps://source.test/work/42",intent.getStringExtra(Intent.EXTRA_TEXT))
        assertNull(intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
    }
    @Test fun numericIdsAreResolvedByTheirOwnSourcesAndKeepSelectionOrder() = runBlocking {
        val calls = mutableListOf<String>()
        val intent = FavoriteShareHelper.prepareShareIntent(app,listOf(favorite("a","42"),favorite("b","42"))) { id ->
            source(id) { bookId -> synchronized(calls) { calls += "$id::$bookId" }; "https://$id.test/details/$bookId" }
        }
        assertEquals(setOf("a::42","b::42"),calls.toSet())
        assertEquals("《同名作品》\nhttps://a.test/details/42\n\n《同名作品》\nhttps://b.test/details/42",intent.getStringExtra(Intent.EXTRA_TEXT))
    }
    @Test fun resolvedLinksStayAvailableAfterRestartAndSourceRemoval() = runBlocking {
        val f = favorite("cached","42")
        FavoriteShareHelper.prepareShareIntent(app,listOf(f)) { source(it) { "https://cached.test/work/42" } }
        val intent = FavoriteShareHelper.prepareShareIntent(app,listOf(f)) { null }
        assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("https://cached.test/work/42"))
    }
    @Test fun sourceUrlWithCredentialsIsNotShared() = runBlocking {
        assertTrue(runCatching { FavoriteShareHelper.prepareShareIntent(app,listOf(favorite("a","42"))) {
            source(it) { "https://user:secret@source.test/download/42" }
        } }.isFailure)
    }
    @Test fun missingPublicPageSharesAnExplicitAppDetailLink() = runBlocking {
        val intent = FavoriteShareHelper.prepareShareIntent(app,listOf(
            favorite("a","https://a.test/42"),favorite("missing","42","缺失作品"))) { null }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("https://a.test/42"))
        assertTrue(text.contains("ciallo://detail?source=missing&id=42"))
        assertTrue(text.contains("需启用相同书源"))
    }
    @Test fun invalidSchemesAndControlCharactersAreRejected() {
        listOf("file:///sdcard/book","content://provider/book","javascript:alert(1)","https://source.test/42\nleak").forEach { assertNull(DetailLink.valid(it)) }
        assertNull(DetailLink.resolve("javascript:alert(1)","https://source.test/"))
    }
    @Test fun htmlSourceResolvesRelativeAndProtocolRelativeDetails() = runBlocking {
        val s = JsonBookSource(SourceConfig("html","HTML","https://source.test/",SearchRule("/search"),htmlSearch=HtmlSearchRule("/search","a")))
        assertEquals("https://source.test/work/42",s.getShareUrl("/work/42"))
        assertEquals("https://mirror.test/work/42",s.getShareUrl("//mirror.test/work/42"))
    }
    @Test fun jsonSourceUsesConfiguredDetailRuleAndEncodesId() = runBlocking {
        val s = JsonBookSource(SourceConfig("json","JSON","https://source.test/",SearchRule("/search"),detail=DetailRule("/work/{id}")))
        assertEquals("https://source.test/work/A%2FB",s.getShareUrl("A/B"))
        val unsupported = JsonBookSource(s.config.copy(detail=null))
        assertNull(unsupported.getShareUrl("42"))
    }
    @Test fun builtInNovelLinksPointToPublicDetailPages() = runBlocking {
        assertEquals("https://wenku8.ywy.moe/books/4367",Wenku8LibrarySource(app).getShareUrl("4367"))
        assertEquals("https://n.novelia.cc/novel/syosetu/n1234ab",AutoNovelSource(app).getShareUrl("syosetu/n1234ab"))
        assertEquals("https://ixdzs8.com/read/42/",IxdzsSource(app).getShareUrl("42"))
        assertEquals("https://mangadex.live/manga/42",MangaDexSource().getShareUrl("42"))
        assertEquals("https://mangadex.org/title/12345678-1234-1234-1234-123456789abc",
            MangaDexSource().getShareUrl("mdapi:12345678-1234-1234-1234-123456789abc"))
        assertTrue(runCatching { AutoNovelSource(app).getShareUrl("unknown/42") }.isFailure)
    }
    @Test fun jsSourceReusesSourceProvidedDetailUrlWithoutBootingEngine() = runBlocking {
        val key = JSONArray(listOf("js_fixture","42")).toString()
        app.getSharedPreferences("work_detail_share_links",Context.MODE_PRIVATE).edit().putString(key,"https://source.test/book/42").commit()
        val s = JsComicSource(app,"fixture","Fixture","1","invalid script should never execute")
        assertEquals("https://source.test/book/42",s.getShareUrl("42"))
    }
    @Test fun cancellationPropagatesThroughLinkResolution() = runBlocking {
        val job = Job().apply { cancel() }
        val result = runCatching { withContext(job) {
            FavoriteShareHelper.shareFavorites(app,listOf(favorite("a","42"))) { source(it) { delay(10_000); null } }
        } }
        assertTrue(result.exceptionOrNull() is CancellationException)
    }
    @Test fun apiOnlySourcesShareImmediatelyWithoutBootingJs() = runBlocking {
        for (key in listOf("jm", "picacg")) {
            val s = JsComicSource(app, key, key, "1", "invalid script")
            val intent = FavoriteShareHelper.prepareShareIntent(app, listOf(favorite(s.id, "42"))) { s }
            assertTrue(intent.getStringExtra(Intent.EXTRA_TEXT)!!.contains("ciallo://detail?source=js_$key&id=42"))
        }
    }
    @Test fun copyMangaAndManhuarenWithTheSameTitleKeepTheirOwnPublicLinks() = runBlocking {
        val copy = JsComicSource(app, "copy_manga", "拷贝漫画", "1", "invalid script")
        val other = JsComicSource(app, "manhuaren", "漫画人", "1", "invalid script")
        val intent = FavoriteShareHelper.prepareShareIntent(app, listOf(
            favorite(copy.id, "baihetianxin", "百合甜心"),
            favorite(other.id, "https://www.manhuaren.com/manhua-baihetianxin/", "百合甜心"),
        )) { if (it == copy.id) copy else other }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains("https://www.copy20.com/comic/baihetianxin"))
        assertTrue(text.contains("https://www.manhuaren.com/manhua-baihetianxin/"))
        assertEquals(2, text.lineSequence().count { it.startsWith("《") })
        assertFalse(text.contains("ciallo:"))
    }
    @Test fun networkFailureStillProvidesAnOpenableDetailLink() = runBlocking {
        val intent = FavoriteShareHelper.prepareShareIntent(app, listOf(favorite("offline", "42"))) {
            source(it) { throw java.io.IOException("offline") }
        }
        val url = intent.getStringExtra(Intent.EXTRA_TEXT)!!.lineSequence().first { it.startsWith("ciallo:") }
        assertEquals("42", SharedWorkLink.parse(android.net.Uri.parse(url))!!.bookId)
    }
    @Test fun appLinksRoundTripOpaqueIdsAndDoNotMixSources() {
        val id = "作品/42?chapter=5&name=甲#fragment"
        val target = SharedWorkLink.parse(android.net.Uri.parse(SharedWorkLink.create("js_a", id, "甲 & 乙")))!!
        assertEquals(SharedWorkLink.Target("js_a", id, "甲 & 乙"), target)
        assertNotEquals(SharedWorkLink.create("js_a", "42", "标题"), SharedWorkLink.create("js_b", "42", "标题"))
    }
    @Test fun malformedAppLinksAndCredentialIdsAreRejected() {
        listOf("ciallo://detail?source=a&id=42", "ciallo://detail?source=a&id=42&title=t&id=43",
            "ciallo://user@detail?source=a&id=42&title=t", "https://detail?source=a&id=42&title=t",
            "ciallo://detail?source=a&id=%0A&title=t").forEach { assertNull(SharedWorkLink.parse(android.net.Uri.parse(it))) }
        assertTrue(runCatching { SharedWorkLink.create("a", "https://user:secret@test/42", "标题") }.isFailure)
    }
}
