package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.example.ui.reader.NovelInlineImages
import com.example.ui.theme.AppFonts
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * ANR-free pagination layer backed by real text layout.
 *
 * Every page break is a real line boundary produced by [Paragraph] (the same layout
 * engine Compose's Text uses), so a paragraph split across two pages never loses a line
 * and the last line of a page is always fully visible. Measurement runs on
 * [Dispatchers.Default]; very large chapters are split into ~40k-char chunks, each chunk
 * is measured independently and pages are appended as chunks finish so the first pages
 * appear almost immediately.
 */
object ReaderPaginationCache {
    private val lock = Any()
    private data class Entry(val pages: List<String>, val bytes: Long)
    private val entries = LinkedHashMap<PaginationKey, Entry>(16, 0.75f, true)
    private const val MAX_ENTRIES = 8
    internal const val MAX_BYTES = 8L * 1024 * 1024
    private var bytes = 0L

    fun get(key: PaginationKey): List<String>? = synchronized(lock) { entries[key]?.pages }

    fun put(key: PaginationKey, pages: List<String>) {
        synchronized(lock) {
            // Account for both chapter text and page strings, plus conservative object overhead.
            val size = key.content.length * 2L + pages.sumOf { it.length * 2L + 40L } + 128L
            if (size > MAX_BYTES) return
            bytes -= entries.remove(key)?.bytes ?: 0L
            entries[key] = Entry(pages, size)
            bytes += size
            while (entries.size > MAX_ENTRIES || bytes > MAX_BYTES) {
                val oldest = entries.keys.firstOrNull() ?: break
                bytes -= entries.remove(oldest)!!.bytes
            }
        }
    }

    fun clear() = synchronized(lock) { entries.clear(); bytes = 0L }
    internal fun retainedBytes(): Long = synchronized(lock) { bytes }
}

data class PaginationKey(
    val content: String,
    val widthPx: Int,
    val heightPx: Int,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val fontFamily: FontFamily,
    val includeFontPadding: Boolean,
    val titleReservePx: Int,
    // Opaque, optional environment retains source compatibility with existing cache callers.
    val layoutEnvironment: Any? = null
)

/** Draw-only color/shadow changes must not discard useful text layout work. */
internal class PaginationTextLayout(private val style: TextStyle) {
    override fun equals(other: Any?): Boolean = other is PaginationTextLayout &&
        style.hasSameLayoutAffectingAttributes(other.style)

    override fun hashCode(): Int {
        // A layout-only subset is sufficient for hashing; equals checks every layout field.
        var result = style.fontSize.hashCode()
        result = 31 * result + style.lineHeight.hashCode()
        result = 31 * result + (style.fontFamily?.hashCode() ?: 0)
        result = 31 * result + (style.fontWeight?.hashCode() ?: 0)
        result = 31 * result + style.letterSpacing.hashCode()
        return result
    }
}

internal data class PaginationLayoutEnvironment(
    val textLayout: PaginationTextLayout,
    val density: Float,
    val fontScale: Float,
    val fontResolverToken: Any
)

/** Stable identity without making cache entries retain an Activity's font resolver. */
internal object PaginationFontEnvironments {
    private class Entry(val resolver: WeakReference<Any>, val token: Any)
    private val entries = ArrayList<Entry>()

    @Synchronized fun tokenFor(resolver: Any): Any {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val retained = entry.resolver.get()
            if (retained == null) iterator.remove()
            else if (retained === resolver) return entry.token
        }
        // Identity metadata is bounded too; eviction causes only a future cache miss.
        if (entries.size >= 64) entries.removeAt(0)
        return Any().also { entries.add(Entry(WeakReference(resolver), it)) }
    }
}

/** Vertical padding applied by RenderSinglePage around the page content (12dp x 2). */
internal const val PAGE_VERTICAL_PADDING_DP = 12

/** Bottom padding of the body Text inside RenderSinglePage. */
internal const val PAGE_TEXT_BOTTOM_PADDING_DP = 16

/** 渐进分页尚未覆盖整章时，当前“最后一页”只是临时末尾，不能触发全书完成。 */
internal fun shouldCelebrateAfterForwardTurn(
    chapterIndex: Int,
    chapterCount: Int,
    pageIndex: Int,
    pages: List<String>,
    formattedLength: Int
): Boolean = chapterCount > 0 && chapterIndex == chapterCount - 1 && pages.isNotEmpty() &&
    pageIndex >= pages.lastIndex - 1 && pages.sumOf { it.length } == formattedLength

