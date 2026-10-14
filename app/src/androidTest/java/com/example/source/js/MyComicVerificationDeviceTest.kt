package com.example.source.js

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.discover.DiscoverDimension
import com.example.discover.DiscoverQuery
import com.example.source.SourceResult
import com.example.ui.comic.ComicImagePipeline
import com.example.ui.comic.ComicPageLoader
import com.example.ui.comic.ComicPageRef
import com.example.ui.comic.ComicReaderTestActivity
import com.example.ui.comicImageLoader
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit live chain check. Site challenges are completed by the user in the visible WebView. */
@RunWith(AndroidJUnit4::class)
class MyComicVerificationDeviceTest {
    @Test fun verificationCatalogueDetailsChapterAndDecodedImages() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = File(context.filesDir, "mycomic-production-source.js").readText()
        val patch = JsSourceRepo::class.java.getDeclaredMethod("patchScript", String::class.java, String::class.java)
            .apply { isAccessible = true }
        val script = patch.invoke(JsSourceRepo, "mycomic", raw) as String
        val source = JsComicSource(context, "mycomic", "MYCOMIC", "live-check", script)
        JsActivityTracker.register(context)
        val report = JSONObject().put("source", "mycomic").put("complete", false)
            .put("scriptSha256", MessageDigest.getInstance("SHA-256").digest(script.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) })
        val output = File(context.filesDir, "mycomic-verification-chain.json")
        fun save() = output.writeText(report.toString(2))
        fun <T> value(result: SourceResult<T>): T = when (result) {
            is SourceResult.Success -> result.data
            is SourceResult.Error -> throw AssertionError(result.toString())
        }
        try {
            ActivityScenario.launch(ComicReaderTestActivity::class.java).use {
                // No cookie deletion, credentials injection or automated CAPTCHA clicks.
                report.put("stage", "verification"); save()
                assertTrue(value(withTimeout(240_000) { source.verifyWebsite() }))
                report.put("verificationPassed", true).put("stage", "catalogue"); save()
                val catalog = value(withTimeout(45_000) { source.discoverCatalog() })
                val feed = catalog.feeds.firstOrNull() ?: error("MYCOMIC 没有原站目录")
                val page = value(withTimeout(45_000) { source.discover(DiscoverQuery(DiscoverDimension.UPDATES, "最新", feedId = feed.id)) })
                val book = page.entries.firstOrNull()?.book ?: error("MYCOMIC 目录无作品")
                report.put("catalogueCount", page.entries.size).put("bookId", book.id).put("stage", "details"); save()
                val detail = value(withTimeout(45_000) { source.getDetail(book.id) })
                assertTrue(detail.title.isNotBlank())
                val chapters = value(withTimeout(45_000) { source.getChapters(book.id) })
                assertTrue(chapters.isNotEmpty())
                report.put("title", detail.title).put("chapters", chapters.size).put("stage", "chapter"); save()
                val chapter = chapters.first()
                val pages = value(withTimeout(45_000) { source.getChapterImages(chapter.id) })
                assertTrue(pages.isNotEmpty())
                report.put("pages", pages.size).put("stage", "images"); save()
                val sampleUrls = listOf(pages.first(), pages.last()).distinct()
                val headers = source.getChapterImageHeaders(chapter.id, sampleUrls)
                val loader = ComicPageLoader(context, comicImageLoader(context))
                val samples = JSONArray()
                for (url in sampleUrls) {
                    val resolved = source.resolveChapterImage(url) ?: url
                    val imageHeaders = headers[url].orEmpty() + source.getResolvedHeaders(resolved)
                    val bitmap = withTimeout(45_000) { loader.load(ComicPageRef.Remote("mycomic-live", resolved, imageHeaders),
                        "mycomic-live", ComicImagePipeline.Geometry(), ComicImagePipeline.Toning()).bitmap }
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)
                    samples.put(JSONObject().put("width", bitmap.width).put("height", bitmap.height))
                }
                report.put("decodedImages", samples).put("complete", true).put("stage", "passed"); save()
            }
        } catch (error: Throwable) {
            report.put("error", error.javaClass.simpleName).put("complete", false); save()
            throw error
        }
    }
}
