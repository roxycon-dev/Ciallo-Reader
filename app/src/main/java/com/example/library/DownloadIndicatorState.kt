package com.example.library

import com.example.download.DownloadState

internal enum class DownloadIndicatorStatus { Idle, Running, Paused, Failed }

/** A toolbar entry describes the live queue; completed history never changes its resting icon. */
internal data class DownloadIndicatorState(
    val activeIds: Set<String> = emptySet(),
    val completedIds: Set<String> = emptySet(),
    val pausedCount: Int = 0,
    val failedCount: Int = 0,
    val progress: Float? = null,
) {
    val activeCount: Int get() = activeIds.size
    val status: DownloadIndicatorStatus get() = when {
        activeCount > 0 -> DownloadIndicatorStatus.Running
        failedCount > 0 -> DownloadIndicatorStatus.Failed
        pausedCount > 0 -> DownloadIndicatorStatus.Paused
        else -> DownloadIndicatorStatus.Idle
    }
    val description: String get() = when (status) {
        DownloadIndicatorStatus.Running -> "$activeCount 项下载中"
        DownloadIndicatorStatus.Failed -> "$failedCount 项失败"
        DownloadIndicatorStatus.Paused -> "$pausedCount 项暂停"
        DownloadIndicatorStatus.Idle -> "无进行中的下载"
    }
}

internal fun downloadIndicatorState(
    novels: Map<String, DownloadState>,
    comics: Map<String, ComicDownloadTask>,
): DownloadIndicatorState {
    val active = linkedSetOf<String>()
    val completed = linkedSetOf<String>()
    var paused = 0
    var failed = 0
    var known = 0
    var totalProgress = 0f
    fun running(id: String, progress: Float?) {
        active += id
        if (progress != null && progress.isFinite()) {
            known++
            totalProgress += progress.coerceIn(0f, 1f)
        }
    }
    for ((id, state) in novels) {
        val key = id // Managers already publish disjoint canonical ids: v2: / comic:.
        when (state) {
            DownloadState.Pending -> running(key, null)
            is DownloadState.Downloading -> running(key, state.progress.takeIf { state.totalBytes > 0 || it > 0f })
            is DownloadState.Success -> completed += key
            is DownloadState.Paused -> paused++
            is DownloadState.Error -> failed++
            DownloadState.Idle -> Unit
        }
    }
    for ((id, task) in comics) {
        val key = id
        when (task.status) {
            ComicDownloadStatus.DOWNLOADING -> running(key, task.progress.takeIf { it > 0f })
            ComicDownloadStatus.SUCCESS -> completed += key
            ComicDownloadStatus.PAUSED -> paused++
            ComicDownloadStatus.FAILED -> failed++
        }
    }
    return DownloadIndicatorState(active, completed, paused, failed,
        if (active.isNotEmpty() && known == active.size) totalProgress / active.size else null)
}

/** Only a task observed running can produce a completion acknowledgement.
 * Loading old history, cancelling a task or reopening a tab cannot replay success.
 */
internal class DownloadCompletionTracker {
    private var previousActive: Set<String> = emptySet()

    fun update(state: DownloadIndicatorState): Boolean {
        val newlyCompleted = previousActive.any { it in state.completedIds }
        previousActive = state.activeIds
        return newlyCompleted && state.status == DownloadIndicatorStatus.Idle
    }
}
