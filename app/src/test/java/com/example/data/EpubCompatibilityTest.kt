package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EpubCompatibilityTest {
    @Test fun canonicalChapterAndTocPathsRetainValidNestedEpubs() = checkImport("chapter.xhtml", true)
    @Test fun canonicalChapterPathsRejectTraversalOutsideTheExtractionRoot() = checkImport("../../outside.xhtml", false)

    private fun checkImport(href: String, valid: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val archive = File(context.cacheDir, "compatibility.epub")
        val entries = mapOf(
            "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OPS/book.opf"/></rootfiles></container>""",
            "OPS/book.opf" to """<package><metadata><title>路径验证</title></metadata><manifest><item id="chapter" href="$href" media-type="application/xhtml+xml"/><item id="toc" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "OPS/chapter.xhtml" to """<html><body><p>测试正文</p></body></html>""",
            "OPS/nav.xhtml" to """<html><body><nav><a href="chapter.xhtml">目录章节</a><a href="../../outside.xhtml">越界目录</a></nav></body></html>"""
        )
        try {
            ZipOutputStream(archive.outputStream()).use { zip -> entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray()); zip.closeEntry()
            } }
            val result = EpubParser.importEpub(context, Uri.fromFile(archive), archive.name, db.bookDao())
            assertEquals(result.exceptionOrNull()?.message, valid, result.isSuccess)
            if (valid) {
                val chapters = db.bookDao().getChaptersListForBook(result.getOrThrow().id)
                assertTrue(chapters.any { it.content.contains("测试正文") })
                assertTrue(chapters.none { it.title == "越界目录" })
            }
        } finally { db.close(); archive.delete() }
    }
}
