package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.OpenableColumns
import org.jsoup.Jsoup
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class OfflineDictionary(val id: String, val name: String, val enabled: Boolean)
internal data class DictionaryResult(val dictionary: String, val word: String, val definition: String)

internal class ReaderDictionaryStore(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "reader_dictionaries")
    private val preferences = app.getSharedPreferences("reader_dictionaries", Context.MODE_PRIVATE)
    private val resolver = app.contentResolver

    suspend fun list(): List<OfflineDictionary> = onDisk { dictionaries() }

    suspend fun enable(id: String, enabled: Boolean): List<OfflineDictionary> = onDisk {
        preferences.edit().putBoolean(id, enabled).apply()
        dictionaries()
    }

    suspend fun remove(id: String): List<OfflineDictionary> = onDisk {
        dictionaries().firstOrNull { it.id == id }?.let {
            val old = File(root, it.id)
            val removed = File(root, ".deleted-${it.id}")
            check(old.renameTo(removed))
            removed.deleteRecursively()
            preferences.edit().remove(it.id).apply()
        }
        dictionaries()
    }

    suspend fun lookup(query: String): List<DictionaryResult> = onDisk {
        val key = dictionaryKey(query)
        val results = mutableListOf<DictionaryResult>()
        for (dictionary in dictionaries().filter { it.enabled }) {
            if (results.size >= MAX_RESULTS) break
            val folder = File(root, dictionary.id)
            val seen = mutableSetOf<Pair<Long, Int>>()
            val type = File(folder, "type").readText()
            openIndex(folder, readOnly = true).use { db ->
                RandomAccessFile(File(folder, "definitions"), "r").use { definitions ->
                    val visited = mutableSetOf<String>()
                    fun find(lemma: String, depth: Int) {
                        if (depth > 5 || !visited.add(lemma) || results.size >= MAX_RESULTS) return
                        db.rawQuery(
                            "SELECT word, offset, length FROM entries WHERE lemma = ? LIMIT 24", arrayOf(lemma)
                        ).use { matches ->
                            while (results.size < MAX_RESULTS && matches.moveToNext()) {
                                val offset = matches.getLong(1)
                                val length = matches.getInt(2)
                                if (!seen.add(offset to length)) continue
                                definitions.seek(offset)
                                val bytes = ByteArray(length).also(definitions::readFully)
                                val text = decodeDefinition(bytes, type)
                                if (text.startsWith("@@@LINK=")) {
                                    find(dictionaryKey(text.removePrefix("@@@LINK=")), depth + 1)
                                } else if (text.isNotBlank()) {
                                    results.add(DictionaryResult(dictionary.name, matches.getString(0), text))
                                }
                            }
                        }
                    }
                    find(key, 0)
                }
            }
        }
        results
    }

    suspend fun import(uris: List<Uri>): List<OfflineDictionary> = onDisk {
        if (uris.isEmpty()) return@onDisk dictionaries()
        require(dictionaries().size < 20)
        val inputs = uris.map { it to displayName(it) }
        root.mkdirs()
        val id = UUID.randomUUID().toString()
        val stage = File(root, ".import-$id").apply { mkdir() }
        try {
            if (inputs.size == 1 && inputs.first().second.endsWith(".zip", true)) {
                val archive = File(stage, "source.zip")
                open(inputs.first().first).use { copyBounded(it, archive, MAX_DATA) }
                ZipFile(archive).use { zip ->
                    val records = zip.entries().asSequence()
                        .filter { !it.isDirectory && it.name.substringAfterLast('/').isNotBlank() }
                        .take(2049).toList()
                    require(records.size <= 2048)
                    val standalone = records.singleOrNull { isStandaloneDictionary(it.name) }
                    if (standalone != null && records.none { it.name.endsWith(".ifo", true) }) {
                        zip.getInputStream(standalone).use { installStandalone(stage, standalone.name.substringAfterLast('/'), it) }
                        return@use
                    }
                    val info = records.single { it.name.endsWith(".ifo", true) }
                    val sources = records.filter { it.name.substringBeforeLast('/', "") == info.name.substringBeforeLast('/', "") }
                        .associate { entry -> entry.name.substringAfterLast('/') to { zip.getInputStream(entry) } }
                    install(stage, sources)
                }
                archive.delete()
            } else if (inputs.size == 1 && isStandaloneDictionary(inputs.first().second)) {
                val (uri, name) = inputs.first()
                open(uri).use { installStandalone(stage, name, it) }
            } else {
                require(inputs.size <= 6 && inputs.map { it.second }.distinct().size == inputs.size)
                install(stage, inputs.associate { (uri, name) -> name to { open(uri) } })
            }
            check(stage.renameTo(File(root, id)))
        } finally {
            stage.deleteRecursively()
        }
        dictionaries()
    }

    private fun isStandaloneDictionary(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("mdx", "mobi", "prc")

    private fun installStandalone(stage: File, name: String, input: InputStream) {
        val source = File(stage, "source").also { copyBounded(input, it, MAX_DATA) }
        val temporary = File(stage, "decoded")
        var title = ""
        var count = 0
        val definitions = File(stage, "definitions")
        openIndex(stage).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, lemma TEXT NOT NULL, word TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL)")
                db.compileStatement("INSERT INTO entries (lemma, word, offset, length) VALUES (?, ?, ?, ?)").use { add ->
                    definitions.outputStream().buffered().use { output ->
                        var offset = 0L
                        val emit: (String, String) -> Unit = record@ { word, definition ->
                            if (word.isBlank()) return@record
                            val bytes = definition.toByteArray(Charsets.UTF_8)
                            if (bytes.isEmpty() || bytes.size > MAX_DEFINITION) return@record
                            require(++count <= MAX_ENTRIES && offset + bytes.size <= MAX_DATA)
                            add.bindString(1, dictionaryKey(word))
                            add.bindString(2, word)
                            add.bindLong(3, offset)
                            add.bindLong(4, bytes.size.toLong())
                            add.executeInsert()
                            output.write(bytes)
                            offset += bytes.size
                        }
                        title = if (name.endsWith(".mdx", true)) extractMdxEntries(source, temporary, emit)
                            else extractMobiEntries(source, emit)
                    }
                }
                require(count > 0)
                db.execSQL("CREATE INDEX lemma_index ON entries (lemma)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        File(stage, "type").writeText("h")
        File(stage, "name").writeText(title.ifBlank { name.substringBeforeLast('.') }.take(160))
        source.delete()
        temporary.delete()
    }

    private fun install(stage: File, sources: Map<String, () -> InputStream>) {
        val infoName = sources.keys.single { it.endsWith(".ifo", true) }
        val stem = infoName.dropLast(4)
        val metadata = sources.getValue(infoName).invoke().use {
            val file = File(stage, "info")
            copyBounded(it, file, 32L * 1024)
            val info = file.readText().removePrefix("\uFEFF")
            require(info.lineSequence().firstOrNull()?.trim() == "StarDict's dict ifo file")
            info.lineSequence().drop(1).mapNotNull { line ->
                line.indexOf('=').takeIf { index -> index > 0 }?.let { index ->
                    line.substring(0, index) to line.substring(index + 1).trim()
                }
            }.toMap().also { check(file.delete()) }
        }
        require(metadata["version"] in setOf("2.4.2", "3.0.0"))
        val offsetBits = metadata["idxoffsetbits"]?.toInt() ?: 32
        require(offsetBits == 32 || offsetBits == 64)
        val expected = metadata.getValue("wordcount").toInt()
        require(expected in 1..MAX_ENTRIES)
        val type = metadata["sametypesequence"].orEmpty()
        require(type.length <= 32 && type.all { it in 'A'..'Z' || it in 'a'..'z' })
        val data = File(stage, "definitions")
        asset(sources, "$stem.dict", "$stem.dict.dz").use { copyBounded(it, data, MAX_DATA) }
        openIndex(stage).use { db ->
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE entries (id INTEGER PRIMARY KEY, lemma TEXT NOT NULL, word TEXT NOT NULL, offset INTEGER NOT NULL, length INTEGER NOT NULL)")
                db.compileStatement("INSERT INTO entries VALUES (?, ?, ?, ?, ?)").use { add ->
                    asset(sources, "$stem.idx", "$stem.idx.gz").use { stream ->
                        DataInputStream(stream.buffered()).use { index ->
                            repeat(expected) { id ->
                                val word = readWord(index)
                                val offset = if (offsetBits == 64) index.readLong() else index.readInt().toLong() and 0xffffffffL
                                val length = index.readInt()
                                require(offset >= 0 && length in 1..MAX_DEFINITION && offset <= data.length() - length)
                                add.bindLong(1, id.toLong())
                                add.bindString(2, dictionaryKey(word))
                                add.bindString(3, word)
                                add.bindLong(4, offset)
                                add.bindLong(5, length.toLong())
                                add.executeInsert()
                            }
                            require(index.read() == -1)
                        }
                    }
                }
                val aliases = metadata["synwordcount"]?.toInt() ?: 0
                require(aliases in 0..MAX_ENTRIES)
                if (aliases > 0) {
                    db.compileStatement("INSERT INTO entries (lemma, word, offset, length) SELECT ?, ?, offset, length FROM entries WHERE id = ?").use { add ->
                        DataInputStream(requireNotNull(sources["$stem.syn"]).invoke().buffered()).use { index ->
                            repeat(aliases) {
                                val word = readWord(index)
                                val id = index.readInt()
                                require(id in 0 until expected)
                                add.bindString(1, dictionaryKey(word))
                                add.bindString(2, word)
                                add.bindLong(3, id.toLong())
                                add.executeInsert()
                            }
                            require(index.read() == -1)
                        }
                    }
                }
                db.execSQL("CREATE INDEX lemma_index ON entries (lemma)")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        File(stage, "type").writeText(type)
        File(stage, "name").writeText(metadata["bookname"]?.take(160)?.ifBlank { stem } ?: stem)
    }

    private fun asset(sources: Map<String, () -> InputStream>, plain: String, compressed: String): InputStream {
        sources[plain]?.let { return it() }
        val source = requireNotNull(sources[compressed]).invoke()
        return try { GZIPInputStream(source) } catch (failure: IOException) {
            source.close()
            throw failure
        }
    }

    private fun dictionaries(): List<OfflineDictionary> =
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }
            .mapNotNull { folder ->
                val name = File(folder, "name").takeIf(File::isFile)?.readText() ?: return@mapNotNull null
                OfflineDictionary(folder.name, name, preferences.getBoolean(folder.name, true))
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: error("No file name")

    private fun open(uri: Uri): InputStream = requireNotNull(resolver.openInputStream(uri))

    private suspend fun <T> onDisk(action: () -> T): T = withContext(Dispatchers.IO) {
        diskLock.withLock { action() }
    }

    private companion object {
        private val diskLock = Mutex()
        private const val MAX_DATA = 256L * 1024 * 1024
        private const val MAX_ENTRIES = 1_000_000
        private const val MAX_RESULTS = 40
        private const val MAX_DEFINITION = 128 * 1024

        private fun openIndex(folder: File, readOnly: Boolean = false): SQLiteDatabase =
            SQLiteDatabase.openDatabase(File(folder, "index.db").path, null,
                if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.CREATE_IF_NECESSARY)

        private fun readWord(input: DataInputStream): String {
            val bytes = ByteArray(255)
            for (index in bytes.indices) {
                val byte = input.readUnsignedByte()
                if (byte == 0) {
                    require(index > 0)
                    return String(bytes, 0, index, Charsets.UTF_8)
                }
                bytes[index] = byte.toByte()
            }
            error("Invalid StarDict headword")
        }

        private fun copyBounded(input: InputStream, target: File, maxBytes: Long) {
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(32 * 1024)
                var copied = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    require(copied <= maxBytes)
                    output.write(buffer, 0, count)
                }
            }
        }
    }
}

internal fun dictionaryKey(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

internal fun decodeDefinition(bytes: ByteArray, sameTypes: String): String {
    val parts = mutableListOf<String>()
    var offset = 0
    var field = 0
    while (offset < bytes.size && (sameTypes.isEmpty() || field < sameTypes.length)) {
        val type = if (sameTypes.isEmpty()) bytes[offset++].toInt().toChar() else sameTypes[field]
        val last = sameTypes.isNotEmpty() && field == sameTypes.lastIndex
        if (type.isUpperCase() && !last) {
            if (offset + 4 > bytes.size) break
            val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
            if (length < 0 || length > bytes.size - offset) break
            offset += length
        } else {
            var end = offset
            if (last) end = bytes.size
            else while (end < bytes.size && bytes[end] != 0.toByte()) end++
            if (type in "mlgxtyh") {
                val text = String(bytes, offset, end - offset, Charsets.UTF_8).trimEnd('\u0000')
                parts.add(if (type in "ghx") Jsoup.parse(text).wholeText().trim() else text.trim())
            }
            offset = end + 1
        }
        field++
    }
    return parts.filter(String::isNotBlank).joinToString("\n\n")
}
