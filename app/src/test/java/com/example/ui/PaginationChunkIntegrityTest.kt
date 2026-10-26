package com.example.ui

import com.example.ui.reader.NovelInlineImages
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random
import kotlinx.coroutines.CancellationException

class PaginationChunkIntegrityTest {
    private fun chunks(text: String, size: Int) = paginationChunkRanges(text, size).map {
        text.substring(it.first, it.last + 1)
    }.toList()

    @Test fun newlineBoundaryRetainsEveryCharacterAndReadingOffset() {
        val text = (1..10_000).joinToString("\r\n") { "第 $it 行正文内容。" }
        val parts = chunks(text, 40_000)
        assertTrue(parts.size > 1)
        assertEquals(text, parts.joinToString(""))
        val anchors = ReaderPageAnchors(parts)
        var start = 0
        parts.forEachIndexed { index, part ->
            assertEquals(start, anchors.startOf(index))
            assertEquals(index, anchors.pageAt(start))
            start += part.length
        }
        assertEquals(text.length, start)
    }

    @Test fun hardCutsKeepSurrogatePairsAndCrLfTogetherEvenWithTinyChunks() {
        val text = "甲😀乙\r\n𠮷末尾".repeat(100)
        for (size in 1..30) {
            val parts = chunks(text, size)
            assertEquals(text, parts.joinToString(""))
            parts.zipWithNext().forEach { (left, right) ->
                assertFalse(left.last().isHighSurrogate() && right.first().isLowSurrogate())
                assertFalse(left.last() == '\r' && right.first() == '\n')
            }
        }
    }

    @Test fun chunkBoundaryNeverSplitsValidImageTokensIncludingOversizedPaths() {
        val tokens = listOf("[IMG:/a/picture.png|800|1600]", "[IMG:/" + "long".repeat(30) + ".png|10|20]")
        val text = "正文\n".repeat(13) + tokens[0] + "后文".repeat(10) + tokens[1] + "完"
        for (size in 1..80) {
            val parts = chunks(text, size)
            assertEquals(text, parts.joinToString(""))
            for (token in tokens) assertEquals(1, parts.count { token in it })
            assertEquals(tokens, parts.flatMap { NovelInlineImages.TOKEN_REGEX.findAll(it).map { m -> m.value }.toList() })
        }
    }

    @Test fun randomChunkSizesRetainExactUtf16AndNeverEmitEmptyChunks() {
        val random = Random(20261026)
        val atoms = listOf("中文", "abc", "\n", "\r\n", "😀", "𠮷", "[IMG:/p.png|10|20]", "[IMG:invalid]")
        repeat(200) {
            val text = buildString { repeat(random.nextInt(1, 300)) { append(atoms[random.nextInt(atoms.size)]) } }
            val parts = chunks(text, random.nextInt(1, 300))
            assertTrue(parts.all { it.isNotEmpty() })
            assertEquals(text, parts.joinToString(""))
        }
        assertTrue(chunks("", 40_000).isEmpty())
    }
    @Test fun linearImageGuardMatchesTheExistingProtocolForValidAndMalformedInput() {
        val cases = listOf(
            "[IMG:/a.png|100|200]", "[IMG:a[IMG:b|1|2]", "[IMG:|1|2]", "[IMG:a|x|2]",
            "[IMG:a|1|[IMG:b|2|3]", "[IMG:a|[IMG:b|2|3]", "[IMG:a]x[IMG:b|2|3]",
            "[IMG:a|１２|34]", "[IMG:a|1|2]".repeat(5), "[IMG:a\nb|00001|00002]"
        )
        val random = Random(827)
        val atoms = cases + listOf("[IMG:", "\n", "]", "|", "[", "1", "abc", "IMG:", "😀")
        val corpus = cases + List(300) {
            buildString { repeat(random.nextInt(1, 40)) { append(atoms[random.nextInt(atoms.size)]) } }
        }
        for (text in corpus) {
            assertEquals("Token ranges for $text", NovelInlineImages.TOKEN_REGEX.findAll(text).map { it.range }.toList(),
                paginationImageTokenRanges(text).toList())
        }
    }

    @Test fun damagedImageMarkersScanLinearlyAndCanBeCancelledBeforeLayout() {
        fun checksFor(repeats: Int): Int {
            var checks = 0
            val text = "[IMG:".repeat(repeats)
            assertTrue(paginationImageTokenRanges(text) { checks++ }.none())
            return checks
        }
        val small = checksFor(20_000)
        val large = checksFor(80_000)
        assertTrue("Periodic scan work must grow linearly: $small -> $large", large <= small * 4 + 4)
        var checks = 0
        try {
            paginationChunkRanges("[IMG:".repeat(100_000), cancellationCheck = {
                if (++checks == 10) throw CancellationException("latest window wins")
            }).toList()
            fail("Damaged marker scan must honor cancellation")
        } catch (expected: CancellationException) {
            assertEquals(10, checks)
        }
    }

}
