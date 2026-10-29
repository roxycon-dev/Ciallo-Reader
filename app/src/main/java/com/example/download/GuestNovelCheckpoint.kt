package com.example.download

import android.util.AtomicFile
import com.example.source.ComicChapter
import com.example.source.impl.NovelPart
import com.example.source.impl.validateParts
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable chapter boundaries, separate from the incomplete EPUB which is never imported. */
internal class GuestNovelCheckpoint(target: File, sourceId: String, downloadUrl: String, toc: List<ComicChapter>) {
    private val directory = directory(target)

    init {
        val identity = JSONObject().put("version", 1).put("sourceId", sourceId).put("url", downloadUrl)
            .put("toc", JSONArray().apply { toc.forEach { put(JSONArray().put(it.id).put(it.title)) } }).toString()
        val manifest = File(directory, "identity")
        if (read(manifest)?.toString(Charsets.UTF_8) != identity) {
            clear(target)
            check(directory.mkdirs() || directory.isDirectory) { "无法创建小说下载断点" }
            write(manifest, identity.toByteArray(Charsets.UTF_8))
        }
    }

    val completedChapters: Int
        get() = directory.listFiles()?.count { it.name.matches(Regex("chapter[0-9]+\\.json")) } ?: 0

    fun chapter(index: Int): List<NovelPart>? = runCatching {
        val file = File(directory, "chapter$index.json")
        val record = JSONObject(requireNotNull(read(file)).toString(Charsets.UTF_8))
        val payload = record.getString("payload")
        require(record.getString("sha256") == hash(payload.toByteArray(Charsets.UTF_8)))
        val rows = JSONArray(payload)
        (0 until rows.length()).map { i ->
            val row = rows.getJSONObject(i)
            when (row.getString("type")) {
                "text" -> NovelPart.Text(row.getString("value"))
                "image" -> NovelPart.Image(row.getString("value"))
                else -> error("断点格式无效")
            }
        }.also(::validateParts)
    }.getOrNull()

    fun saveChapter(index: Int, parts: List<NovelPart>) {
        val payload = JSONArray().apply {
            parts.forEach { part -> put(JSONObject().put("type", if (part is NovelPart.Text) "text" else "image")
                .put("value", when (part) { is NovelPart.Text -> part.value; is NovelPart.Image -> part.url })) }
        }.toString()
        write(File(directory, "chapter$index.json"), JSONObject().put("payload", payload)
            .put("sha256", hash(payload.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8))
    }

    data class Image(val bytes: ByteArray, val mime: String, val extension: String)

    fun image(url: String): Image? = runCatching {
        val key = hash(url.toByteArray(Charsets.UTF_8))
        val metadata = JSONObject(requireNotNull(read(File(directory, "$key.json"))).toString(Charsets.UTF_8))
        val bytes = requireNotNull(read(File(directory, "$key.image")))
        require(metadata.getString("sha256") == hash(bytes))
        Image(bytes, metadata.getString("mime"), metadata.getString("extension"))
    }.getOrNull()

    fun saveImage(url: String, image: Image) {
        val key = hash(url.toByteArray(Charsets.UTF_8))
        write(File(directory, "$key.image"), image.bytes)
        write(File(directory, "$key.json"), JSONObject().put("sha256", hash(image.bytes))
            .put("mime", image.mime).put("extension", image.extension).toString().toByteArray(Charsets.UTF_8))
    }

    companion object {
        private fun directory(target: File) = File(target.parentFile, "${target.nameWithoutExtension}.chapters")
        fun clear(target: File) { directory(target).deleteRecursively() }
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        private fun read(file: File): ByteArray? = runCatching { AtomicFile(file).openRead().use { it.readBytes() } }.getOrNull()
        private fun write(file: File, bytes: ByteArray) {
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream) }
            catch (e: Exception) { atomic.failWrite(stream); throw e }
        }
    }
}