/** Vertical padding around the chapter title block (8dp top + 12dp bottom). */
internal const val TITLE_BLOCK_PADDING_DP = 20

/** Chapters larger than this are paginated chunk-by-chunk instead of in one pass. */
internal const val LARGE_CHAPTER_THRESHOLD = 200_000

/** Chunk size used for large chapters (measured once per chunk, ~40k chars). */
private const val PAGINATION_CHUNK_CHARS = 40_000

/** Chapters up to this size are paginated in one pass and published at once. */
private const val EAGER_CHAPTER_THRESHOLD = 60_000

/** One chapter, at most one old layout and one pending layout; stale workers cannot publish. */
internal class ChapterPageBuffer(initialKey: PaginationKey, initialPages: List<String>) {
    private val lock = Any()
    private var requestedKey = initialKey
    private var displayedKey = initialKey
    var pages: List<String> by mutableStateOf(initialPages)
        private set

    fun request(key: PaginationKey) = synchronized(lock) { requestedKey = key }
    fun publish(key: PaginationKey, result: List<String>, complete: Boolean = true): Boolean = synchronized(lock) {
        if (requestedKey != key) return false
        // A partial resized layout may not yet contain the reader's current character anchor.
        // Keep the old layout until the complete replacement covers the entire chapter.
        if (!complete && displayedKey != key && pages.isNotEmpty()) return false
        pages = result
        displayedKey = key
        true
    }
}

internal fun pageIndexAtOffset(pages: List<String>, offset: Int): Int {
    var accumulated = 0
    for (index in pages.indices) {
        accumulated += pages[index].length
        if (accumulated > offset || index == pages.lastIndex) return index
    }
    return 0
}

/** Prefix offsets are built once per publication; page turns are O(1), re-anchors O(log n). */
internal class ReaderPageAnchors(pages: List<String>) {
    private val ends = IntArray(pages.size)
    init {
        var offset = 0
        for (index in pages.indices) {
            offset += pages[index].length
            ends[index] = offset
        }
    }
    fun pageAt(offset: Int): Int {
        if (ends.isEmpty()) return 0
        var low = 0
        var high = ends.lastIndex
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] > offset) high = middle else low = middle + 1
        }
        return low
    }
    fun startOf(page: Int): Int = if (page <= 0 || ends.isEmpty()) 0 else ends[(page - 1).coerceAtMost(ends.lastIndex)]
}

@Composable
fun rememberChapterPages(
    content: String,
    widthPx: Int,
    heightPx: Int,
    bodyStyle: TextStyle,
    titleReservePx: Int,
    isScrollMode: Boolean
): List<String> {
    if (isScrollMode || content.isEmpty() || widthPx <= 20 || heightPx <= 20) {
        return listOf(content)
    }

    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val fontSizePx = with(density) { bodyStyle.fontSize.toPx() }.coerceAtLeast(8f)
    // 缓存必须使用实际行高；小于 1.2 倍字号的不同设置也会产生不同排版。
    val lineHeightPx = with(density) { bodyStyle.lineHeight.toPx() }
    val environment = remember(bodyStyle, density, fontFamilyResolver) {
        PaginationLayoutEnvironment(
            PaginationTextLayout(bodyStyle), density.density, density.fontScale,
            PaginationFontEnvironments.tokenFor(fontFamilyResolver)
        )
    }
    val key = PaginationKey(
        content = content,
        widthPx = widthPx,
        heightPx = heightPx,
        fontSizePx = fontSizePx,
        lineHeightPx = lineHeightPx,
        fontFamily = bodyStyle.fontFamily ?: AppFonts.Default,
        includeFontPadding = false,
        titleReservePx = titleReservePx,
        layoutEnvironment = environment
    )

    val cached = remember(key) { ReaderPaginationCache.get(key) }
    // A different chapter must never inherit the previous chapter's retained text.
    val buffer = remember(content) { ChapterPageBuffer(key, cached ?: emptyList()) }
    SideEffect { buffer.request(key) }

    LaunchedEffect(key) {
        if (cached != null) {
            buffer.publish(key, cached)
            return@LaunchedEffect
        }

        val params = LayoutParams(
            widthPx = widthPx,
            pageHeightPx = heightPx,
            firstPageHeightPx = (heightPx - titleReservePx).coerceAtLeast(80),
            bodyStyle = bodyStyle,
            fontSizePx = fontSizePx,
            lineHeightPx = lineHeightPx,
            density = density,
            fontFamilyResolver = fontFamilyResolver
        )

        if (content.length <= EAGER_CHAPTER_THRESHOLD) {
            val computed = withContext(Dispatchers.Default) {
                val computation = currentCoroutineContext()
                paginateChunked(content, params, progressiveSink = null, cancellationCheck = { computation.ensureActive() })
            }
            ReaderPaginationCache.put(key, computed)
            buffer.publish(key, computed)
        } else {
            // Very large chapters: measure the first chunk right away so the reader gets
            // pages almost instantly, then append the remaining chunks in the background.
            val full = withContext(Dispatchers.Default) {
                val computation = currentCoroutineContext()
                paginateChunked(content, params, progressiveSink = { partial ->
                    computation.ensureActive()
                    buffer.publish(key, partial, complete = false)
                }, cancellationCheck = { computation.ensureActive() })
            }
            ReaderPaginationCache.put(key, full)
            buffer.publish(key, full)
        }
    }

    return cached ?: buffer.pages
}

