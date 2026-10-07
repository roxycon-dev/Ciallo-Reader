package com.example.library

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.download.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.junit.Assert.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BookShareDeviceTest {
    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var root: File
    @Before fun setup() {
        app = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        root = File(app.filesDir, "share-test-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, db)
    }
    @After fun cleanup() {
        db.close()
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }.set(null, null)
        root.deleteRecursively()
    }
    private fun file(name: String, bytes: ByteArray = "original bytes".toByteArray()) = File(root, name).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    private fun book(id: Int, file: File, comic: Boolean = false) = Book(id, "作品$id", filePath = file.absolutePath, contentType = if (comic) "COMIC" else "NOVEL")
    private fun bytes(uri: Uri) = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
    private fun stream(intent: Intent) = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
    private fun archive(): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
        listOf("page_10.jpg", "page_2.jpg").forEach { name ->
            zip.putNextEntry(ZipEntry(name)); zip.write(name.toByteArray()); zip.closeEntry()
        }
    } }.toByteArray()

    @Test fun singleBookSendsOriginalBytesWithReadGrant() = runBlocking {
        val source = file("小说.txt", byteArrayOf(-1, 0, 12, 88))
        val intent = BookShareHelper.prepareShareIntent(app, listOf(book(1, source)))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertArrayEquals(source.readBytes(), bytes(stream(intent)))
        assertEquals(stream(intent), intent.clipData!!.getItemAt(0).uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNull(intent.getStringExtra(Intent.EXTRA_TEXT))
    }
    @Test fun multiBookKeepsOrderEveryStreamAndMixedMime() = runBlocking {
        val a = file("a.epub"); val b = file("b.cbz"); val c = file("c.txt")
        val intent = BookShareHelper.prepareShareIntent(app, listOf(book(1,a),book(2,b,true),book(3,c)))
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(3, uris.size)
        uris.forEachIndexed { i, uri ->
            assertEquals(uri, intent.clipData!!.getItemAt(i).uri)
            assertArrayEquals(listOf(a,b,c)[i].readBytes(), bytes(uri))
        }
    }
    @Test fun homogeneousFilesKeepSpecificMime() = runBlocking {
        val intent = BookShareHelper.prepareShareIntent(app, listOf(book(1,file("a.pdf"),true),book(2,file("b.pdf"),true)))
        assertEquals("application/pdf", intent.type)
    }
    @Test fun sameMimeFamilyUsesFamilyWildcard() = runBlocking {
        val intent = BookShareHelper.prepareShareIntent(app, listOf(book(1,file("a.epub")),book(2,file("b.cbz"),true)))
        assertEquals("application/*", intent.type)
    }
    @Test fun encodedFileUriWithSpacesStillSharesOriginal() = runBlocking {
        val original = file("有 空格.cbz", archive())
        val b = book(1,original,true).copy(filePath = Uri.fromFile(original).toString())
        assertArrayEquals(original.readBytes(), bytes(stream(BookShareHelper.prepareShareIntent(app,listOf(b)))))
    }
    @Test fun outsideProviderRootsCopiesBytesWithoutExposingDirectory() = runBlocking {
        val original = File(app.cacheDir, "outside-${UUID.randomUUID()}.mobi").apply { writeBytes(byteArrayOf(1,0,-3,24)) }
        try {
            val uri = stream(BookShareHelper.prepareShareIntent(app,listOf(book(1,original))))
            assertEquals("content", uri.scheme)
            assertTrue(uri.path!!.contains("share_temp"))
            assertArrayEquals(original.readBytes(), bytes(uri))
        } finally { original.delete() }
    }
    @Test fun safImportSharesCompleteOriginalAfterProviderPermissionIsGone() = runBlocking {
        val raw = archive()
        val incoming = file("incoming.cbz", raw)
        val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", incoming)
        val imported = BookRepository(app,db.bookDao(),db).importBookFromUri(uri,"原始漫画.cbz").getOrThrow()
        try {
            val original = File(imported.filePath,"original/原始漫画.cbz")
            assertTrue(original.isFile)
            assertArrayEquals(raw, original.readBytes())
            assertArrayEquals(raw, bytes(stream(BookShareHelper.prepareShareIntent(app,listOf(imported)))))
            assertEquals(2, db.bookDao().getChaptersListForBook(imported.id).size)
        } finally {
            File(imported.filePath).deleteRecursively()
            File(imported.coverUri!!).delete()
        }
    }
    @Test fun retainedPdfWinsOverExtractedImages() = runBlocking {
        val dir = File(root,"comic").apply { mkdirs() }
        val pdf = file("comic/original/original.pdf", "%PDF-exact-original".toByteArray())
        val intent = BookShareHelper.prepareShareIntent(app,listOf(book(1,dir,true)))
        assertEquals("application/pdf", intent.type)
        assertArrayEquals(pdf.readBytes(),bytes(stream(intent)))
    }
    @Test fun taskRecoveryMatchesSourceAndIdInsteadOfSameTitle() = runBlocking {
        val original = file("right.cbz", archive()); val wrong = file("wrong.cbz", "wrong".toByteArray())
        listOf("sourceB" to wrong,"sourceA" to original).forEach { (source,file) ->
            db.downloadTaskDao().insertOrUpdate(DownloadTaskEntity(DownloadManager.taskId(source,"42"),source,"同名","",null,"","cbz",DownloadStatus.COMPLETED,filePath=file.absolutePath))
        }
        val b = book(1,File(root,"missing"),true).copy(sourceId="sourceA",comicId="42",title="同名")
        assertArrayEquals(original.readBytes(),bytes(stream(BookShareHelper.prepareShareIntent(app,listOf(b)))))
    }
    @Test fun legacyComicPackagesAllPagesInDatabaseOrder() = runBlocking {
        val dir = File(root,"legacy").apply { mkdirs() }
        val second = file("legacy/p10.jpg", byteArrayOf(10)); val first = file("legacy/p2.jpg",byteArrayOf(2))
        val b = db.bookDao().insertBookWithChapters(book(0,dir,true),listOf(
            Chapter(bookId=0,chapterOrder=1,title="second",content=second.path),
            Chapter(bookId=0,chapterOrder=0,title="first",content=first.path)))
        val raw = bytes(stream(BookShareHelper.prepareShareIntent(app,listOf(b))))
        ZipInputStream(ByteArrayInputStream(raw)).use { zip ->
            assertTrue(zip.nextEntry.name.startsWith("0000_")); assertArrayEquals(byteArrayOf(2),zip.readBytes())
            assertTrue(zip.nextEntry.name.startsWith("0001_")); assertArrayEquals(byteArrayOf(10),zip.readBytes())
            assertNull(zip.nextEntry)
        }
    }
    @Test fun missingFileAbortsWholeBatchAndIdentifiesBook() = runBlocking {
        val result = runCatching { BookShareHelper.prepareShareIntent(app,listOf(book(1,file("okay.epub")),book(2,File(root,"missing.epub")))) }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("作品2"))
    }
    @Test fun missingLegacyPageDoesNotShareIncompleteComic() = runBlocking {
        val dir = File(root,"legacy").apply { mkdirs() }
        val b = db.bookDao().insertBookWithChapters(book(0,dir,true),listOf(
            Chapter(bookId=0,chapterOrder=0,title="lost",content=File(dir,"lost.jpg").path)))
        assertTrue(runCatching { BookShareHelper.prepareShareIntent(app,listOf(b)) }.isFailure)
    }
    @Test fun newShareDoesNotDeleteFreshTemporaryFiles() = runBlocking {
        val dir = File(app.cacheDir,"share_temp").apply { mkdirs() }
        val fresh = File(dir,"fresh-${UUID.randomUUID()}").apply { writeText("pending recipient") }
        val stale = File(dir,"stale-${UUID.randomUUID()}").apply { writeText("old"); setLastModified(System.currentTimeMillis()-25*60*60*1000L) }
        try {
            BookShareHelper.prepareShareIntent(app,listOf(book(1,file("a.epub"))))
            assertTrue(fresh.exists()); assertFalse(stale.exists())
        } finally { fresh.delete(); stale.delete() }
    }
    @Test fun cancelledPreparationIsNotReturnedAsShareFailure() = runBlocking {
        val job = kotlinx.coroutines.Job().apply { cancel() }
        val result = runCatching { kotlinx.coroutines.withContext(job) { BookShareHelper.shareBooks(app,listOf(book(1,file("a.epub")))) } }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
