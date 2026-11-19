package com.example.ui.reader

import android.content.Context
import android.graphics.Rect
import android.view.ActionMode
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.BookTextSpan
import com.example.data.BookTextStyle
import com.example.data.spansIn
import com.example.i18n.tr
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.jsoup.nodes.Entities

private const val verticalOrigin = "https://reader.ciallo.invalid/"
private const val selectionItem = 60129

private class VerticalDocument(val html: String, val images: List<String>, val text: String)

@Composable
internal fun VerticalNovelBody(
    title: String,
    text: String,
    spans: List<BookTextSpan>,
    marks: List<ReaderMark>,
    mode: String,
    fontSize: Float,
    lineHeight: Float,
    foreground: Color,
    background: Color,
    offset: Int,
    highlight: Int?,
    onOffset: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    onSelect: (ReaderTextSelection) -> Unit,
    onImage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val document = remember(title, text, spans, marks, mode, fontSize, lineHeight, foreground, background) {
        verticalDocument(title, text, spans, marks, mode, fontSize, lineHeight, foreground, background)
    }
    AndroidView(
        factory = { VerticalTextView(it) },
        modifier = modifier,
        update = { view ->
            view.setBackgroundColor(background.toArgb())
            view.onOffset = onOffset
            view.onToggleChrome = onToggleChrome
            view.onSelect = onSelect
            view.onImage = onImage
            view.updateDocument(document, offset, highlight)
        },
        onRelease = { view -> view.dispose() }
    )
}

private class VerticalTextView(context: Context) : WebView(context) {
    var onOffset: (Int) -> Unit = {}
    var onToggleChrome: () -> Unit = {}
    var onSelect: (ReaderTextSelection) -> Unit = {}
    var onImage: (String) -> Unit = {}
    @Volatile private var document: VerticalDocument? = null
    @Volatile private var requestedUrl = ""
    private var generation = 0L
    private var loaded = false
    private var offset = 0
    private var highlight: Int? = null
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            evaluateJavascript("window.getSelection().isCollapsed") { collapsed ->
                if (collapsed == "true") onToggleChrome()
            }
            return true
        }
    })

    init {
        @Suppress("SetJavaScriptEnabled")
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.useWideViewPort = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        overScrollMode = View.OVER_SCROLL_NEVER
        isHorizontalScrollBarEnabled = false
        // The bridge receives only an offset from the generated reader document.
        addJavascriptInterface(object {
            @JavascriptInterface
            fun report(url: String, position: Int) {
                post {
                    if (loaded && url == requestedUrl && position != offset &&
                        position in 0..(document?.text?.length ?: -1)) {
                        offset = position
                        onOffset(position)
                    }
                }
            }
        }, "ReaderPosition")
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val id = request.url.path?.removePrefix("/open/")?.toIntOrNull()
                if (request.url.host == "reader.ciallo.invalid" && id != null) {
                    document?.images?.getOrNull(id)?.let(onImage)
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val source = document ?: return emptyResponse()
                if (request.url.scheme != "https" || request.url.host != "reader.ciallo.invalid") return emptyResponse()
                if (request.url.toString() == requestedUrl) {
                    return WebResourceResponse("text/html", "UTF-8", ByteArrayInputStream(source.html.toByteArray(Charsets.UTF_8)))
                }
                val id = request.url.path?.removePrefix("/image/")?.toIntOrNull() ?: return emptyResponse()
                val image = source.images.getOrNull(id) ?: return emptyResponse()
                return imageResponse(image) ?: emptyResponse()
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (url != requestedUrl) return
                loaded = true
                jump(offset)
                highlightParagraph(highlight)
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    fun updateDocument(value: VerticalDocument, requestedOffset: Int, spoken: Int?) {
        if (document !== value) {
            document = value
            offset = requestedOffset
            highlight = spoken
            loaded = false
            requestedUrl = verticalOrigin + "document/" + (++generation)
            loadUrl(requestedUrl)
        } else {
            if (requestedOffset != offset) {
                offset = requestedOffset
                if (loaded) jump(requestedOffset)
            }
            if (spoken != highlight) {
                highlight = spoken
                if (loaded) highlightParagraph(spoken)
            }
        }
    }

    private fun jump(index: Int) {
        evaluateJavascript("locate(" + index.coerceAtLeast(0) + ")") { }
    }

    private fun highlightParagraph(index: Int?) {
        evaluateJavascript("speakAt(" + (index ?: -1) + ")") { }
    }

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(selectionCallback(callback), type)

    private fun selectionCallback(callback: ActionMode.Callback) = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!callback.onCreateActionMode(mode, menu)) return false
            menu.add(0, selectionItem, 0, tr("查词与批注"))
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = callback.onPrepareActionMode(mode, menu)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId != selectionItem) return callback.onActionItemClicked(mode, item)
            evaluateJavascript("readSelection()") { result ->
                val selected = runCatching { JSONObject(result) }.getOrNull()
                val text = document?.text.orEmpty()
                val start = selected?.optInt("start", -1) ?: -1
                val end = selected?.optInt("end", -1) ?: -1
                if (start >= 0 && end in (start + 1)..text.length) {
                    val windowStart = (start - 180).coerceAtLeast(0)
                    val windowEnd = (end + 380).coerceAtMost(text.length)
                    onSelect(ReaderTextSelection(text.substring(windowStart, windowEnd),
                        start - windowStart, end - windowStart, windowStart))
                }
                mode.finish()
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (callback is ActionMode.Callback2) callback.onGetContentRect(mode, view, outRect)
            else super.onGetContentRect(mode, view, outRect)
        }
    }

    private fun emptyResponse() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun imageResponse(path: String): WebResourceResponse? = runCatching {
        val file = File(path)
        val bytes = if (path.startsWith("epzip:")) NovelInlineImages.NovelImageCache.readEpubImageBytes(path)
            else file.takeIf { it.isFile && it.length() <= 16L * 1024 * 1024 }?.readBytes()
        if (bytes == null || bytes.size > 16 * 1024 * 1024) return null
        val format = when (path.substringAfterLast('.').lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> "image/jpeg"
        }
        WebResourceResponse(format, null, ByteArrayInputStream(bytes))
    }.getOrNull()

    fun dispose() {
        loaded = false
        removeJavascriptInterface("ReaderPosition")
        stopLoading()
        destroy()
    }
}

