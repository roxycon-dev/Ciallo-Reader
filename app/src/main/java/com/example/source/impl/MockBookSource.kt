package com.example.source.impl

import com.example.i18n.tr

import android.content.Context
import com.example.source.AuthenticationState
import com.example.source.BookSource
import com.example.source.DownloadInfo
import com.example.source.LoginCredential
import com.example.source.SearchBook
import com.example.source.SourceCapabilities
import com.example.source.SourceResult

/**
 * Environment-only mock source used by integration tests and local demos.
 * Never shown in the production UI because [SourceCapabilities.environmentOnly] is true.
 */
class MockBookSource(context: Context) : BookSource {
    override val id: String = "mock"
    override val name: String = "Mock 测试书源"
    override val capabilities: SourceCapabilities = SourceCapabilities(
        supportSearch = true,
        supportDownload = true,
        supportDebug = true,
        environmentOnly = true
    )

    private val sampleBooks = listOf(
        SearchBook(
            id = "mock/pride-and-prejudice",
            sourceId = id,
            title = "Pride and Prejudice",
            author = "Jane Austen",
            cover = null,
            format = "epub",
            downloadUrl = "https://example.com/mock/pride-and-prejudice.epub"
        ),
        SearchBook(
            id = "mock/three-body",
            sourceId = id,
            title = tr("三体 (The Three-Body Problem)"),
            author = "刘慈欣",
            cover = null,
            format = "epub",
            downloadUrl = "https://example.com/mock/three-body.epub"
        )
    )

    override suspend fun search(keyword: String): SourceResult<List<SearchBook>> {
        val filtered = if (keyword.isBlank()) {
            sampleBooks
        } else {
            sampleBooks.filter {
                it.title.contains(keyword, ignoreCase = true) ||
                    it.author.contains(keyword, ignoreCase = true)
            }
        }
        return SourceResult.Success(filtered)
    }

    override suspend fun getDetail(bookId: String): SourceResult<SearchBook> {
        val book = sampleBooks.firstOrNull { it.id == bookId }
        return if (book != null) {
            SourceResult.Success(book)
        } else {
            SourceResult.Error(com.example.source.SourceException.BookNotFound)
        }
    }

    override suspend fun getDownloadInfo(bookId: String): SourceResult<DownloadInfo> {
        val detail = getDetail(bookId).getOrNull() ?: return SourceResult.Error(
            com.example.source.SourceException.BookNotFound
        )
        return SourceResult.Success(
            DownloadInfo(
                url = detail.downloadUrl ?: "https://example.com/mock/${detail.id}.epub",
                fileName = detail.title,
                format = detail.format,
                referer = "https://example.com/"
            )
        )
    }

    override suspend fun login(credential: LoginCredential): SourceResult<Boolean> =
        SourceResult.Success(true)

    override suspend fun logout() {
        // No-op for mock source
    }

    override suspend fun isLoggedIn(): Boolean = true

    override suspend fun getAuthenticationState(): AuthenticationState =
        AuthenticationState.NotRequired
}
