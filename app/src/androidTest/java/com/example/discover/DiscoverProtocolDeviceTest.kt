package com.example.discover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.source.js.JsSourceEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Native QuickJS + host DOM checks. Deterministic fixtures, no accounts or live requests. */
@RunWith(AndroidJUnit4::class)
class DiscoverProtocolDeviceTest {
    @Test fun cursorSearchCategoriesBypassTheHostsFirstPageWrapper() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
        val source = """
            class CursorSource extends ComicSource {
                key='discover_cursor_fixture';
                category={parts:[{name:'题材',categories:['冒险'],itemType:'search'}]};
                search={loadNext:async(k,o,next)=>({comics:[{id:next?'second':'first',title:'冒险漫画'}],next:next?null:'cursor1'})};
            }
        """
        val engine = JsSourceEngine(asset("venera/_venera_.js"), source, "discover_cursor_fixture", context)
        suspend fun page(number: Int, cursor: String): JSONObject {
            val result = JSONObject(engine.call("(() => { ${asset("venera/discover_bridge.js")}; return __cialloDiscover(src, {dimension:'updates',range:'最新',categoryId:'category:0:0',page:$number,cursor:$cursor}); })()")!!)
            assertTrue(result.toString(), result.optBoolean("ok")); return result.getJSONObject("data")
        }
        assertEquals("cursor1", page(1, "null").getString("nextCursor"))
        val second = page(2, "'cursor1'")
        assertEquals("second", second.getJSONArray("entries").getJSONObject(0).getString("id"))
        assertFalse(second.getBoolean("hasMore"))
    }
    private suspend fun run(key: String, setup: String, query: String): JSONObject {
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun asset(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
        val engine = JsSourceEngine(asset("venera/_venera_.js"), asset("js_extra/$key.js"), "discover_fixture_$key", context)
        val result = JSONObject(engine.call("(() => { ${asset("venera/discover_bridge.js")}; $setup; return __cialloDiscover(src, $query); })()")!!)
        assertTrue(result.toString(), result.optBoolean("ok"))
        return result.getJSONObject("data")
    }
    @Test fun biliPublicCatalogueUsesRealHostDomAndRoutes() = runBlocking {
        val html = """<a class="book-layout" href="/detail/123.html"><img alt="冒险漫画" data-src="/cover.jpg"></a>"""
        val data = run("bilimanga", """
            src.page=async url=>{if(!url.endsWith('/top/lastupdate/1.html'))throw Error(url);return {body:${JSONObject.quote(html)},status:200};};
        """, "{dimension:'updates',range:'最新',page:1,feedId:'native'}")
        val entry = data.getJSONArray("entries").getJSONObject(0)
        assertEquals("https://www.bilimanga.net/detail/123.html", entry.getString("id"))
        assertEquals("冒险漫画", entry.getString("title")); assertFalse(data.getBoolean("hasMore"))
    }
    @Test fun pufeiCatalogueKeepsSupplierLinksAndExplicitAuthor() = runBlocking {
        val data = run("pufei", """
            src.loadData=()=>null;src.saveData=()=>{};
            src.json=async url=>{if(!url.includes('size=40')||!url.includes('page=1'))throw Error(url);
                return [{name:'测试漫画',url:'/comic/test',pic:'/cover.jpg',author:'作者',chapter_nums:4,chapter_url:'/chapter/1'}];};
        """, "{dimension:'updates',range:'最新',page:1,feedId:'native'}")
        val entry = data.getJSONArray("entries").getJSONObject(0)
        assertTrue(entry.getString("id").endsWith("/comic/test")); assertEquals("作者", entry.getString("author"))
    }
    @Test fun vomicSkipsAccountCardsAndRetainsActualUpdateTime() = runBlocking {
        val data = run("vomic", """
            Network.get=async()=>({status:200,body:''});
            src.parseHomeCards=()=>[{id:'1',name:'测试漫画',cover:'',subtitle:'2026-10-13T08:00:00+08:00'}];
        """, "{dimension:'updates',range:'最新',page:1,feedId:'native'}")
        val entries = data.getJSONArray("entries")
        assertEquals(1, entries.length()); assertEquals("2026-10-13T08:00:00+08:00", entries.getJSONObject(0).getString("updatedAt"))
        assertEquals("最新更新", entries.getJSONObject(0).getString("origin"))
        // Optional input supplied by the CLI inventory; report uses the production classifier.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inventory = java.io.File(context.getExternalFilesDir(null), "source-protocol-inventory.json")
        if (inventory.isFile) {
            val sources = JSONObject(inventory.readText()).getJSONArray("sources")
            val report = org.json.JSONArray()
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val categories = source.optJSONArray("categories") ?: org.json.JSONArray()
                val mapped = org.json.JSONArray(); val unknown = org.json.JSONArray()
                for (n in 0 until categories.length()) {
                    val category = categories.getJSONObject(n)
                    val label = category.getString("label")
                    val ids = DiscoverTaxonomy.classify(listOf(label))
                    if (ids.isEmpty()) unknown.put(label)
                    else mapped.put(JSONObject().put("label", label).put("standardIds", org.json.JSONArray(ids.sorted())))
                }
                assertEquals(categories.length(), mapped.length() + unknown.length())
                report.put(JSONObject().put("key", source.getString("key")).put("categories", categories.length())
                    .put("mapped", mapped).put("unmapped", unknown).put("dynamic", source.optBoolean("dynamicCategories")))
            }
            fun categoryTree(c: DiscoverCategory): JSONObject = JSONObject().put("id", c.id).put("label", c.label)
                .put("children", org.json.JSONArray(c.children.map(::categoryTree)))
            java.io.File(context.getExternalFilesDir(null), "discover-taxonomy-audit.json").writeText(
                JSONObject().put("scope", "declared native category labels; zero is not proof of source incompatibility")
                    .put("sources", report).put("taxonomy", org.json.JSONArray(DiscoverTaxonomy.roots.map(::categoryTree))).toString(2))
        }
    }
}
