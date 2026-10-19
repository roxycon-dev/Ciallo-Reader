package com.example.source.zlibrary

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.SearchBook
import com.example.source.SourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ZLibraryCardMetadataTest {
    @Test fun opaqueCardLinkDoesNotDependOnTheFailingDetailPage() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = ZLibraryCredentialStorage(context.getSharedPreferences("card-metadata", Context.MODE_PRIVATE))
        val source = ZLibrarySource(context, storage)
        val book = SearchBook("book/opaque/title.html", "zlibrary", "测试书籍", "作者", format = "epub",
            downloadUrl = "https://example.com/dl/opaque?format=epub", size = 4096)
        val formats = source.getAvailableFormats(book)
        assertTrue(formats is SourceResult.Success)
        val format = (formats as SourceResult.Success).data.single()
        assertEquals("epub", format.format)
        assertEquals(book.downloadUrl, format.downloadUrl)
        assertEquals(book.size, format.size)
    }
}
