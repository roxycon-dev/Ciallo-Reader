package com.example.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.AppDatabase
import com.example.source.ComicChapter
import com.example.source.SearchBook
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ComicLocalImporterDeviceTest {
    @Test fun resolvedHeadersAndChangedExtensionSurviveResume() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val dir = File(context.cacheDir, "comic-import-test-${UUID.randomUUID()}").apply { mkdirs() }
        val server = ServerSocket(0)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val image = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        // Old URL and extension from an interrupted task.
        File(dir, "img_0001.jpg").writeBytes(image)
        File(dir, "page_1.url").writeText("old-identity")
        server.soTimeout = 20_000
        val responder = thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                val request = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                val ok = request.any { it.equals("X-Image-Token: required", ignoreCase = true) }
                val status = if (ok) "200 OK" else "403 Forbidden"
                val body = if (ok) image else byteArrayOf()
                socket.getOutputStream().use { output ->
                    output.write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body)
                }
            }
        }
        var cover: File? = null
        try {
            val url = "http://127.0.0.1:${server.localPort}/page.png"
            val book = SearchBook("book", "test", "Test", "Test")
            val chapter = ComicChapter("chapter", "Chapter")
            suspend fun runImport() = ComicLocalImporter.importChapter(
                context, db.bookDao(), book, chapter, listOf(url), targetDir = dir,
                resolveImage = { null }, resolveHeaders = { mapOf("X-Image-Token" to "required") },
                onProgress = {}
            ).getOrThrow()
            val imported = runImport()
            cover = imported.coverUri?.let(::File)
            assertFalse(File(dir, "img_0001.jpg").exists())
            assertTrue(File(dir, "img_0001.png").isFile)
            // With the server stopped, a resume must reuse the new file only.
            responder.join(20_000)
            server.close()
            runImport()
            assertEquals(listOf("img_0001.png"), dir.listFiles().orEmpty()
                .filter { it.name.startsWith("img_") }.map { it.name })
        } finally {
            server.close()
            responder.join(20_000)
            db.close()
            cover?.delete()
            dir.deleteRecursively()
        }
    }
}