private class LayoutParams(
    val widthPx: Int,
    val pageHeightPx: Int,
    val firstPageHeightPx: Int,
    val bodyStyle: TextStyle,
    val fontSizePx: Float,
    val lineHeightPx: Float,
    val density: androidx.compose.ui.unit.Density,
    val fontFamilyResolver: androidx.compose.ui.text.font.FontFamily.Resolver
)

/**
 * Splits [content] into ~40k-char chunks (preferring newline boundaries) and paginates
 * each chunk with a real [Paragraph] layout, appending pages so the concatenation is
 * byte-for-byte identical to [content].
 *
 * When [progressiveSink] is provided it receives intermediate chunks. The caller publishes
 * the returned complete list once, without copying and publishing the last chunk twice.
 */
private fun paginateChunked(
    content: String,
    params: LayoutParams,
    progressiveSink: ((List<String>) -> Unit)?,
    cancellationCheck: () -> Unit
): List<String> {
    cancellationCheck()
    val allPages = mutableListOf<String>()
    for (range in paginationChunkRanges(content, cancellationCheck = cancellationCheck)) {
        cancellationCheck()
        val chunk = content.substring(range.first, range.last + 1)
        allPages += paginateChunk(chunk, params, reserveTitle = range.first == 0, cancellationCheck = cancellationCheck)
        // The original <=40k path returned directly; it does not need a duplicate snapshot.
        if (range.last < content.lastIndex) progressiveSink?.invoke(allPages.toList())
    }
    return allPages
}

/**
 * Lazy ranges avoid retaining a second copy of the entire chapter before measurement.
 * Newline cuts include the newline; hard cuts keep UTF-16 pairs, CRLF and image tokens
 * whole so concatenated pages retain the original text and reading offsets.
 */
internal fun paginationChunkRanges(
    content: String,
    maxChars: Int = PAGINATION_CHUNK_CHARS,
    cancellationCheck: () -> Unit = {}
): Sequence<IntRange> = sequence {
    require(maxChars > 0)
    cancellationCheck()
    val tokens = if (NovelInlineImages.hasImages(content)) {
        paginationImageTokenRanges(content, cancellationCheck).iterator()
    } else null
    var token = if (tokens?.hasNext() == true) tokens.next() else null
    var start = 0
    while (start < content.length) {
        cancellationCheck()
        var end = start + minOf(content.length - start, maxChars)
        if (end < content.length) {
            // Search only this chunk's latter half, never rescan earlier chapter text.
            val earliestNewline = start + maxChars / 2
            var newline = end
            while (newline >= earliestNewline && content[newline] != '\n') {
                if ((newline and 1023) == 0) cancellationCheck()
                newline--
            }
            if (newline >= earliestNewline) end = newline + 1
            while (token != null && token.last < start) {
                token = if (tokens?.hasNext() == true) tokens.next() else null
            }
            while (token != null && token.first < end) {
                if (token.last >= end) {
                    end = if (token.first > start) token.first else token.last + 1
                    break
                }
                token = if (tokens?.hasNext() == true) tokens.next() else null
            }
            if (end < content.length && end > start &&
                ((content[end - 1].isHighSurrogate() && content[end].isLowSurrogate()) ||
                    (content[end - 1] == '\r' && content[end] == '\n'))
            ) {
                end = if (end - 1 > start) end - 1 else end + 1
            }
        }
        yield(start until end)
        start = end
    }
}

