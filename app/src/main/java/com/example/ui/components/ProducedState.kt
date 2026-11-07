package com.example.ui.components

import androidx.compose.runtime.*

/** Retains the last value while key changes cancel and restart an asynchronous producer. */
@Composable
internal fun <T> rememberAsyncState(
    initialValue: T,
    vararg keys: Any?,
    producer: suspend MutableState<T>.() -> Unit,
): State<T> {
    val state = remember { mutableStateOf(initialValue) }
    LaunchedEffect(*keys) { state.producer() }
    return state
}

/**
 * Like [rememberAsyncState], but the value belongs to [keys]: when any key changes the state is
 * re-created from [initialValue] instead of keeping the previous value.
 *
 * Use it whenever showing the stale value would be wrong rather than merely less smooth — e.g. a
 * bitmap decoded from a path. [rememberAsyncState] deliberately keeps the old value to avoid a
 * blank frame while a key change re-produces, which is right for a fade-free text reload but wrong
 * for a page-swapped illustration: the new page would keep drawing the previous page's bitmap.
 */
@Composable
internal fun <T> rememberKeyedAsyncState(
    initialValue: T,
    vararg keys: Any?,
    producer: suspend MutableState<T>.() -> Unit,
): State<T> {
    val state = remember(*keys) { mutableStateOf(initialValue) }
    LaunchedEffect(*keys) { state.producer() }
    return state
}
