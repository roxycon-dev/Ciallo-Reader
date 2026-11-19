package com.example.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import org.jsoup.Jsoup

internal fun extractMobiEntries(source: File, emit: (String, String) -> Unit): String {
    val parsed = requireNotNull(MobiParser.parseMobi(source.inputStream().use { it.readImportBytes() }, forDictionary = true))
    require(parsed.encryptionType == 0 && parsed.failureReason == null)
    val raw = requireNotNull(parsed.dictionaryText)
    val metadata = parsed.records.getOrNull(parsed.orthRecord)
    if (metadata != null) {
        readMobiOrthIndex(parsed, raw, metadata, emit)
    } else {
        val html = Jsoup.parse(parsed.html.orEmpty())
        for (node in html.getAllElements().filter { it.normalName().substringAfter(':') == "entry" }) {
            val headword = node.getAllElements().firstOrNull { it.normalName().substringAfter(':') == "orth" } ?: continue
            val word = headword.attr("value").ifBlank { headword.text() }
            emit(word, node.html())
            for (inflection in node.getAllElements().filter { it.normalName().substringAfter(':') == "iform" }) {
                val alias = inflection.attr("value")
                if (alias.isNotBlank()) emit(alias, node.html())
            }
        }
    }
    return parsed.title
}

private data class OrthTag(val id: Int, val values: Int, val mask: Int, val separator: Boolean)
private data class OrthValue(val tag: OrthTag, val amount: Int, val bytes: Boolean)

private fun readMobiOrthIndex(parsed: MobiParser.ParsedMobi, raw: ByteArray, meta: ByteArray, emit: (String, String) -> Unit) {
    val header = ByteBuffer.wrap(meta)
    require(header.int == 0x494e4458)
    val tagStart = header.getInt(4)
    val recordCount = header.getInt(24)
    val entryCount = header.getInt(36)
    val labelCharset = mobiDictionaryEncoding(header.getInt(28).let { if (it == -1) 1252 else it })
    val bodyCharset = mobiDictionaryEncoding(parsed.dictionaryEncoding)
    require(recordCount in 1..(parsed.records.lastIndex - parsed.orthRecord) && entryCount in 1..1_000_000)
    require(tagStart in 52..(meta.size - 12) && header.getInt(tagStart) == 0x54414758)
    val tagLength = header.getInt(tagStart + 4)
    val controlCount = header.getInt(tagStart + 8)
    require(tagLength in 12..(meta.size - tagStart) && controlCount in 1..32 && (tagLength - 12) % 4 == 0)
    val tags = (tagStart + 12 until tagStart + tagLength step 4).map {
        OrthTag(meta[it].toInt() and 255, meta[it + 1].toInt() and 255,
            meta[it + 2].toInt() and 255, meta[it + 3].toInt() == 1)
    }
    var observed = 0
    for (record in parsed.records.subList(parsed.orthRecord + 1, parsed.orthRecord + 1 + recordCount)) {
        val index = ByteBuffer.wrap(record)
        require(index.int == 0x494e4458)
        val idxt = index.getInt(20)
        val count = index.getInt(24)
        require(count in 0..minOf(entryCount - observed, record.size / 2))
        require(idxt >= 28 && idxt.toLong() + 4 + count * 2L <= record.size && index.getInt(idxt) == 0x49445854)
        fun position(i: Int) = if (i == count) idxt else index.getShort(idxt + 4 + i * 2).toInt() and 65535
        repeat(count) { item ->
            val start = position(item)
            val end = position(item + 1)
            require(start in 28 until end && end <= idxt)
            val entry = ByteBuffer.wrap(record, start, end - start).slice()
            val length = entry.get().toInt() and 255
            val word = ByteArray(length).also(entry::get).toString(labelCharset)
            val control = ByteArray(controlCount).also(entry::get)
            var controlByte = 0
            val values = buildList {
                for (tag in tags) {
                    if (tag.separator) { controlByte++; continue }
                    val mask = tag.mask
                    val bits = control[controlByte].toInt() and mask
                    if (bits == 0) continue
                    val byteCount = bits == mask && Integer.bitCount(mask) > 1
                    val amount = if (byteCount) entry.mobiVariableInt() else bits ushr Integer.numberOfTrailingZeros(mask)
                    add(OrthValue(tag, amount, byteCount))
                }
            }
            var offset = -1
            var size = 0
            for (value in values) {
                val vStart = entry.position()
                var first = -1
                if (value.bytes) {
                    require(value.amount <= entry.remaining())
                    while (entry.position() - vStart < value.amount) {
                        val number = entry.mobiVariableInt()
                        if (first < 0) first = number
                    }
                    require(entry.position() - vStart == value.amount)
                } else {
                    val length = value.amount * value.tag.values
                    require(length <= entry.remaining())
                    repeat(length) {
                        val number = entry.mobiVariableInt()
                        if (it == 0) first = number
                    }
                }
                when (value.tag.id) {
                    1 -> offset = first
                    2 -> size = first
                }
            }
            if (offset >= 0 && size in 1..131_072 && offset <= raw.size - size && word.isNotBlank()) {
                emit(word, String(raw, offset, size, bodyCharset))
            }
        }
        observed += count
    }
    require(observed == entryCount)
}

private fun mobiDictionaryEncoding(id: Int): Charset = when (id) {
    65001 -> Charsets.UTF_8
    65002 -> Charsets.UTF_16LE
    1252 -> Charset.forName("windows-1252")
    else -> error("Unsupported MOBI dictionary character set")
}

private fun ByteBuffer.mobiVariableInt(): Int {
    var value = 0L
    repeat(5) {
        val byte = get().toInt() and 255
        value = (value shl 7) or (byte and 127).toLong()
        if (byte and 128 != 0) {
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }
    }
    error("Invalid MOBI index integer")
}
