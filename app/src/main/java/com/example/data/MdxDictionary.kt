package com.example.data

import java.io.ByteArrayInputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.zip.Adler32
import java.util.zip.InflaterInputStream
import org.bouncycastle.crypto.digests.RIPEMD128Digest
import org.jsoup.parser.Parser

private const val MAX_BLOCK = 16 * 1024 * 1024
private const val MAX_KEYS = 1_000_000
private const val MAX_EXPANDED = 256L * 1024 * 1024
private data class MdxKey(val word: String, val offset: Long)
private data class MdxBlock(val compressed: Int, val expanded: Int)

internal fun extractMdxEntries(source: File, temporary: File, emit: (String, String) -> Unit): String =
    RandomAccessFile(source, "r").use { input ->
        val headerBytes = readMdxBytes(input, input.readInt().toLong(), 256 * 1024)
        require(input.readInt() == Integer.reverseBytes(adler(headerBytes)))
        val header = headerBytes.toString(Charsets.UTF_16LE).trim('\u0000', '\uFEFF')
        val attributes = Regex("""([\w-]+)\s*=\s*"([^"]*)"""").findAll(header)
            .associate { it.groupValues[1] to Parser.unescapeEntities(it.groupValues[2], true) }
        val version = attributes["GeneratedByEngineVersion"]?.toDoubleOrNull() ?: error("Missing MDict version")
        require(version >= 1 && version < 3)
        val modern = version >= 2
        val encryption = attributes["Encrypted"]?.let { if (it.equals("No", true)) 0 else it.toIntOrNull() ?: 1 } ?: 0
        require(encryption == 0 || modern && encryption == 2)
        val encoding = attributes["Encoding"].orEmpty().uppercase(java.util.Locale.ROOT)
        val charset = when (encoding) {
            "UTF-16", "UTF-16LE" -> Charsets.UTF_16LE
            "GB2312", "GBK" -> Charset.forName("GB18030")
            "" -> Charsets.UTF_8
            else -> Charset.forName(encoding)
        }
        val wide = charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE
        val headerStart = input.filePointer
        val keyBlockCount = input.mdxNumber(modern).boundedInt(MAX_KEYS)
        val keyCount = input.mdxNumber(modern).boundedInt(MAX_KEYS)
        require(keyCount > 0 && keyBlockCount > 0)
        val keyInfoSize = if (modern) input.mdxNumber(true).boundedInt(MAX_BLOCK) else 0
        val keyInfoCompressed = input.mdxNumber(modern)
        val keyDataLength = input.mdxNumber(modern)
        if (modern) {
            val expected = input.readInt()
            input.seek(headerStart)
            val checksumHeader = readMdxBytes(input, 40)
            require(adler(checksumHeader) == expected)
            input.skipBytes(4)
        }
        val indexBytes = readMdxBytes(input, keyInfoCompressed)
        if (encryption == 2) decodeMdxKeyInfo(indexBytes)
        val keyInfo = if (modern) unpackMdx(indexBytes, keyInfoSize) else indexBytes
        val keyBlocks = DataInputStream(ByteArrayInputStream(keyInfo)).use { index ->
            val blocks = List(keyBlockCount) {
                index.mdxNumber(modern)
                repeat(2) {
                    val count = if (modern) index.readUnsignedShort() + 1 else index.readUnsignedByte()
                    index.skipFully(count * if (wide) 2 else 1)
                }
                index.mdxBlock(modern)
            }
            require(index.read() == -1 && blocks.sumOf { it.compressed.toLong() } == keyDataLength)
            blocks
        }
        val keys = ArrayList<MdxKey>(keyCount.coerceAtMost(64_000))
        var lastOffset = 0L
        for (block in keyBlocks) {
            val decoded = unpackMdx(readMdxBytes(input, block.compressed.toLong()), block.expanded)
            val words = ByteBuffer.wrap(decoded)
            while (words.hasRemaining()) {
                require(keys.size < keyCount)
                val offset = if (modern) words.long else words.int.toLong() and 0xffffffffL
                require(offset in lastOffset..MAX_EXPANDED)
                lastOffset = offset
                val start = words.position()
                while (words.hasRemaining()) {
                    val first = words.get().toInt()
                    val second = if (wide) words.get().toInt() else 0
                    if (first == 0 && second == 0) break
                }
                val length = words.position() - start - if (wide) 2 else 1
                require(length in 1..2048)
                keys.add(MdxKey(String(decoded, start, length, charset), offset))
            }
        }
        require(keys.size == keyCount)
        val recordCount = input.mdxNumber(modern).boundedInt(MAX_KEYS)
        require(input.mdxNumber(modern) == keyCount.toLong())
        val recordIndexLength = input.mdxNumber(modern)
        val recordLength = input.mdxNumber(modern)
        require(recordIndexLength == recordCount.toLong() * if (modern) 16 else 8)
        val blocks = List(recordCount) { input.mdxBlock(modern) }
        require(blocks.sumOf { it.expanded.toLong() } <= MAX_EXPANDED)
        require(blocks.sumOf { it.compressed.toLong() } == recordLength)
        temporary.outputStream().buffered().use { out ->
            for (block in blocks) out.write(unpackMdx(readMdxBytes(input, block.compressed.toLong()), block.expanded))
        }
        RandomAccessFile(temporary, "r").use { records ->
            require(lastOffset <= records.length())
            var position = 0
            while (position < keys.size) {
                val start = keys[position].offset
                var next = position + 1
                while (next < keys.size && keys[next].offset == start) next++
                val end = keys.getOrNull(next)?.offset ?: records.length()
                val size = end - start
                if (size in 1..131_072L) {
                    records.seek(start)
                    val definition = readMdxBytes(records, size, 131_072).toString(charset).trimEnd('\u0000')
                    for (i in position until next) emit(keys[i].word, definition)
                }
                position = next
            }
        }
        attributes["Title"].orEmpty()
    }

private fun Long.boundedInt(limit: Int): Int = also { require(it in 0..limit.toLong()) }.toInt()
private fun DataInput.mdxNumber(modern: Boolean): Long = if (modern) readLong() else readInt().toLong() and 0xffffffffL
private fun DataInput.mdxBlock(modern: Boolean) =
    MdxBlock(mdxNumber(modern).boundedInt(MAX_BLOCK), mdxNumber(modern).boundedInt(MAX_BLOCK))

private fun DataInputStream.skipFully(size: Int) {
    require(skipBytes(size) == size)
}

private fun readMdxBytes(input: DataInput, count: Long, limit: Int = MAX_BLOCK): ByteArray =
    ByteArray(count.boundedInt(limit)).also(input::readFully)

private fun adler(bytes: ByteArray): Int = Adler32().apply { update(bytes) }.value.toInt()

private fun unpackMdx(block: ByteArray, expanded: Int): ByteArray {
    require(block.size >= 8)
    val decoded = when (ByteBuffer.wrap(block).int) {
        0 -> block.copyOfRange(8, block.size)
        0x02000000 -> InflaterInputStream(ByteArrayInputStream(block, 8, block.size - 8)).use {
            it.readImportBytes(expanded.coerceAtLeast(1))
        }
        else -> error("Unsupported MDict compression")
    }
    require(decoded.size == expanded && ByteBuffer.wrap(block, 4, 4).int == adler(decoded))
    return decoded
}

private fun decodeMdxKeyInfo(block: ByteArray) {
    require(block.size >= 8)
    val seed = block.copyOfRange(4, 8) + byteArrayOf(0x95.toByte(), 0x36, 0, 0)
    val digest = RIPEMD128Digest()
    digest.update(seed, 0, seed.size)
    val key = ByteArray(digest.digestSize).also { digest.doFinal(it, 0) }
    var previous = 0x36
    for (offset in 8 until block.size) {
        val index = offset - 8
        val value = block[offset].toInt() and 255
        val nibbles = (value ushr 4) or (value shl 4)
        block[offset] = (nibbles xor previous xor index xor key[index % key.size].toInt()).toByte()
        previous = value
    }
}
