package com.example.ui.comic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.Backdrop
import kotlin.math.roundToInt

/** Draw processed page pixels directly into glass effects, without retaining any live RenderNode.
 * Paper snapshots and their glass children can then be rasterized independently of the reader tree.
 */
@Composable
internal fun rememberComicGlassBackdrop(
    slots: List<ComicSlot>, config: ComicReaderConfig, loader: ComicPageLoader,
    bookState: ComicBookState, dynamicColor: Color?, viewport: Rect?,
): Backdrop {
    val ordered = if (config.direction == ComicDirection.RTL) slots.reversed() else slots
    val bitmaps = ordered.map { slot -> key(slot.ref.id, slot.half) {
        val state by rememberPageBitmap(loader, slot, config, bookState)
        (state as? PageBitmapState.Ready)?.bitmap?.asImageBitmap()
    } }
    val paper = if (config.bgType == ComicBgType.PAPER) remember(config.paperIntensity) {
        ComicReaderBackgrounds.paperTexture(config.paperIntensity)
    } else null
    return remember(bitmaps, config, dynamicColor, viewport, paper) {
        object : Backdrop {
            override val isCoordinatesDependent = true
            override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
                val viewportSize = viewport?.size ?: size
                val offset = if (viewport != null && coordinates?.isAttached == true) coordinates.positionInWindow() - viewport.topLeft
                    else androidx.compose.ui.geometry.Offset.Zero
                withTransform({ translate(-offset.x, -offset.y) }) {
                    val background = when (config.bgType) {
                        ComicBgType.BLACK -> Color.Black
                        ComicBgType.WHITE -> Color(0xFFF7F7F5)
                        ComicBgType.GRAY -> Color(0xFF232326)
                        ComicBgType.PAPER -> Color(0xFFF3ECDF)
                        ComicBgType.DYNAMIC -> dynamicColor ?: Color(0xFF101014)
                    }
                    drawRect(background, size = viewportSize)
                    if (paper != null) {
                        val scale = maxOf(viewportSize.width / paper.width, viewportSize.height / paper.height)
                        val w = paper.width * scale; val h = paper.height * scale
                        clipRect(0f, 0f, viewportSize.width, viewportSize.height) {
                            drawImage(paper, dstOffset = IntOffset(((viewportSize.width - w) / 2).roundToInt(), ((viewportSize.height - h) / 2).roundToInt()),
                                dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)))
                        }
                    }
            if (bitmaps.isEmpty()) return@withTransform
            val gap = if (bitmaps.size > 1) config.doubleGapDp.dp.toPx() else 0f
            val width = ((viewportSize.width - gap * (bitmaps.size - 1)) / bitmaps.size).coerceAtLeast(1f)
            bitmaps.forEachIndexed { index, bitmap ->
                bitmap ?: return@forEachIndexed
                val fitted = fittedSize(Size(bitmap.width.toFloat(), bitmap.height.toFloat()),
                    Size(width, viewportSize.height), config.fit, config.customFitScale, config.customFitBase)
                val left = index * (width + gap)
                val y = if (bitmaps.size == 1) (viewportSize.height - fitted.height) / 2f else when (config.doubleAlign) {
                    ComicDoubleAlign.TOP -> 0f
                    ComicDoubleAlign.CENTER -> (viewportSize.height - fitted.height) / 2f
                    ComicDoubleAlign.BOTTOM -> viewportSize.height - fitted.height
                }
                val shiftX = if (bitmaps.size > 1) config.doubleShiftXDp.dp.toPx() else 0f
                val shiftY = if (bitmaps.size > 1) config.doubleShiftYDp.dp.toPx() else 0f
                clipRect(left, 0f, left + width, viewportSize.height) {
                    drawImage(bitmap, dstOffset = IntOffset((left + (width - fitted.width) / 2f + shiftX).roundToInt(), (y + shiftY).roundToInt()),
                        dstSize = IntSize(fitted.width.roundToInt().coerceAtLeast(1), fitted.height.roundToInt().coerceAtLeast(1)))
                }
            }
                }
            }
        }
    }
}
