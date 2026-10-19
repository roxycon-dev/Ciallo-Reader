package com.example.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GuestNovelIntegrityTest {
    private fun epub(vararg bodies: ByteArray): File {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "quality.epub")
        val entries = linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>".toByteArray(),
            "book.opf" to ("<package><manifest>" + bodies.indices.joinToString("") {
                "<item id=\"c$it\" href=\"c$it.xhtml\"/>"
            } + "</manifest><spine>" + bodies.indices.joinToString("") { "<itemref idref=\"c$it\"/>" } + "</spine></package>").toByteArray()
        )
        bodies.forEachIndexed { i, body -> entries["c$i.xhtml"] = "<html><body><h1>第${i + 1}章</h1><p>".toByteArray() + body + "</p></body></html>".toByteArray() }
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, data) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(data); zip.closeEntry()
        } }
        return file
    }

    @Test fun isolatedSourceMissingCharacterDoesNotRejectAnOtherwiseCompleteNovel() {
        val file = epub(("中文正文".repeat(800) + "视线向�太移去").toByteArray())
        val before = file.readBytes()
        try {
            val result = GuestNovelIntegrity.epub(file)
            assertEquals(1, result.replacementCharacters)
            assertEquals(listOf("第1章"), result.titles)
            assertArrayEquals("Source text must not be silently rewritten", before, file.readBytes())
        } finally { file.delete() }
    }

    @Test fun malformedUtf8AndNullStillRejectEvenInLongChapters() {
        for (invalid in listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0))) {
            val file = epub("中文正文".repeat(800).toByteArray() + invalid)
            try { assertTrue(runCatching { GuestNovelIntegrity.epub(file) }.isFailure) } finally { file.delete() }
        }
    }

    @Test fun replacementBurstsShortBrokenChaptersAndBookWideDamageStillReject() {
        val cases = listOf(
            arrayOf(("中文正文".repeat(800) + "��").toByteArray()),
            arrayOf("中文正文缺�字".toByteArray()),
            Array(4) { ("中文正文".repeat(800) + "�").toByteArray() }
        )
        for (bodies in cases) {
            val file = epub(*bodies)
            try { assertTrue(runCatching { GuestNovelIntegrity.epub(file) }.isFailure) } finally { file.delete() }
        }
    }

    @Test fun missingContentPlaceholderStillRejects() {
        val file = epub(("中文正文".repeat(800) + "内容加载失败").toByteArray())
        try { assertTrue(runCatching { GuestNovelIntegrity.epub(file) }.isFailure) } finally { file.delete() }
    }
}
