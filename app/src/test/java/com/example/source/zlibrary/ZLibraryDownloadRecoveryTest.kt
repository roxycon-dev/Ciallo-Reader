package com.example.source.zlibrary

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class ZLibraryDownloadRecoveryTest {
    @Test fun absoluteResultMovesToTheNewMirrorWithoutLosingBookIdentity() {
        assertEquals("https://new.example/book/1234/a1b2c3/name.html",
            ZLibraryBookReference.url("https://old.example/book/1234/a1b2c3/name.html", "new.example"))
        assertEquals("1234" to "a1b2c3", ZLibraryBookReference.apiKey("book/1234/a1b2c3/name.html"))
        assertEquals("1234" to "a1b2c3", ZLibraryBookReference.apiKey("https://old.example/book/1234/a1b2c3/name.html"))
        assertNull(ZLibraryBookReference.apiKey("book/not-an-id/login"))
    }

    @Test fun quotaAndStorageFailuresAreNotRetriedAcrossMirrors() {
        assertFalse(shouldRetryZLibraryDownload("今日下载次数已达上限（10/10）"))
        assertFalse(shouldRetryZLibraryDownload("Daily download limit reached"))
        assertFalse(shouldRetryZLibraryDownload("存储空间不足，请清理后重试"))
        assertTrue(shouldRetryZLibraryDownload("下载授权已失效（HTTP 403）"))
        assertTrue(shouldRetryZLibraryDownload("服务器返回了验证或错误页"))
        assertEquals("今日下载额度已用尽，请等待额度重置", zLibraryDownloadPageError("<h1>Daily download limit reached</h1>"))
        assertNull(zLibraryDownloadPageError("<h1>Checking your browser</h1>"))
    }

    @Test fun failedNodeDoesNotWinAndSlowProbeIsCancelled() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val winner = selectHealthyNode(listOf("slow", "bad", "good"), 2_000) { host ->
            when (host) {
                "slow" -> try { started.complete(Unit); awaitCancellation() } finally { cancelled.set(true) }
                "bad" -> false
                else -> { started.await(); true }
            }
        }
        assertEquals("good", winner)
        assertTrue(cancelled.get())
    }

    @Test fun allFailedCandidatesReturnNoWinner() = runBlocking {
        assertNull(selectHealthyNode(listOf("a", "b"), 500) { false })
        assertNull(selectHealthyNode(emptyList(), 500) { error("no probes expected") })
    }

    @Test fun aChallengePageIsNotTreatedAsAHealthyHomepage() {
        assertFalse(isZLibraryPage("<html>Checking your browser</html>"))
        assertFalse(isZLibraryPage("<h1>Z-Library domain for sale</h1>"))
        assertTrue(isZLibraryPage("<script src='/js/zlibrary.js'></script>"))
    }

    @Test fun mirrorRefreshRequiresOneMatchingEdition() {
        val expected = com.example.source.SearchBook("old", "zlibrary", "未来日记", "作者", format = "epub")
        val correct = expected.copy(id = "new")
        val wrongAuthor = correct.copy(id = "other", author = "另一个作者")
        assertEquals(correct, uniqueZLibraryEdition(listOf(correct, wrongAuthor), expected, "epub"))
        assertNull(uniqueZLibraryEdition(listOf(correct.copy(format = "pdf")), expected, "epub"))
        assertNull(uniqueZLibraryEdition(listOf(correct, correct.copy(id = "second-edition")), expected, "epub"))
    }
}
