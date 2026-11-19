package com.example.ui

import android.net.Uri
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.ListeningChapter
import com.example.data.ReaderDictionaryStore
import com.example.data.TtsManager
import com.example.data.paragraphAt
import com.example.data.speechChunkEnd
import com.example.data.spokenParagraphs
import com.example.ui.comic.ComicReaderTestActivity
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderListeningDataDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComicReaderTestActivity::class.java)

    @Test fun keepsSourceOffsetsAndDoesNotSplitUnicodePairs() {
        val text = "  First\r\n\r\n[IMG:one|200|100]\n第二段\n  Third"
        val paragraphs = spokenParagraphs(text)
        assertEquals(listOf("First", "第二段", "Third"), paragraphs.map { it.text })
        assertEquals(text.indexOf("第二段"), paragraphs[1].start)
        assertEquals(1, paragraphs.paragraphAt(text.indexOf("第二段") + 1))
        assertEquals(2, speechChunkEnd("ab😀cd", 0, 3))
    }

    @Test fun indexesCompressedStarDictAndResolvesAliasesOffline() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val data = "<b>Reader</b><br>A person who reads.".toByteArray()
        val zip = File.createTempFile("reader-stardict-", ".zip", app.cacheDir)
        ZipOutputStream(zip.outputStream()).use { archive ->
            fun entry(name: String, bytes: ByteArray) {
                archive.putNextEntry(ZipEntry("fixture/$name"))
                archive.write(bytes)
                archive.closeEntry()
            }
            entry("demo.ifo", ("StarDict's dict ifo file\nversion=2.4.2\nwordcount=1\n" +
                "bookname=Device Dictionary Fixture\nsametypesequence=h\nsynwordcount=1\n").toByteArray())
            entry("demo.idx.gz", fixtureBytes { stream ->
                GZIPOutputStream(stream).use { gzip ->
                    DataOutputStream(gzip).use { index ->
                        index.write("Reader".toByteArray())
                        index.writeByte(0)
                        index.writeInt(0)
                        index.writeInt(data.size)
                    }
                }
            })
            entry("demo.dict.dz", fixtureBytes { stream -> GZIPOutputStream(stream).use { it.write(data) } })
            entry("demo.syn", fixtureBytes { stream ->
                DataOutputStream(stream).use {
                    it.write("lector".toByteArray())
                    it.writeByte(0)
                    it.writeInt(0)
                }
            })
        }
        val store = ReaderDictionaryStore(app)
        val previous = store.list().map { it.id }.toSet()
        try {
            val dictionary = store.import(listOf(Uri.fromFile(zip))).single { it.id !in previous }
            val definition = store.lookup("ＲＥＡＤＥＲ").single { it.dictionary == dictionary.name }
            assertEquals("Reader", definition.word)
            assertTrue(definition.definition.contains("A person who reads."))
            assertEquals("lector", store.lookup("lector").single { it.dictionary == dictionary.name }.word)
            store.enable(dictionary.id, false)
            assertFalse(store.lookup("lector").any { it.dictionary == dictionary.name })
            store.enable(dictionary.id, true)
            assertTrue(store.lookup("lector").any { it.dictionary == dictionary.name })
        } finally {
            store.list().filter { it.id !in previous }.forEach { store.remove(it.id) }
            zip.delete()
        }
        assertEquals(previous, store.list().map { it.id }.toSet())
    }

    @Test fun startsForegroundSpeechAndResumesAtSelectedParagraph() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = TtsManager(app)
        val text = List(3) { "Paragraph $it. " + "A quiet evening in the library. ".repeat(15) }.joinToString("\n")
        val chapter = ListeningChapter("device-fixture", "first", "Ciallo", "Test chapter", spokenParagraphs(text))
        try {
            activity.scenario.onActivity { manager.play(chapter, 1, 1f, 1f) }
            awaitPlaying(manager)
            activity.scenario.onActivity {
                manager.pause()
                assertFalse(manager.state.value.playing)
                assertEquals(1, manager.state.value.paragraph)
                manager.moveParagraph(1)
                assertFalse(manager.state.value.playing)
                assertEquals(2, manager.state.value.paragraph)
                assertEquals(2, manager.savedParagraph(chapter))
                manager.play(chapter, null, 1f, 1f)
            }
            awaitPlaying(manager)
            assertEquals(2, manager.state.value.paragraph)
        } finally {
            activity.scenario.onActivity { manager.release() }
        }
    }

    private fun fixtureBytes(write: (java.io.ByteArrayOutputStream) -> Unit): ByteArray =
        java.io.ByteArrayOutputStream().also(write).toByteArray()

    private fun awaitPlaying(manager: TtsManager) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!manager.state.value.playing && manager.state.value.error == null &&
            android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(50)
        }
        assertTrue(manager.state.value.error ?: "No foreground playback", manager.state.value.playing)
    }
}
