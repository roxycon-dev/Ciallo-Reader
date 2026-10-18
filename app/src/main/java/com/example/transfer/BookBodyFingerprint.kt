package com.example.transfer

import android.content.Context
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.security.MessageDigest

/** A second identity when either device has lost its original file. Paths are never identities. */
internal class BookBodyFingerprint(private val context: Context, private val portableFiles: File? = null) {
    private val resources = hashMapOf<String, String?>()

    private fun resourceHash(path: String): String? {
        if (path.startsWith("https://") || path.startsWith("http://")) return "url:" + sha256(path.toByteArray())
        if (resources.containsKey(path)) return resources[path]
        val hash = try {
            if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))?.use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
                digest.digest().hex()
            } else {
                val raw = path.removePrefix("file://")
                val resolved = if (raw.startsWith("@FILES@/") && portableFiles != null)
                    File(portableFiles, raw.removePrefix("@FILES@/")) else File(raw)
                resolved.takeIf { it.isFile }?.let(::fileHash)
            }
        } catch (_: Exception) { null }
        resources[path] = hash
        return hash
    }

    fun chapterHash(body: String, contentType: String = "NOVEL"): String? {
        // Comic chapters store a page path directly, while EPUB/novel images use [IMG:...].
        if (contentType == "COMIC" && body.isNotBlank() && !body.contains("[IMG:"))
            return resourceHash(body.trim())?.let { sha256("[IMG:sha256:$it]".toByteArray()) }
        var complete = true
        val normalized = Regex("\\[IMG:([^]]+)]").replace(body) { match ->
            val path = match.groupValues[1]
            val hash = resourceHash(path)
            if (hash == null) complete = false
            "[IMG:sha256:$hash]"
        }
        return if (complete) sha256(normalized.toByteArray()) else null
    }

    fun local(db: SupportSQLiteDatabase, bookId: Int, contentType: String): String? {
        val body = Accumulator()
        db.query("SELECT chapterOrder,title,content FROM chapters WHERE bookId=? ORDER BY chapterOrder", arrayOf(bookId)).use { c ->
            while (c.moveToNext()) body.add(c.getInt(0), c.getString(1), chapterHash(c.getString(2),contentType), c.getString(2).isNotBlank())
        }
        return body.finish(contentType)
    }

    class Accumulator {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var complete = true
        private var readable = false
        fun add(order: Int, title: String, hash: String?, hasContent: Boolean) {
            if (hash == null) complete = false
            readable = readable || hasContent
            for (part in listOf(order.toString(), title, hash.orEmpty())) {
                digest.update(part.toByteArray()); digest.update(0.toByte())
            }
        }
        fun finish(contentType: String): String? {
            if (!complete || !readable) return null
            digest.update(contentType.toByteArray())
            return "body-content:" + digest.digest().hex()
        }
    }
}