/**
 * Linear equivalent of TOKEN_REGEX for chunk-boundary protection only. A failed path
 * candidate and prefixes nested in that path share the same first delimiter and failure;
 * resume at that failure instead of backtracking over the same long malformed path.
 * The image parsing/rendering protocol continues to use NovelInlineImages.TOKEN_REGEX.
 */
internal fun paginationImageTokenRanges(
    content: String,
    cancellationCheck: () -> Unit = {}
): Sequence<IntRange> = sequence {
    var search = 0
    while (search <= content.length - 5) {
        cancellationCheck()
        var start = search
        while (start <= content.length - 5 && !content.regionMatches(start, "[IMG:", 0, 5)) {
            if ((start and 1023) == 0) cancellationCheck()
            start++
        }
        if (start > content.length - 5) break
        var cursor = start + 5
        val pathStart = cursor
        while (cursor < content.length && content[cursor] != '|' && content[cursor] != ']') {
            if ((cursor and 1023) == 0) cancellationCheck()
            cursor++
        }
        if (cursor == content.length) break
        if (cursor == pathStart || content[cursor] != '|') {
            search = cursor
            continue
        }
        cursor++
        val widthStart = cursor
        while (cursor < content.length && content[cursor] in '0'..'9') {
            if ((cursor and 1023) == 0) cancellationCheck()
            cursor++
        }
        if (cursor == content.length) break
        if (cursor == widthStart || content[cursor] != '|') {
            search = cursor
            continue
        }
        cursor++
        val heightStart = cursor
        while (cursor < content.length && content[cursor] in '0'..'9') {
            if ((cursor and 1023) == 0) cancellationCheck()
            cursor++
        }
        if (cursor == content.length) break
        if (cursor == heightStart || content[cursor] != ']') {
            search = cursor
            continue
        }
        yield(start..cursor)
        search = cursor + 1
    }
}

/** Primitive line metadata: the source text already owns every character. */
private class PaginationItems(initialCapacity: Int) {
    private var ends = IntArray(initialCapacity)
    private var heights = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(end: Int, height: Float) {
        if (size == ends.size) {
            ends = ends.copyOf(size * 2)
            heights = heights.copyOf(size * 2)
        }
        ends[size] = end
        heights[size] = height
        size++
    }

    fun endAt(index: Int): Int = ends[index]
    fun heightAt(index: Int): Float = heights[index]
}

/**
 * Real-layout pagination for one chunk. Page breaks are line boundaries reported by
 * [Paragraph], so no line is ever clipped or lost between pages.
 *
 * 内嵌图片走块驱动：图片按长宽比从文本流中拆出、按 (宽, 收敛后的高) 独立占空间，
 * 当前页剩余空间不足时断页 —— 不再依赖 ParagraphIntrinsics 的 placeholder
 * （大图场景测量与渲染不一致，会导致叠绘）。
 *
 * @param reserveTitle whether the first page of this chunk must reserve the chapter
 *   title block (only the very first chunk of a chapter).
 */
