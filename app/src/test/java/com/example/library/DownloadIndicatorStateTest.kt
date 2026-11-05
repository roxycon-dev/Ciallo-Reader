package com.example.library

import com.example.download.DownloadState
import com.example.source.ComicChapter
import com.example.source.SearchBook
import org.junit.Assert.*
import org.junit.Test

class DownloadIndicatorStateTest {
    private fun novel(state: DownloadState) = downloadIndicatorState(mapOf("book" to state), emptyMap())
    private fun comic(status: ComicDownloadStatus, progress: Float = 0f) = ComicDownloadTask(
        "chapter", SearchBook("id", "source", "Title", "Author"), ComicChapter("chapter", "Chapter"), status, progress)

    @Test fun completedHistoryKeepsTheDownloadEntryIdle() {
        val state = novel(DownloadState.Success("/book.epub"))
        assertEquals(DownloadIndicatorStatus.Idle, state.status)
        assertEquals(0, state.activeCount)
        assertFalse(DownloadCompletionTracker().update(state))
    }
    @Test fun mixedQueueUsesAllActiveTasksWithoutPromotingHistory() {
        val state = downloadIndicatorState(mapOf("active" to DownloadState.Downloading(40, 100, .4f),
            "done" to DownloadState.Success("/done")), mapOf("comic" to comic(ComicDownloadStatus.DOWNLOADING, .8f)))
        assertEquals(2, state.activeCount)
        assertEquals(.6f, state.progress!!, .0001f)
    }
    @Test fun pendingOrUnknownTasksKeepTheRingIndeterminate() {
        assertNull(novel(DownloadState.Pending).progress)
        assertNull(novel(DownloadState.Downloading(0, 0, 0f)).progress)
        assertNull(downloadIndicatorState(mapOf("pending" to DownloadState.Pending,
            "known" to DownloadState.Downloading(50, 100, .5f)), emptyMap()).progress)
    }
    @Test fun malformedProgressCannotEscapeIntoTheCanvas() {
        assertNull(novel(DownloadState.Downloading(0, 100, Float.NaN)).progress)
        assertEquals(1f, novel(DownloadState.Downloading(100, 100, 1.5f)).progress!!, 0f)
    }
    @Test fun failuresAndPausesAreAttentionStatesWithoutChangingTheAction() {
        assertEquals(DownloadIndicatorStatus.Paused, novel(DownloadState.Paused(10, 100)).status)
        assertEquals(DownloadIndicatorStatus.Failed, novel(DownloadState.Error("failed")).status)
        val state = downloadIndicatorState(mapOf("active" to DownloadState.Pending,
            "failed" to DownloadState.Error("failed")), emptyMap())
        assertEquals(DownloadIndicatorStatus.Running, state.status)
    }
    @Test fun onlyObservedRunningTasksCanAcknowledgeCompletion() {
        val tracker = DownloadCompletionTracker()
        assertFalse(tracker.update(novel(DownloadState.Downloading(90, 100, .9f))))
        assertTrue(tracker.update(novel(DownloadState.Success("/book"))))
        assertFalse(tracker.update(novel(DownloadState.Success("/book"))))
    }
    @Test fun cancellationAndHistoryRemovalNeverReplaySuccess() {
        val tracker = DownloadCompletionTracker()
        tracker.update(novel(DownloadState.Success("/old")))
        tracker.update(novel(DownloadState.Pending))
        assertFalse(tracker.update(DownloadIndicatorState()))
        assertFalse(tracker.update(novel(DownloadState.Success("/old"))))
    }
    @Test fun anUnfinishedOrFailedBatchDoesNotCelebrate() {
        val tracker = DownloadCompletionTracker()
        tracker.update(novel(DownloadState.Pending))
        assertFalse(tracker.update(downloadIndicatorState(mapOf("book" to DownloadState.Success("/done"),
            "other" to DownloadState.Error("failed")), emptyMap())))
    }
}
