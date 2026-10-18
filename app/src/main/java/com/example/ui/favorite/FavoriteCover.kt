package com.example.ui.favorite

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.library.GenericCoverLoader
import com.example.library.ZLibraryCoverLoader
import com.example.source.BookSource
import com.example.source.ComicSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A removed thumbnail must not hide a still usable online cover. */
internal fun favoriteCoverData(local: String?, remote: String?, skipLocal: Boolean = false): String? {
    if (!skipLocal && !local.isNullOrBlank()) {
        if (local.startsWith("content://")) return local
        val file = File(local.removePrefix("file://"))
        if (file.isFile && file.canRead() && file.length() > 0) return local
    }
    return remote?.takeIf { it.isNotBlank() }
}

internal suspend fun favoriteCoverRequest(context: Context, data: String, source: BookSource?, attempt: Int): ImageRequest {
    val remote = data.startsWith("https://") || data.startsWith("http://")
    val headers = if (remote) when (source?.id) {
        "ixdzs8" -> mapOf("Referer" to "https://ixdzs8.com/")
        "wenku8_library" -> mapOf("Referer" to "https://wenku8.ywy.moe/")
        else -> (source as? ComicSource)?.getCoverHeaders(data).orEmpty()
    } else emptyMap()
    return ImageRequest.Builder(context).data(data).apply {
        headers.forEach { (name, value) -> addHeader(name, value) }
        // A bounded retry creates a fresh request even when its URL has not changed.
        setParameter("favoriteCoverAttempt", attempt)
    }.build()
}

@Composable
internal fun FavoriteCover(
    local: String?, remote: String?, sourceId: String, source: BookSource?,
    title: String, modifier: Modifier,
) {
    val context = LocalContext.current
    var skipLocal by remember(local, remote) { mutableStateOf(false) }
    var attempt by remember(local, remote, source) { mutableStateOf(0) }
    var failed by remember(local, remote, source) { mutableStateOf(false) }
    var request by remember(local, remote, source) { mutableStateOf<ImageRequest?>(null) }

    LaunchedEffect(local, remote, source, skipLocal, attempt) {
        val data = withContext(Dispatchers.IO) { favoriteCoverData(local, remote, skipLocal) }
        request = data?.let {
            try {
                favoriteCoverRequest(context, it, source, attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The shared loader can still use its cookies and browser UA if source headers fail.
                ImageRequest.Builder(context).data(it).setParameter("favoriteCoverAttempt", attempt).build()
            }
        }
    }
    LaunchedEffect(failed) {
        if (!failed) return@LaunchedEffect
        val data = request?.data?.toString().orEmpty()
        if (!data.startsWith("http://") && !data.startsWith("https://")) {
            skipLocal = true
        } else if (attempt < 2) {
            delay(500L * (attempt + 1))
            attempt++
        }
        failed = false
    }
    request?.let {
        AsyncImage(
            model = it,
            imageLoader = if (sourceId == "zlibrary") ZLibraryCoverLoader.get(context) else GenericCoverLoader.get(context),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            onError = { failed = true },
        )
    }
}
