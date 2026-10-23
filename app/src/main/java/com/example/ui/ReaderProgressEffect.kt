package com.example.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun rememberReaderScrollTopVisible(scrollState: ScrollState, enabled: Boolean): State<Boolean> =
    remember(scrollState, enabled) {
        derivedStateOf { enabled && scrollState.value > scrollState.maxValue * 0.2f }
    }

/** Observe pixels outside composition so scrolling cannot recompose the whole reader. */
@Composable
internal fun ReaderScrollProgressEffect(
    scrollState: ScrollState,
    enabled: Boolean,
    bookId: Int?,
    chapterIndex: Int,
    onSave: (Int) -> Unit,
) {
    val latestSave by rememberUpdatedState(onSave)
    LaunchedEffect(scrollState, enabled, bookId, chapterIndex) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { scrollState.value }.collectLatest { offset ->
            delay(1_500)
            latestSave(offset)
        }
    }
}
