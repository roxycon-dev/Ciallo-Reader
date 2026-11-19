package com.example.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.i18n.tr
import com.example.ui.components.AppIconButton
import com.example.ui.components.PullOutTextButton
import com.example.ui.wenku.WenkuSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ReaderTextSelection(val passage: String, val start: Int, val end: Int, val sourceOffset: Int)
internal data class ReaderMark(val start: Int, val end: Int, val underline: Boolean, val color: Color)

internal fun wordSelection(text: String, layout: TextLayoutResult?, position: Offset, sourceOffset: Int): ReaderTextSelection? {
    if (layout == null || text.isBlank()) return null
    val line = layout.getLineForVerticalPosition(position.y)
    if (position.x < layout.getLineLeft(line) - 8 || position.x > layout.getLineRight(line) + 8) return null
    val offset = layout.getOffsetForPosition(position).coerceIn(0, text.lastIndex)
    val word = layout.getWordBoundary(offset)
    val rangeStart = (word.start - 220).coerceAtLeast(0)
    val rangeEnd = (word.end + 680).coerceAtMost(text.length)
    return ReaderTextSelection(text.substring(rangeStart, rangeEnd), word.start - rangeStart,
        word.end.coerceAtLeast(offset + 1) - rangeStart, sourceOffset + rangeStart)
}

@Composable
internal fun Modifier.readerTextLongPress(
    text: String,
    offset: Int,
    layout: () -> TextLayoutResult?,
    onSelect: ((ReaderTextSelection) -> Unit)?
): Modifier {
    if (onSelect == null) return this
    val latestLayout by rememberUpdatedState(layout)
    val latestAction by rememberUpdatedState(onSelect)
    return pointerInput(text, offset) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val ended = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    if (change == null || !change.pressed ||
                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                }
                true
            }
            if (ended == null) {
                val selected = wordSelection(text, latestLayout(), down.position, offset)
                if (selected != null) {
                    latestAction(selected)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
    }
}

