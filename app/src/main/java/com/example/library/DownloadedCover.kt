package com.example.library

import android.content.Context
import android.graphics.BitmapFactory
import com.example.data.readImportBytes
import com.example.source.executeCancellable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Preserves the original encoded image. Compare the portion usable in a portrait crop. */
internal object DownloadedCover {
    private fun width(options: BitmapFactory.Options): Int =
        minOf(options.outWidth, options.outHeight * 7 / 10).coerceAtLeast(0)

    private fun width(file: File?): Int =
        if (file == null || !file.isFile) 0 else BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, this)
        }.let(::width)

    fun originalUrl(cover: String): String {
        val url = cover.toHttpUrlOrNull() ?: return cover
        val path = url.encodedPath
        if (url.host != "uploads.mangadex.org" || !path.startsWith("/covers/")) return cover
        val suffix = listOf(".256.jpg", ".512.jpg").firstOrNull(path::endsWith) ?: return cover
        return url.newBuilder().encodedPath(path.removeSuffix(suffix)).build().toString()
    }

    suspend fun betterImage(
        context: Context,
        cover: String?,
        local: File?,
        sourceId: String,
        sourceHeaders: (suspend (String) -> Map<String, String>)? = null,
    ): ByteArray? {
        val url = cover?.toHttpUrlOrNull() ?: return null
        val existing = width(local)
        val original = originalUrl(url.toString())
        return withTimeoutOrNull(5_000) {
            for (candidate in listOf(original, url.toString()).distinct()) {
                try {
                    val headers = sourceHeaders?.invoke(candidate).orEmpty().ifEmpty {
                        when (sourceId) {
                            "ixdzs8" -> mapOf("Referer" to "https://ixdzs8.com/")
                            "wenku8_library" -> mapOf("Referer" to "https://wenku8.ywy.moe/")
                            else -> emptyMap()
                        }
                    }
                    val request = Request.Builder().url(candidate).apply {
                        headers.forEach { (name, value) -> header(name, value) }
                    }.build()
                    val client = GenericCoverLoader.client(context)
                    val bytes = client.newCall(request).apply {
                        timeout().timeout(4, java.util.concurrent.TimeUnit.SECONDS)
                    }.executeCancellable().use { response ->
                        if (!response.isSuccessful) null
                        else response.body?.byteStream()?.readImportBytes(12 * 1024 * 1024)
                    } ?: continue
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (width(bounds) > existing) return@withTimeoutOrNull bytes
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A missing or unreadable remote cover does not interrupt the download.
                }
            }
            null
        }
    }
}
