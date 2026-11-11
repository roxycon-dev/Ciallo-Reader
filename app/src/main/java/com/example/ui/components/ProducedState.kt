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