private fun paginateChunk(
    chunk: String,
    params: LayoutParams,
    reserveTitle: Boolean,
    cancellationCheck: () -> Unit
): List<String> {
    // Keep exact source boundaries instead of allocating an Item and substring per line.
    val items = PaginationItems((chunk.length / 32 + 1).coerceIn(1, 128))
    var blockStart = 0
    NovelInlineImages.splitIntoBlocks(chunk, params.widthPx.toFloat(), params.pageHeightPx.toFloat())
        .forEach { block ->
            cancellationCheck()
            when (block) {
                is NovelInlineImages.InlineBlock.Image -> {
                    // 图片块：高度 = displaySize 收敛值（不超过页高），整块不可分割
                    val h = block.heightPx.coerceAtMost(params.pageHeightPx.toFloat()).coerceAtLeast(1f)
                    items.add(blockStart + block.raw.length, h)
                }
                is NovelInlineImages.InlineBlock.Text -> {
                    if (block.text.isEmpty()) return@forEach
                    // 纯文本段：真实排版后按行拆成原子项（行不可跨页）
                    val paragraph = buildParagraph(block.text, params)
                    val lineCount = paragraph.lineCount
                    if (lineCount == 0) {
                        items.add(blockStart + block.text.length, 1f)
                        blockStart += block.raw.length
                        return@forEach
                    }
                    for (i in 0 until lineCount) {
                        if ((i and 127) == 0) cancellationCheck()
                        val lineH = (paragraph.getLineBottom(i) - paragraph.getLineTop(i)).coerceAtLeast(1f)
                        val start = paragraph.getLineStart(i)
                        val end = paragraph.getLineEnd(i)
                        // 末尾换行会产生没有字符的空行，其高度由页级复测计入。
                        if (start == end) continue
                        items.add(blockStart + end, lineH)
                    }
                }
            }
            blockStart += block.raw.length
        }

    if (items.size == 0) return if (chunk.isEmpty()) emptyList() else listOf(chunk)

    // 行高只用于估算断点。小行距下，整章的中间行成为独立页的首尾行时，
    // Paragraph 会补足字形边界；末尾换行也会多出一行，必须按页重新测量。
    val pages = mutableListOf<String>()
    var startItem = 0
    var startChar = 0
    while (startItem < items.size) {
        cancellationCheck()
        // 标题预留必须用于首页所有行，不能只在追加第一项时生效。
        val pageHeight = if (reserveTitle && pages.isEmpty()) {
            params.firstPageHeightPx.toFloat()
        } else {
            params.pageHeightPx.toFloat()
        }

        var endItem = startItem
        var estimatedHeight = 0f
        while (endItem < items.size) {
            val nextHeight = estimatedHeight + items.heightAt(endItem)
            if (endItem > startItem && nextHeight > pageHeight) break
            estimatedHeight = nextHeight
            endItem++
        }

        fun fits(end: Int): Boolean {
            cancellationCheck()
            return measurePageHeightPx(
                chunk.substring(startChar, items.endAt(end - 1)), params
            ) <= pageHeight
        }

        if (endItem > startItem + 1 && !fits(endItem)) {
            // 在真实行/图边界二分回退，避免每加一行都排版整页。
            // 单项超过可用高度时仍保留该项，确保不丢字且分页能继续。
            var low = startItem + 1
            var high = endItem - 1
            while (low < high) {
                val middle = low + (high - low + 1) / 2
                if (fits(middle)) low = middle else high = middle - 1
            }
            endItem = low
        }

        val endChar = items.endAt(endItem - 1)
        pages.add(chunk.substring(startChar, endChar))
        startChar = endChar
        startItem = endItem
    }

    return pages
}

/** 与 RenderSinglePage 的块布局一致，计入首尾字体边界、末尾空行和像素取整。 */
private fun measurePageHeightPx(text: String, params: LayoutParams): Float =
    NovelInlineImages.splitIntoBlocks(text, params.widthPx.toFloat(), params.pageHeightPx.toFloat())
        .sumOf { block ->
            when (block) {
                is NovelInlineImages.InlineBlock.Text -> ceil(buildParagraph(block.text, params).height).toInt()
                is NovelInlineImages.InlineBlock.Image -> block.heightPx.roundToInt()
            }
        }.toFloat()

/**
 * Builds a [Paragraph] that lays out [text] exactly like RenderSinglePage's Text:
 * same TextStyle (fontFamily / fontSize / lineHeight / includeFontPadding), same width
 * and same density.
 *
 * Image tokens have already been split into independently sized blocks by both callers.
 */
private fun buildParagraph(text: String, params: LayoutParams): Paragraph {
    // Both callers already split valid image tokens into independent image blocks.
    // Literal malformed markers remain ordinary text, with unchanged UTF-16 offsets.
    val intrinsics = ParagraphIntrinsics(
        text = text,
        style = params.bodyStyle,
        spanStyles = emptyList(),
        placeholders = emptyList(),
        density = params.density,
        fontFamilyResolver = params.fontFamilyResolver
    )
    return Paragraph(
        paragraphIntrinsics = intrinsics,
        constraints = Constraints(maxWidth = params.widthPx)
    )
}
