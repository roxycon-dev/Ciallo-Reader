package com.example.mangatranslate

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.core.graphics.scale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.ensureActive

/**
 * 漫画页区域检测器（第十九轮，完整复刻 jedzqer/manga-translator-android
 * detection/PageRegionDetector.kt 的检测流程，MIT License）：
 *
 * - 常规页：气泡分割 + 文字行检测一次完成，行归属气泡、游离行聚块；
 * - 长条漫页（高≥2048 且 高宽比>2.0）：
 *   气泡 → 连续自适应切片（底部截断回放、顶部碎片丢弃、80% 垂直压缩）；
 *   文字 → 25% 重叠切片 + 跨片行去重 + 块合并；
 *   汇总：跨片气泡去重 → 行跨越合并分裂气泡 → tiny/长条过滤 → 文本块。
 *
 * 适配差异：页面位图全部在内存（原仓库经 BitmapCropSource 从文件解码），
 * 切片裁剪 = createBitmap + maxEdge 缩放；无 SettingsStore，置信度取默认 0.15。
 */
class PageRegionDetector(
    paddleDetector: PaddleDetector,
    bubbleDetector: BubbleDetector,
) {
    private val paddle = paddleDetector
    private val bubbles = bubbleDetector

    data class PageRegionResult(
        val bubbles: List<BubbleDetector.Detection>,
        val textLines: List<RectF>,
        val textBlocks: List<TextBlock>,
        val complete: Boolean,
    )

    suspend fun detect(bitmap: Bitmap): PageRegionResult? {
        coroutineContext.ensureActive()
        return try {
            if (PageRegionTiling.shouldUseLongImageTiling(bitmap.width, bitmap.height)) detectLongImageTiledPage(bitmap)
            else detectSingleBitmap(bitmap)
        } catch (e: Exception) { coroutineContext.ensureActive(); null }
    }

    /* ══ 常规页 ══ */

    private fun detectSingleBitmap(bitmap: Bitmap): PageRegionResult? {
        val balloons = runCatching { bubbles.detect(bitmap) }
            .getOrDefault(emptyList())
            .let { PageRegionTiling.filterTinyBubbleDetections(it, bitmap) }
        var textDetectionOk = false
        val rawLines = runCatching {
            val lines = paddle.detectLines(bitmap)
            textDetectionOk = true
            lines
        }.getOrDefault(emptyList())
        val balloonsRejoined = PageRegionTiling.mergeBubblesSpannedByTextLines(
            balloons, rawLines, bitmap.width, bitmap.height
        )
        val textRects = if (balloonsRejoined.isEmpty()) {
            rawLines
        } else {
            PageRegionTiling.filterOverlapping(
                rawLines,
                balloonsRejoined.map { it.rect },
                PAGE_REGION_TEXT_IOU
            )
        }
        val sizeFiltered = PageRegionTiling.filterTinyTextRects(textRects, bitmap.width, bitmap.height)
        val blocks = runCatching {
            TextBlockMerger.merge(sizeFiltered, bitmap.width, bitmap.height)
        }.getOrElse {
            sizeFiltered.map { rect ->
                TextBlock(rect, listOf(RectF(rect)), TextLineOrientation.AMBIGUOUS, FloatArray(0))
            }
        }
        return PageRegionResult(
            bubbles = balloonsRejoined,
            textLines = rawLines,
            textBlocks = blocks,
            complete = textDetectionOk,
        )
    }

    /* ══ 长条漫页：气泡自适应切片 ══ */

    private suspend fun detectLongImageTiledPage(bitmap: Bitmap): PageRegionResult? {
        val pageWidth = bitmap.width
        val pageHeight = bitmap.height

        // ── 气泡切片 ──
        val tileHeight = PageRegionTiling.longImageBubbleDetectionTileHeight(pageWidth, pageHeight)
        if (tileHeight <= 0) return null
        val tiled = ArrayList<TiledBubbleDetection>()
        var tileTop = 0
        var tileIndex = 0
        var previousTileBottom = 0
        while (tileTop < pageHeight) {
            coroutineContext.ensureActive()
            val tile = DetectionTile(0, tileTop, pageWidth, min(pageHeight, tileTop + tileHeight))
            var nextTileTop = tile.bottom
            val tileBitmap = cropRegion(bitmap, tile, PageRegionTiling.DETECTION_MAX_EDGE)
            if (tileBitmap != null) {
                try {
                    // 80% 垂直压缩：切片模型看到的等效纵横比与像素密度与整页一致
                    val detectionBitmap = compressTile(tileBitmap)
                    if (detectionBitmap !== tileBitmap) tileBitmap.recycle()
                    try {
                        val detections = PageRegionTiling.filterTinyBubbleDetections(
                            bubbles.detect(detectionBitmap), detectionBitmap
                        )
                        val discardTopFragments = PageRegionTiling.shouldDiscardReplayTileTopFragments(
                            overlapsPreviousTile = tile.top < previousTileBottom,
                            tileBottom = tile.bottom,
                            pageHeight = pageHeight,
                        )
                        val (topFragments, replayCandidates) = if (discardTopFragments) {
                            detections.partition {
                                PageRegionTiling.isDetectionAtReplayTileTop(it.rect, detectionBitmap.height)
                            }
                        } else {
                            emptyList<BubbleDetector.Detection>() to detections
                        }
                        val (bottomEdge, complete) = replayCandidates.partition {
                            PageRegionTiling.isDetectionAtInternalTileBottom(
                                it.rect, detectionBitmap.height, tile.bottom, pageHeight
                            )
                        }
                        nextTileTop = PageRegionTiling.adaptiveNextTileTop(
                            tile, pageHeight, detectionBitmap.height,
                            bottomEdge.map { it.rect }
                        )
                        if (bottomEdge.isNotEmpty()) {
                            android.util.Log.d(
                                "PageRegionDetector",
                                "Replaying ${bottomEdge.size} bottom-edge bubble(s) from y=$nextTileTop"
                            )
                        }
                        if (topFragments.isNotEmpty()) {
                            android.util.Log.d(
                                "PageRegionDetector",
                                "Dropped ${topFragments.size} top-edge fragment(s)"
                            )
                        }
                        tiled.addAll(
                            complete.map { d ->
                                val remappedContour = d.maskContour?.let {
                                    PageRegionTiling.remapTileMaskContourToPage(
                                        it, tile.top, tile.height, pageWidth, pageHeight
                                    )
                                }
                                TiledBubbleDetection(
                                    rect = remapRect(d.rect, tile, detectionBitmap.width, detectionBitmap.height),
                                    confidence = d.confidence,
                                    maskContour = remappedContour,
                                    touchesInternalTileBoundary = false, // 完整气泡（底部截断已分流）
                                    tileIndex = tileIndex,
                                )
                            }
                        )
                    } finally {
                        if (detectionBitmap !== tileBitmap) detectionBitmap.recycle()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 切片失败跳过（原仓库同语义）
                }
            }
            previousTileBottom = tile.bottom
            tileTop = nextTileTop
            tileIndex++
        }
        val groups = PageRegionTiling.deduplicateBubbleDetections(tiled, pageHeight)
        val deduplicatedBubbles = groups
            .filterNot { PageRegionTiling.shouldFilterLongImageRegion(it.rect, pageWidth, pageHeight) }
            .map { BubbleDetector.Detection(it.rect, it.confidence, it.maskContour) }

        // ── 文字切片（25% 重叠 + 跨片去重）──
        val paddleTiles = PageRegionTiling.planPaddleTextDetectionTiles(pageWidth, pageHeight)
        val pageBubbleRects = deduplicatedBubbles.map { it.rect }
        val detectedTextLines = ArrayList<RectF>()
        for (tile in paddleTiles) {
            coroutineContext.ensureActive()
            val tileBitmap = cropRegion(bitmap, tile, PageRegionTiling.DETECTION_MAX_EDGE) ?: throw TranslationFailure.Ocr.exception()
            try {
                val localLines = paddle.detectLines(tileBitmap)
                detectedTextLines.addAll(
                    PageRegionTiling.remapTileRectsToPage(
                        localLines, tileBitmap.width, tileBitmap.height, tile
                    )
                )
            } finally {
                tileBitmap.recycle()
            }
        }
        val deduplicatedLines = TextBlockMerger.deduplicateLines(detectedTextLines, pageWidth, pageHeight)
        val rejoinedBubbles = PageRegionTiling.mergeBubblesSpannedByTextLines(
            deduplicatedBubbles, deduplicatedLines, pageWidth, pageHeight
        )
        val supplementLines = PageRegionTiling.filterOverlapping(
            deduplicatedLines, rejoinedBubbles.map { it.rect }, PAGE_REGION_TEXT_IOU
        )
        val sizeFiltered = PageRegionTiling.filterTinyTextRects(supplementLines, pageWidth, pageHeight)
        val longFiltered = sizeFiltered.filterNot {
            PageRegionTiling.shouldFilterLongImageRegion(it, pageWidth, pageHeight)
        }
        val blocks = runCatching {
            TextBlockMerger.merge(longFiltered, pageWidth, pageHeight)
        }.getOrElse {
            longFiltered.map { rect ->
                TextBlock(rect, listOf(RectF(rect)), TextLineOrientation.AMBIGUOUS, FloatArray(0))
            }
        }
        return PageRegionResult(
            bubbles = rejoinedBubbles,
            textLines = deduplicatedLines,
            textBlocks = blocks,
            complete = true,
        )
    }

    /** 页面气泡 rect → 本片坐标（remapTileRectsToPage 的反向）。 */
    private fun remapRect(
        rect: RectF,
        tile: DetectionTile,
        tileBitmapWidth: Int,
        tileBitmapHeight: Int
    ): RectF {
        val scaleX = tile.width / tileBitmapWidth.toFloat().coerceAtLeast(1f)
        val scaleY = tile.height / tileBitmapHeight.toFloat().coerceAtLeast(1f)
        return RectF(
            (rect.left * scaleX) + tile.left,
            (rect.top * scaleY) + tile.top,
            (rect.right * scaleX) + tile.left,
            (rect.bottom * scaleY) + tile.top,
        )
    }

    /** 内存裁剪：tile 区域 → 独立位图（长边不超 maxEdge）。 */
    private fun cropRegion(bitmap: Bitmap, tile: DetectionTile, maxEdge: Int): Bitmap? {
        val l = tile.left.coerceIn(0, bitmap.width - 1)
        val t = tile.top.coerceIn(0, bitmap.height - 1)
        val r = tile.right.coerceAtMost(bitmap.width)
        val b = tile.bottom.coerceAtMost(bitmap.height)
        if (r - l < 1 || b - t < 1) return null
        val crop = runCatching {
            Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        }.getOrNull() ?: return null
        val shrink = min(1f, maxEdge.toFloat() / max(crop.width, crop.height))
        return if (shrink < 1f) {
            val scaled = crop.scale(
                (crop.width * shrink).toInt().coerceAtLeast(1),
                (crop.height * shrink).toInt().coerceAtLeast(1)
            )
            if (scaled !== crop) crop.recycle()
            scaled
        } else {
            crop
        }
    }

    private fun compressTile(bitmap: Bitmap): Bitmap {
        val targetHeight = PageRegionTiling.longImageBubbleDetectionInputHeight(bitmap.height)
        if (targetHeight <= 0 || targetHeight == bitmap.height) return bitmap
        return bitmap.scale(bitmap.width, targetHeight)
    }

    companion object {
        private const val PAGE_REGION_TEXT_IOU = 0.2f
    }
}
