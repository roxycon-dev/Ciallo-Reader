package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.download.GuestNovelIntegrity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class GuestNovelIntegrityDeviceTest {
    @Test fun textRejectsMissingDuplicateAndEmptyChaptersWithoutChangingBytes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "guest-integrity-${System.nanoTime()}.txt")
        try {
            for (bad in listOf("第1章 开始\n正文。\n第3章 结尾\n正文。", "第1章 开始\n第2章 结尾\n正文。",
                "第1章 开始\n正文。\n第1章 重复\n重复正文。", "第1章 开始\n正文\uFFFD。",
                "正文\n内容还在处理中,请稍后重试")) {
                file.writeText(bad)
                assertTrue(runCatching { GuestNovelIntegrity.text(file) }.isFailure)
                assertEquals(bad, file.readText())
            }
            file.writeText("第1章 开始\n中文正文。\n第2章 结尾\n完整结尾。")
            GuestNovelIntegrity.text(file)
        } finally { file.delete() }
    }

    @Test fun epubRejectsMissingBodyMissingImagesBrokenImagesAndPlaceholderText() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "guest-integrity-${System.nanoTime()}.epub")
        fun fixture(body: String?, image: Boolean = false) {
            ZipOutputStream(file.outputStream()).use { zip ->
                val contents = linkedMapOf(
                    "mimetype" to "application/epub+zip",
                    "META-INF/container.xml" to """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""",
                    "OEBPS/content.opf" to """<package><manifest><item id="c1" href="chapter.xhtml"/></manifest><spine><itemref idref="c1"/></spine></package>"""
                )
                if (body != null) contents["OEBPS/chapter.xhtml"] = "<html><body><h1>第一章</h1>$body</body></html>"
                if (image) contents["OEBPS/image.png"] = "server error pretending to be an image"
                for ((name, text) in contents) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() }
            }
        }
        try {
            for (body in listOf(null, "<p>正文内容加载失败</p>", "<p>中文乱码\uFFFD</p>", "<img src='missing.png'/>", "<img src='https://example.invalid/image.png'/>")) {
                fixture(body)
                assertTrue(runCatching { GuestNovelIntegrity.epub(file) }.isFailure)
            }
            fixture("<img src='image.png'/>", true)
            assertTrue(runCatching { GuestNovelIntegrity.epub(file) }.isFailure)
            fixture("<p>这是完整的中文测试正文，用于验证离线电子书的内容和章节没有丢失。</p>")
            assertEquals(listOf("第一章"), GuestNovelIntegrity.epub(file).titles)
        } finally { file.delete() }
    }
}