private fun verticalDocument(
    title: String,
    text: String,
    spans: List<BookTextSpan>,
    marks: List<ReaderMark>,
    mode: String,
    fontSize: Float,
    lineHeight: Float,
    foreground: Color,
    background: Color
): VerticalDocument {
    val images = mutableListOf<String>()
    fun htmlColor(color: Color) = "#" + (color.toArgb() and 0xffffff).toString(16).padStart(6, '0')
    val cssMode = if (mode == "vertical-lr") mode else "vertical-rl"
    val html = buildString {
        append("<!doctype html><html><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
        append("<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; img-src https://reader.ciallo.invalid; style-src 'unsafe-inline'; script-src 'unsafe-inline'\">")
        append("<style>html{")
        append("background:").append(htmlColor(background)).append(";color:").append(htmlColor(foreground))
        append(";font-size:").append(fontSize.coerceIn(8f, 80f)).append("px;line-height:")
        append(lineHeight.coerceIn(10f, 130f)).append("px;}#reader{writing-mode:").append(cssMode)
        append(";text-orientation:mixed;}")
        append("""
            html,body{width:100%;height:100%;margin:0;overflow:hidden;}
            #reader{position:fixed;top:0;right:0;bottom:0;left:0;overflow-x:auto;overflow-y:hidden;
                box-sizing:border-box;padding:16px 14px;font-family:serif;scrollbar-width:none;}
            #reader::-webkit-scrollbar{display:none;}
            h1{font-size:1.24em;margin:0 0.8em;font-weight:700;}
            p{margin:0 0.35em;white-space:pre-wrap;overflow-wrap:anywhere;}
            .spoken{background:rgba(92,172,151,.22)}
            img{max-height:82vh;max-width:86vw;object-fit:contain;margin:0 8px;}
            """.trimIndent())
        append("</style></head><body><main id='reader'><h1>").append(escapeReaderHtml(title)).append("</h1>")
        var lineStart = 0
        for (line in text.split('\n')) {
            val lineEnd = lineStart + line.length
            if (line.isNotBlank()) {
                append("<p data-start='").append(lineStart).append("' data-end='").append(lineEnd).append("'>")
                var offset = lineStart
                for (block in NovelInlineImages.splitIntoBlocks(line, 10_000f, 10_000f)) {
                    when (block) {
                        is NovelInlineImages.InlineBlock.Text -> {
                            val boundaries = sortedSetOf(offset, offset + block.text.length)
                            val styles = spansIn(spans, offset, offset + block.text.length).toList()
                            val visibleMarks = marks.filter { it.end > offset && it.start < offset + block.text.length }
                            for (style in styles) {
                                boundaries.add(maxOf(offset, style.start))
                                boundaries.add(minOf(offset + block.text.length, style.end))
                            }
                            for (mark in visibleMarks) {
                                boundaries.add(maxOf(offset, mark.start))
                                boundaries.add(minOf(offset + block.text.length, mark.end))
                            }
                            boundaries.zipWithNext().forEach { (start, end) ->
                                val style = styles.firstOrNull { it.start <= start && it.end > start }?.style ?: 0
                                val mark = visibleMarks.lastOrNull { it.start <= start && it.end > start }
                                append("<span data-offset='").append(start).append("' style='")
                                append(verticalCss(style))
                                if (mark != null) {
                                    if (mark.underline) append("text-decoration:underline;")
                                    else append("background:").append(htmlColor(mark.color.copy(alpha = 1f))).append("44;")
                                }
                                append("'>").append(escapeReaderHtml(block.text.substring(start - offset, end - offset))).append("</span>")
                            }
                        }
                        is NovelInlineImages.InlineBlock.Image -> {
                            val index = images.size
                            images.add(block.path)
                            append("<a href='/open/").append(index).append("'><img src='/image/")
                                .append(index).append("' alt=''></a>")
                        }
                    }
                    offset += block.raw.length
                }
                append("</p>")
            }
            lineStart = lineEnd + 1
        }
        append("</main><script>").append(verticalScript).append("</script></body></html>")
    }
    return VerticalDocument(html, images, text)
}

private fun verticalCss(flags: Int): String = buildString {
    with(BookTextStyle) {
        if (flags and Bold != 0) append("font-weight:bold;")
        if (flags and (Italic or Quote) != 0) append("font-style:italic;")
        if (flags and (Code or Table) != 0) append("font-family:monospace;")
        if (flags and Heading != 0) append("font-size:1.18em;")
        if (flags and Super != 0) append("vertical-align:super;font-size:.75em;")
        if (flags and Sub != 0) append("vertical-align:sub;font-size:.75em;")
        if (flags and (Underline or Strike) != 0) {
            append("text-decoration:")
            if (flags and Underline != 0) append("underline ")
            if (flags and Strike != 0) append("line-through")
            append(';')
        }
    }
}

private fun escapeReaderHtml(value: String): String = Entities.escape(value)

private val verticalScript = """
    const reader = document.getElementById('reader');
    const lines = [...reader.querySelectorAll('[data-start]')];
    const rightFirst = getComputedStyle(reader).writingMode === 'vertical-rl';
    let scrollTimer;
    reader.addEventListener('scroll', () => {
      clearTimeout(scrollTimer);
      scrollTimer = setTimeout(() => ReaderPosition.report(location.href, currentOffset()), 120);
    }, {passive:true});
    function paragraph(at) {
      let low = 0, high = lines.length;
      while (low < high) {
        const mid = low + ((high - low) >> 1);
        if (+lines[mid].dataset.end < at) low = mid + 1; else high = mid;
      }
      return lines[Math.min(low, lines.length - 1)];
    }
    function locate(at) {
      if (at <= 0) { reader.scrollLeft = 0; return; }
      const node = paragraph(at);
      if (!node) return;
      const part = [...node.querySelectorAll('[data-offset]')].reverse().find(it => +it.dataset.offset <= at);
      if (part && part.firstChild) {
        const range = document.createRange();
        const index = Math.max(0, Math.min(at - +part.dataset.offset, part.textContent.length - 1));
        range.setStart(part.firstChild, index);
        range.setEnd(part.firstChild, index + 1);
        const r = range.getBoundingClientRect();
        reader.scrollLeft += rightFirst ? r.right - reader.clientWidth + 14 : r.left - 14;
      } else { node.scrollIntoView({block:'start',inline:'start'}); }
    }
    function absoluteOffset(node, offset) {
      const part = (node.nodeType === 1 ? node : node.parentElement)?.closest('[data-offset]');
      if (!part) return -1;
      const range = document.createRange();
      range.setStart(part,0); range.setEnd(node,offset);
      return +part.dataset.offset + range.toString().length;
    }
    function currentOffset() {
      const x = rightFirst ? reader.clientWidth - 28 : 28;
      for (const y of [24, 52, 96, reader.clientHeight / 2]) {
        const range = document.caretRangeFromPoint(x,y);
        const at = range ? absoluteOffset(range.startContainer,range.startOffset) : -1;
        if (at >= 0) return at;
      }
      return -1;
    }
    function readSelection() {
      const selected = window.getSelection();
      if (!selected || selected.isCollapsed) return {};
      const range = selected.getRangeAt(0);
      const start = absoluteOffset(range.startContainer,range.startOffset);
      const end = absoluteOffset(range.endContainer,range.endOffset);
      return {start:start,end:end};
    }
    function speakAt(at) {
      document.querySelector('.spoken')?.classList.remove('spoken');
      if (at >= 0) paragraph(at)?.classList.add('spoken');
    }
""".trimIndent()
