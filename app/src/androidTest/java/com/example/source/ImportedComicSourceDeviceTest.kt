package com.example.source

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.js.ImportedComicSources
import com.example.source.js.JsCookieJar
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ImportedComicSourceDeviceTest {
    @Test fun previewIsStaticAndARealImportedSourceCanSearchAndRead() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val script = """
            // class NotTheSource extends ComicSource {}
            class LocalFixture extends ComicSource {
                name = "Device manga";
                key = "device-import-${System.nanoTime()}";
                version = "1.2.3";
                search = { load: async (keyword) => ({comics: [{id: "example", title: keyword}]}) };
                comic = {
                    loadInfo: async () => ({title: "Device manga", chapters: {"one": "Chapter 1"}}),
                    loadEp: async () => ({images: ["https://example.org/image.png"]})
                };
            }
        """.trimIndent()
        val good = File.createTempFile("source-device-", ".js", context.cacheDir).apply { writeText("\uFEFF$script") }
        val bad = File.createTempFile("source-invalid-", ".js", context.cacheDir).apply { writeText("class {") }
        try {
            val preview = ImportedComicSources.inspect(context, listOf(Uri.fromFile(bad), Uri.fromFile(good)))
            assertNotNull(preview[0].error)
            val definition = requireNotNull(preview[1].definition)
            assertEquals("LocalFixture", definition.className)
            assertEquals("1.2.3", definition.version)
            val static = ImportedComicSources.inspectScript(context, "throw Error('do not run during preview');\n$script")
            assertEquals(definition.id, static.id)
            ImportedComicSources.install(context, definition, replace = false)!!
            try {
                assertNull(ImportedComicSources.install(context, definition, replace = false))
                val source = requireNotNull(ImportedComicSources.find(context, definition.id))
                assertTrue(source.isUserImported)
                val match = (source.search("Device manga") as SourceResult.Success).data.single()
                assertEquals(definition.id, match.sourceId)
                val chapter = (source.getChapters(match.id) as SourceResult.Success).data.single()
                val image = (source.getChapterImages(chapter.id) as SourceResult.Success).data.single()
                assertEquals("https://example.org/image.png", image)
                JsCookieJar.saveCookiePairs(context, "example.org", listOf("imported=test"), definition.runtimeKey)
                assertEquals("imported=test", source.getChapterImageHeaders(chapter.id, listOf(image))[image]?.get("Cookie"))
                assertFalse(JsCookieJar.cookieHeader(context, image).contains("imported=test"))
                ImportedComicSources.install(context, definition.copy(version = "2.0"), replace = true)
                assertEquals("2.0", ImportedComicSources.find(context, definition.id)?.version)
                JsCookieJar.clear(context, "example.org", definition.runtimeKey)
            } finally {
                ImportedComicSources.remove(context, definition.id)
            }
            assertNull(ImportedComicSources.find(context, definition.id))
        } finally {
            good.delete()
            bad.delete()
        }
    }
}
