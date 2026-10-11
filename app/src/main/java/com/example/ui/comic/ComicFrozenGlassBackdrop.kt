package com.example.ui.comic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import kotlin.math.roundToInt

/** GL pages live outside Compose. Record their processed bitmaps in an invisible sampling layer.
 * This does not wrap, snapshot, or replace the GLSurfaceView; it exists only while a panel is open.
 */
@Composable
internal fun ComicFrozenGlassBackdrop(
    backdrop: LayerBackdrop,
    slots: List<ComicSlot>,
    config: ComicReaderConfig,
    loader: ComicPageLoader,
    bookState: ComicBookState,
    dynamicColor: androidx.compose.ui.graphics.Color?,
) {
    val ordered = if (config.direction == ComicDirection.RTL) slots.reversed() else slots
    val bitmaps = ordered.map { slot -> key(slot.ref.id, slot.half) {
        val state by rememberPageBitmap(loader, slot, config, bookState)
        (state as? PageBitmapState.Ready)?.bitmap?.asImageBitmap()
    } }
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }
        .graphicsLayer { alpha = 0f }.layerBackdrop(backdrop)) {
        ComicReaderBackground(config.bgType, config.paperIntensity, dynamicColor)
        Canvas(Modifier.fillMaxSize()) {
            if (bitmaps.isEmpty()) return@Canvas
            val gap = if (bitmaps.size > 1) config.doubleGapDp.dp.toPx() else 0f
            val width = ((size.width - gap * (bitmaps.size - 1)) / bitmaps.size).coerceAtLeast(1f)
            bitmaps.forEachIndexed { index, bitmap ->
                bitmap ?: return@forEachIndexed
                val fitted = fittedSize(Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                    Size(width, size.height), config.fit, config.customFitScale, config.customFitBase)
                val left = index * (width + gap)
                val y = if (bitmaps.size == 1) (size.height - fitted.height) / 2f else when (config.doubleAlign) {
                    ComicDoubleAlign.TOP -> 0f
                    ComicDoubleAlign.CENTER -> (size.height - fitted.height) / 2f
                    ComicDoubleAlign.BOTTOM -> size.height - fitted.height
                }
                val shiftX = if (bitmaps.size > 1) config.doubleShiftXDp.dp.toPx() else 0f
                val shiftY = if (bitmaps.size > 1) config.doubleShiftYDp.dp.toPx() else 0f
                clipRect(left, 0f, left + width, size.height) {
                    drawImage(bitmap, dstOffset = IntOffset((left + (width - fitted.width) / 2f + shiftX).roundToInt(), (y + shiftY).roundToInt()),
                        dstSize = IntSize(fitted.width.roundToInt().coerceAtLeast(1), fitted.height.roundToInt().coerceAtLeast(1)))
                }
            }
        }
    }
}