@Composable
internal fun ReaderSelectionSheet(
    selection: ReaderTextSelection,
    book: String,
    chapter: String,
    onDismiss: () -> Unit,
    onDictionary: (String) -> Unit,
    onMark: ((Int, Int, String, String, Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var value by remember(selection) {
        mutableStateOf(TextFieldValue(selection.passage, TextRange(selection.start, selection.end)))
    }
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var underline by remember { mutableStateOf(false) }
    var close by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var afterExit by remember { mutableStateOf<(() -> Unit)?>(null) }
    val start = value.selection.min
    val end = value.selection.max
    val excerpt = value.text.substring(start, end)
    fun exit(action: () -> Unit) {
        afterExit = action
        close = true
    }
    var pendingSave by remember { mutableStateOf<File?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val file = pendingSave
        pendingSave = null
        if (uri != null && file != null) {
            working = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val sink = requireNotNull(context.contentResolver.openOutputStream(uri))
                        file.inputStream().use { source -> sink.use { source.copyTo(it) } }
                    }
                    close = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    error = true
                } finally {
                    file.delete()
                    working = false
                }
            }
        } else file?.delete()
    }
    fun export(save: Boolean) {
        working = true
        error = false
        val accent = colors.primary.toArgb()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    renderExcerptCard(context, book, chapter, excerpt, accent)
                }
                if (save) {
                    pendingSave = file
                    saveFile.launch("Ciallo-reading-moment.png")
                } else {
                    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                    val share = Intent(Intent.ACTION_SEND).setType("image/png")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    exit { context.startActivity(Intent.createChooser(share, tr("导出阅读时刻"))) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pendingSave?.delete()
                pendingSave = null
                error = true
            } finally {
                working = false
            }
        }
    }
    WenkuSheetDialog(
        onDismissRequest = { onDismiss(); afterExit?.invoke() },
        requestDismiss = close,
        fitKeyboard = true
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 610.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(if (editing) "范围批注" else "正文选段"), Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge)
                AppIconButton(Icons.Filled.Close, tr("关闭"), { close = true })
            }
            OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), readOnly = true, maxLines = 7,
                label = { Text(tr("选中文字")) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PullOutTextButton(onClick = { exit { onDictionary(excerpt) } }, enabled = excerpt.isNotBlank()) {
                    Text(tr("查词"))
                }
                PullOutTextButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText(book, excerpt))
                    close = true
                }, enabled = excerpt.isNotBlank()) { Text(tr("复制")) }
                if (onMark != null) PullOutTextButton(onClick = { editing = !editing }, enabled = excerpt.isNotBlank()) {
                    Text(tr("添加批注"))
                }
            }
            if (editing && onMark != null) {
                OutlinedTextField(note, { note = it.take(1200) }, Modifier.fillMaxWidth(), maxLines = 5,
                    label = { Text(tr("笔记（可选）")) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PullOutTextButton(onClick = { underline = false }) {
                        Text(tr("高亮"), color = if (!underline) colors.primary else colors.onSurface)
                    }
                    PullOutTextButton(onClick = { underline = true }) {
                        Text(tr("下划线"), color = if (underline) colors.primary else colors.onSurface)
                    }
                    Spacer(Modifier.weight(1f))
                    PullOutTextButton(onClick = {
                        exit { onMark(selection.sourceOffset + start, selection.sourceOffset + end, excerpt, note, underline) }
                    }, enabled = excerpt.isNotBlank()) { Text(tr("保存")) }
                }
            }
            HorizontalDivider(color = colors.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                PullOutTextButton(onClick = { export(false) }, enabled = excerpt.isNotBlank() && !working) {
                    Text(tr("分享摘录图片"))
                }
                PullOutTextButton(onClick = { export(true) }, enabled = excerpt.isNotBlank() && !working) {
                    Text(tr("保存摘录图片"))
                }
                if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (error) Text(tr("无法生成摘录图片"), color = colors.error)
        }
    }
}

private fun renderExcerptCard(context: Context, book: String, chapter: String, excerpt: String, accent: Int): File {
    val width = 1080
    val inset = 90f
    val background = android.graphics.Color.rgb(248, 246, 239)
    val ink = android.graphics.Color.rgb(29, 37, 34)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    fun layout(value: String, size: Float, color: Int, maxLines: Int, weight: Int = 0): StaticLayout {
        val style = TextPaint(paint).apply {
            textSize = size
            this.color = color
            typeface = android.graphics.Typeface.create("sans-serif", weight)
        }
        return StaticLayout.Builder.obtain(value, 0, value.length, style, width - 2 * inset.toInt())
            .setMaxLines(maxLines).setEllipsize(android.text.TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(9f, 1f).build()
    }
    val title = layout(book, 42f, ink, 2, android.graphics.Typeface.BOLD)
    val subtitle = layout(chapter, 30f, ink, 2)
    val passage = layout(excerpt.take(1600), 43f, ink, 24)
    val height = (inset * 2 + title.height + subtitle.height + passage.height + 138).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        canvas.drawColor(background)
        paint.color = accent
        canvas.drawRoundRect(inset, inset, inset + 62, inset + 9, 5f, 5f, paint)
        canvas.translate(inset, inset + 36)
        title.draw(canvas)
        canvas.translate(0f, title.height + 10f)
        subtitle.draw(canvas)
        canvas.translate(0f, subtitle.height + 42f)
        passage.draw(canvas)
        canvas.translate(0f, passage.height + 55f)
        paint.textSize = 24f
        paint.color = ink
        canvas.drawText("Ciallo Reader", 0f, 0f, paint)
        val folder = File(context.cacheDir, "share_temp").apply { mkdirs() }
        return File.createTempFile("reading-moment-", ".png", folder).also { file ->
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    } finally {
        bitmap.recycle()
    }
}
