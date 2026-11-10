package com.example.ui.wenku

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.example.data.Book
import com.example.ui.components.pullOutClickable
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * ArchiveFileCarousel 的 Compose 等价实现（重写版）。
 *
 * 逐行对照参考仓库，不再"接近"而是照搬：
 *
 * 1. `archive_shelf_geometry.dart`
 *    - L8  `depthX = .66`, `depthY = -.30`, `rowStep = 2.5`
 *    - L9  `spineFor(w) = (w*.043).clamp(14,18)`
 *    - L10 `pitchFor(w) = spineFor(w) + 11`
 *    - L11 `coverFor(w) = (w*.32).clamp(100,128)`
 *    - L12 `bodyHeight(cover) = (cover-20)*166/118 + 54`
 *    - L13 `footprint = spine + cover*depthX`
 *    - L14 `pullDistance = cover*.84`
 *    - L15 `baseY = -cover*depthY + height*.60 + 12`
 *    - L17 `rowHeight = baseY + height + 60`
 *    - L19 `railY(d) = clamp(d,-7,7)*2.5 + d*d*.45`
 *    - L24 `origin(cover,height,weight) = (-pull*depthX, baseY - pull*depthY - height*.60*lift)`
 *      （`pull = pullDistance*weight*(1.24-.24*weight)`，`lift = w*w*(3-2w)`）
 *
 * 2. `archive_wave.dart` `_WavePose` (L1156-1269)
 *    - L1191 `inkStart = ((index-focus+8)/24).clamp(0,.65)`
 *    - L1192 `inkProgress = Interval(start,start+.35,easeOutCubic)(entrance)`
 *    - L1197 `loadingLift`
 *    - L1204 `focus = count<2 ? 0 : edgeSlots + logicalFocus % count`
 *    - L1209 `poolCount = count<2 || !repeatFill ? count : count + edgeSlots*2`
 *    - L1210 `bookIndex(slot)`
 *    - L1232 `configure`: `edgeSlots = ceil((w/2 + footprint + pull*depthX)/pitch) + 2`
 *    - L1261 `weight = cos(d/1.48 * pi/2)^2`
 *
 * 3. `ArchiveShelfSceneRender.performLayout` (L1438-1471)
 *    - L1459 `start = viewport/2 - footprint/2 + pull*depthX`
 *    - L1466 `x_i = start + i*pitch`，绘制偏移 `x_i - focus*pitch, railY(i)`
 *    - 子盒尺寸 `Size(spine + cover, rowHeight)`；`_BookVolume.height = bodyHeight(cover)`
 *      （archive_wave.dart L259 + L665，不是视口高度）
 *
 * 4. `ArchiveShelfSceneRender._floor` (L1473-1509)
 *    - L1481 六边形 `[0, front, front+depth, depth, depth+tail, tail]`
 *      `front=(spine,1.4)`、`tail=(-10,10)`、`depth=(cover*.66, cover*-.30)`
 *    - L1492 `LinearGradient(begin: topRight, end: bottomLeft, [ink@.10, ink@0])`
 *    - L1500 落点 `offset + railY + (0, baseY + height)`
 *    - L1519/1521 绘制顺序：先全部地面，再按槽位升序（左在后、右在前）
 *
 * 5. `ArchiveBoxDepthRender` (L1615-1913)
 *    - L1679 `_base`：`x' = x + ox`, `y' = .10x + y + oy`
 *    - L1689 `_coverTransform`：`x' = ox+spine + .66x`, `y' = oy+spine*.10 - .30x + y`
 *    - L1700 `_topPath = [origin, origin+front, origin+front+depth, origin+depth]`
 *    - L1796 每个体的绘制顺序：cover → top cap → spine（顶面压封面，书脊压顶面）
 *    - L1813 cap 渐变 = `[#E3DFD3, milk]`（暗色 `[darkRaisedHigh, darkRaised]`），
 *      描边 `#9A927F@.24`（暗色 `darkMilk@.24`），宽度 .4
 *
 * 6. `archive_spine_title.dart`
 *    - L5 `archiveSpineRuns`（拉丁连排、CJK 单字）
 *    - L31 `fontSize 9, height 1.08, w600`，`runs.take(7)`，超过 7 段补省略号，
 *      拉丁段 > 24 字符截 23 + '…'，整体 `FittedBox(scaleDown)`
 *
 * 7. `archive_design.dart` L14/L17/L22-24：milk #FEFDF8、raised #2D2D2D、圆角 3/4
 *
 * 8. `archive_shelf_loading.dart` L8 `archiveLoadingLift`
 *    `phase = frame/1.7s - lane*.075`; `crest = (1+sin(2pi*phase))/2`;
 *    `lift = -9*crest*crest*remaining`
 *
 * 9. `archive_scroll_simulation.dart`：`e^-4.4t`、`v0/4.4`、
 *    `handoffSpeed = pitch*4`、`k = w^2 = 144`、`d = 2w = 24`、三次落地
 *
 * 每帧只做矩阵/平移 + 已缓存 Path/Paint/Typeface；封面位图每本只光栅化一次。
 */
@Composable
fun ArchiveShelf(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    modifier: Modifier = Modifier,
    initialIndex: Int? = null,
    showChrome: Boolean = true
) {
    if (books.isEmpty()) return
    val shelfBooks = remember(books) { books.take(60) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val colors = wenkuColors()
    val a11y = rememberWenkuAccessibility()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val painters = shelfBooks.map { rememberShelfPainter(it, context) }
    val volumes = remember(shelfBooks) { shelfBooks.mapIndexed { index, book -> ShelfVolume.of(index, book) } }
    val covers = remember { ShelfCoverStore() }
    val scene = remember { ShelfScenePainter() }

    val starting = remember(shelfBooks, initialIndex) {
        (initialIndex ?: shelfBooks.indexOf(shelfBooks.maxByOrNull { it.lastReadTime } ?: shelfBooks.first()))
            .coerceIn(0, shelfBooks.lastIndex)
    }
    val logicalFocus = remember { Animatable(starting.toFloat()) }
    val reveal = remember { Animatable(if (a11y.reduceMotion) 1f else 0f) }
    val frameNanos = remember { mutableStateOf(0L) }

    LaunchedEffect(a11y.reduceMotion) {
        if (a11y.reduceMotion) {
            reveal.snapTo(1f)
            return@LaunchedEffect
        }
        reveal.snapTo(0f)
        // archive_wave.dart L1197: loadingLift 读的是全局帧时间戳，只有入场期间需要。
        launch {
            while (true) {
                frameNanos.value = withFrameNanos { it }
                if (reveal.value >= 1f) break
            }
        }
        reveal.animateTo(1f, tween(520, easing = LinearEasing))
    }
    // Only clamp when the collection shrinks; never reset the active book on a
    // background DB emission (the shelf must keep the user's swiped position).
    LaunchedEffect(shelfBooks.size) {
        logicalFocus.snapTo(logicalFocus.value.coerceIn(0f, shelfBooks.lastIndex.toFloat()))
    }
    val activeIndex by remember(shelfBooks.size) {
        derivedStateOf { logicalFocus.value.roundToInt().coerceIn(0, shelfBooks.lastIndex) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val viewportWidth = minOf(maxWidth, METRICS_MAX_VIEWPORT)
        val metrics = remember(viewportWidth, density.density) {
            ShelfMetrics.of(viewportWidth.value, density.density)
        }
    // Reference rowHeight keeps a 60dp ink band below the shelf; inside this app that band
    // pushes the caption under the dock, so the drawing box keeps the 10px shadow tail plus 12dp.
    val rowHeight = with(density) { (metrics.baseYPx + metrics.bodyPx + 12f * density.density).toDp() }
        val coverStates = painters.map { (it as? AsyncImagePainter)?.state }
        val coverReady = coverStates.map { it is AsyncImagePainter.State.Success }
        val bitmapped = with(density) { (metrics.coverPx - 20f * density.density).roundToInt() }
            .coerceIn(64, 256)
        val bitmapHeight = (bitmapped * 166f / 118f).roundToInt()
        val warm = remember(activeIndex, shelfBooks.size) { shelfWarmWindow(activeIndex, shelfBooks.size) }
        // 封面每本只光栅化一次（Painter -> ImageBitmap），draw 阶段只做矩阵贴图。
        SideEffect {
            val portrait = Density(density.density)
            for (index in warm) {
                if (!coverReady.getOrElse(index) { false }) continue
                val book = shelfBooks[index]
                if (covers.get(book.id) != null) continue
                rasterizeShelfCover(painters.getOrNull(index), bitmapped, bitmapHeight, portrait)
                    ?.let { covers.put(book.id, it) }
            }
        }

        val center = metrics.startXPx + metrics.pullPx * METRICS_DEPTH_X

        // 3D 书架的封面飞行入口：语义盒就是可见封面的矩形（shelfSemanticBox 的布局与
        // ScenePainter 画封面面完全同源），登记成 root 坐标后点击时交给飞行桥。
        // 普通 Map：onGloballyPositioned 在布局阶段写状态会触发「布局→重组→布局」死循环
        // （R15 实测：切列表模式直接 ANR）。这里只给点击时查表用，不需要参与重组。
        val bookBounds = remember { HashMap<Int, androidx.compose.ui.geometry.Rect>() }
        fun openBook(book: Book) {
            val index = shelfBooks.indexOf(book)
            val rect = bookBounds[index]
            if (index >= 0 && rect != null && !book.isComic) {
                com.example.ui.wenku.WenkuFlightBridge.begin(
                    bookKey = book.id.toString(),
                    bounds = rect,
                    image = covers.get(book.id),
                    radius = 3.dp
                )
            }
            onBookClick(book)
        }

        fun centre(target: Int) {
            if (shelfBooks.size < 2) return
            val destination = target.coerceIn(0, shelfBooks.lastIndex)
            if (abs(logicalFocus.value - destination) < .01f) return
            scope.launch {
                if (a11y.reduceMotion) logicalFocus.snapTo(destination.toFloat())
                else logicalFocus.animateTo(destination.toFloat(), tween(WenkuMotion.ENTER_MS, easing = SHELF_CENTRE_CURVE))
            }
        }

        fun slotAt(point: Offset): Int? {
            val count = shelfBooks.size
            if (count <= 0) return null
            val focus = metrics.focusOf(logicalFocus.value, count)
            val first = floor(focus - metrics.edgeSlots - 2f).toInt().coerceAtLeast(0)
            val last = ceil(focus + metrics.edgeSlots + 2f).toInt()
                .coerceAtMost(metrics.poolCountOf(count) - 1)
            var hit: Int? = null
            for (slot in first..last) {
                val distance = slot - focus
                val left = metrics.startXPx + distance * metrics.pitchPx + metrics.originX(distance)
                val top = metrics.railY(distance) + metrics.originY(distance) + metrics.coverPx * METRICS_DEPTH_Y
                val right = left + metrics.footprintPx
                val bottom = top + metrics.bodyPx + metrics.coverPx * (-METRICS_DEPTH_Y) + metrics.spinePx * .10f
                if (point.x >= left && point.x <= right && point.y >= top && point.y <= bottom) hit = slot
            }
            return hit
        }

        fun tap(point: Offset) {
            val count = shelfBooks.size
            val focus = metrics.focusOf(logicalFocus.value, count)
            val slot = slotAt(point)
            if (slot == null) {
                if (count <= 1 || abs(point.x - center) <= metrics.coverPx * .42f) {
                    shelfBooks.getOrNull(activeIndex)?.let(::openBook)
                }
                return
            }
            // archive_wave.dart L161 _tap: 命中的就是中心书才打开，否则先滚到中心。
            if (abs(slot - focus) < .30f) shelfBooks.getOrNull(metrics.bookIndexOf(slot, count))?.let(::openBook)
            else centre(metrics.bookIndexOf(slot, count))
        }

        Column(
            modifier = Modifier.align(Alignment.Center).width(viewportWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .clipToBounds()
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .drawWithCache {
                            scene.bind(metrics, volumes, covers, colors)
                            onDrawBehind {
                                scene.draw(
                                    scope = this,
                                    logicalFocus = logicalFocus.value,
                                    entrance = reveal.value,
                                    frameNanos = frameNanos.value,
                                    reduceMotion = a11y.reduceMotion
                                )
                            }
                        }
                )

                // 只暴露当前书与左右邻书，避免 60 个语义节点在拖动时逐帧布局。
                val semanticIndices = remember(activeIndex, shelfBooks.size) {
                    val n = shelfBooks.size
                    if (n <= 1) listOf(0)
                    else listOf(activeIndex, (activeIndex - 1 + n) % n, (activeIndex + 1) % n).distinct()
                }
                semanticIndices.forEach { index ->
                    val book = shelfBooks.getOrNull(index) ?: return@forEach
                    val selectedNow = index == activeIndex
                    Box(
                        modifier = Modifier
                            .shelfSemanticBox(index, logicalFocus, metrics, shelfBooks.size)
                            .onGloballyPositioned { bookBounds[index] = it.boundsInRoot() }
                            .semantics {
                                role = Role.Button
                                selected = selectedNow
                                contentDescription =
                                    "《${book.title}》，${book.author.ifBlank { "未知作者" }}${if (selectedNow) "，当前选中" else ""}"
                                onClick(label = "打开") {
                                    openBook(book)
                                    true
                                }
                            }
                    )
                }

                // A real hit-test surface above the painted volumes and the
                // semantics-only nodes. Relying on the parent pointerInput to
                // receive taps here is not reliable inside a lazy scroll host.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(Color.Transparent)
                        .pointerInput(shelfBooks.size, metrics.pitchPx, a11y.reduceMotion) {
                            val touchSlop = viewConfiguration.touchSlop
                            val densityScale = density.density
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val tracker = VelocityTracker()
                                tracker.addPosition(down.uptimeMillis, down.position)
                                var horizontal = false
                                var moved = false
                                var cancelled = false
                                var lastX = down.position.x
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.isConsumed && !horizontal) {
                                        cancelled = true
                                        break
                                    }
                                    if (!change.pressed) {
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        break
                                    }
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (!horizontal && hypot(dx, dy) > touchSlop) {
                                        moved = true
                                        if (abs(dx) > abs(dy) * 1.2f) {
                                            horizontal = true
                                            lastX = change.position.x - (dx - kotlin.math.sign(dx) * touchSlop)
                                        } else if (abs(dy) >= abs(dx)) {
                                            cancelled = true
                                            break
                                        }
                                    }
                                    if (horizontal) {
                                        val amount = change.position.x - lastX
                                        lastX = change.position.x
                                        change.consume()
                                        val next = (logicalFocus.value - amount / metrics.pitchPx)
                                            .coerceIn(0f, shelfBooks.lastIndex.toFloat())
                                        scope.launch { logicalFocus.snapTo(next) }
                                    }
                                }
                                if (!moved && !cancelled) {
                                    tap(down.position)
                                } else if (horizontal) {
                                    val velocity = tracker.calculateVelocity().x
                                    val from = logicalFocus.value
                                    if (a11y.reduceMotion) {
                                        hapticsPerform(haptics)
                                        scope.launch { logicalFocus.snapTo(from.roundToInt().toFloat()) }
                                    } else {
                                        val simulation = ShelfScrollSimulation(
                                            position = from * metrics.pitchPx,
                                            velocity = velocity,
                                            pitch = metrics.pitchPx,
                                            minVelocity = 50f * densityScale
                                        )
                                        val target = (simulation.target / metrics.pitchPx)
                                            .coerceIn(0f, shelfBooks.lastIndex.toFloat())
                                        val fromPx = from * metrics.pitchPx
                                        val toPx = target * metrics.pitchPx
                                        hapticsPerform(haptics)
                                        if (abs(toPx - fromPx) < .01f) {
                                            scope.launch { logicalFocus.snapTo(target) }
                                        } else {
                                            val span = toPx - fromPx
                                            val duration = simulation.durationSeconds()
                                            scope.launch {
                                                logicalFocus.animateTo(
                                                    targetValue = target,
                                                    animationSpec = tween(
                                                        durationMillis = (duration * 1000f).roundToInt().coerceIn(1, 1400),
                                                        easing = Easing { fraction ->
                                                            ((simulation.x(fraction * duration) - fromPx) / span)
                                                                .coerceIn(0f, 1f)
                                                        }
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                )
            }

            if (showChrome) {
                ArchiveShelfCaption(
                    book = shelfBooks[activeIndex],
                    index = activeIndex,
                    count = shelfBooks.size,
                    onPrevious = if (shelfBooks.size > 1) {
                        { centre((activeIndex - 1 + shelfBooks.size) % shelfBooks.size) }
                    } else null,
                    onNext = if (shelfBooks.size > 1) {
                        { centre((activeIndex + 1) % shelfBooks.size) }
                    } else null,
                    onOpen = { openBook(shelfBooks[activeIndex]) }
                )
            }
        }
    }
}

private fun hapticsPerform(haptics: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
}

@Composable
private fun ArchiveShelfCaption(
    book: Book,
    index: Int,
    count: Int,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onOpen: () -> Unit
) {
    val colors = wenkuColors()
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pullOutClickable(onClick = onOpen)
                .padding(vertical = 8.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 10.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)
            ) {
                // app_language.dart:239-369: characters fold one by one (780ms clock, per-char
                // delay Random(hash ^ generation) * .52) instead of a whole-string cross fade.
                WenkuMorphText(
                    text = book.title,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 17.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.ink,
                        fontFamily = com.example.ui.theme.AppFonts.Serif
                    ),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(book.author.ifBlank { "未知作者" })
                        if (book.totalChapters > 0) {
                            append(" · 第 ${(book.currentChapterIndex + 1).coerceAtMost(book.totalChapters)} / ${book.totalChapters} 章")
                        }
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowOutward,
                contentDescription = null,
                tint = colors.ink.copy(alpha = .82f),
                modifier = Modifier.size(20.dp)
            )
        }
        if (count > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ShelfStepButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    label = "上一本",
                    enabled = onPrevious != null,
                    onClick = { onPrevious?.invoke() },
                    wenkuSymbol = WenkuSymbol.ArrowLeft
                )
                Text(
                    text = "%02d / %02d".format(index + 1, count),
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                ShelfStepButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    label = "下一本",
                    enabled = onNext != null,
                    onClick = { onNext?.invoke() },
                    wenkuSymbol = WenkuSymbol.ArrowRight
                )
            }
        }
    }
}

@Composable
private fun ShelfStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    wenkuSymbol: WenkuSymbol? = null
) {
    val colors = wenkuColors()
    com.example.ui.components.AppIconButton(
        icon = icon,
        contentDescription = label,
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(36.dp)
            .background(colors.ink.copy(alpha = if (enabled) .06f else .025f), CircleShape),
        tint = colors.ink.copy(alpha = .82f),
        glyphSize = 17.dp,
        wenkuSymbol = wenkuSymbol
    )
}

@Composable
private fun rememberShelfPainter(book: Book, context: android.content.Context): Painter? {
    val model = remember(book.coverUri, book.isCoverValid) {
        val uri = book.coverUri
        when {
            uri.isNullOrBlank() -> null
            uri.startsWith("content://") -> android.net.Uri.parse(uri)
            // 「我喜欢的」是在线收藏，封面是远程 URL：ArchiveShelf 现在两个板块共用，
            // 这里必须认 http(s)，否则收藏 3D 书架全是空白书脊。
            uri.startsWith("http://") || uri.startsWith("https://") -> uri
            book.isCoverValid -> {
                val path = if (uri.startsWith("file://")) uri.substring(7) else uri
                java.io.File(path)
            }
            else -> null
        }
    }
    if (model == null) return null
    val request = remember(model, book.id) {
        ImageRequest.Builder(context)
            .data(model)
            .size(256)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }
    return rememberAsyncImagePainter(request)
}

// ---------------------------------------------------------------------------
// geometry — archive_shelf_geometry.dart 1:1
// ---------------------------------------------------------------------------

private const val METRICS_DEPTH_X = .66f
private const val METRICS_DEPTH_Y = -.30f
private const val METRICS_ROW_STEP = 2.5f
private const val METRICS_RADIUS = 1.48f
private val METRICS_MAX_VIEWPORT = 520.dp
private val SHELF_CENTRE_CURVE = CubicBezierEasing(.2f, 1.06f, .35f, 1f)

/** 全部字段 px（= Dart logical px × density），比例与常量完全一致。 */
private class ShelfMetrics(
    val density: Float,
    val viewportPx: Float,
    val spinePx: Float,
    val pitchPx: Float,
    val coverPx: Float,
    val bodyPx: Float,
    val baseYPx: Float,
    val rowHeightPx: Float,
    val footprintPx: Float,
    val pullPx: Float,
    val startXPx: Float,
    val edgeSlots: Int
) {
    companion object {
        fun of(widthDp: Float, density: Float): ShelfMetrics {
            val spine = (widthDp * .043f).coerceIn(14f, 18f)
            val pitch = spine + 11f
            val cover = (widthDp * .32f).coerceIn(100f, 128f)
            val body = (cover - 20f) * 166f / 118f + 54f
            val baseY = -cover * METRICS_DEPTH_Y + body * .60f + 12f
            val rowHeight = baseY + body + 60f
            val footprint = spine + cover * METRICS_DEPTH_X
            val pull = cover * .84f
            // _WavePose.configure (archive_wave.dart L1238): edgeSlots 由视口算，
            // 小库也不缩减 —— 缩减会露出书架两端，看起来像一面白墙。
            val edgeSlots = ceil((widthDp / 2f + footprint + pull * METRICS_DEPTH_X) / pitch)
                .toInt() + 2
            val start = widthDp / 2f - footprint / 2f + pull * METRICS_DEPTH_X
            return ShelfMetrics(
                density = density,
                viewportPx = widthDp * density,
                spinePx = spine * density,
                pitchPx = pitch * density,
                coverPx = cover * density,
                bodyPx = body * density,
                baseYPx = baseY * density,
                rowHeightPx = rowHeight * density,
                footprintPx = footprint * density,
                pullPx = pull * density,
                startXPx = start * density,
                edgeSlots = edgeSlots
            )
        }
    }

    fun railY(distance: Float): Float {
        val d = distance.coerceIn(-7f, 7f)
        return (d * METRICS_ROW_STEP + d * d * .45f) * density
    }

    /** archive_wave.dart L1261: `cos(d / 1.48 * pi/2)^2`。 */
    fun weight(distance: Float): Float {
        val d = abs(distance)
        if (d >= METRICS_RADIUS) return 0f
        val value = cos(d / METRICS_RADIUS * (PI / 2).toFloat())
        return value * value
    }

    private fun pullOf(distance: Float): Float {
        val w = weight(distance)
        return pullPx * w * (1.24f - .24f * w)
    }

    fun originX(distance: Float): Float = -pullOf(distance) * METRICS_DEPTH_X

    fun originY(distance: Float): Float {
        val w = weight(distance)
        val lift = w * w * (3f - 2f * w)
        return baseYPx - pullOf(distance) * METRICS_DEPTH_Y - bodyPx * .60f * lift
    }

    fun focusOf(logicalFocus: Float, count: Int): Float =
        if (count < 2) 0f else edgeSlots + logicalFocus

    fun poolCountOf(count: Int): Int = if (count < 2) 1 else count + edgeSlots * 2

    fun bookIndexOf(slot: Int, count: Int): Int =
        if (count < 2) 0 else floorMod(slot - edgeSlots, count)
}

private fun floorMod(value: Int, count: Int): Int {
    if (count <= 0) return 0
    val result = value % count
    return if (result < 0) result + count else result
}

/**
 * archive_wave.dart L1191/L1197/L1232 —— `_WavePose` 的入场与滚动物理。
 */
private fun inkStartOf(distance: Float): Float = ((distance + 8f) / 24f).coerceIn(0f, .65f)

private fun inkProgressOf(entrance: Float, start: Float): Float {
    val end = start + .35f
    val t = entrance.coerceIn(0f, 1f)
    if (t <= start) return 0f
    if (t >= end) return 1f
    return WenkuMotion.ease.transform((t - start) / (end - start))
}

/** archive_shelf_loading.dart L8，返回 px。 */
private fun loadingLiftPx(frameNanos: Long, distance: Float, remaining: Float, density: Float): Float {
    if (remaining <= 0f) return 0f
    val phase = frameNanos / 1_700_000_000f - distance * .075f
    val crest = (1f + sin((phase * (2f * PI.toFloat())))) / 2f
    return -9f * crest * crest * remaining * density
}

// ---------------------------------------------------------------------------
// scroll simulation — archive_scroll_simulation.dart
// ---------------------------------------------------------------------------

private const val SHELF_DRAG_RATE = 4.4f
private const val SHELF_OMEGA = 12f

/**
 * `ArchiveScrollSimulation`：先惯性（v0 以 e^-4.4t 衰减，位移 v0/4.4），
 * 再三次落地；无方向释放时用 k=omega^2=144、damping=2*omega=24 的临界阻尼。
 */
private class ShelfScrollSimulation(
    private val position: Float,
    velocity: Float,
    private val pitch: Float,
    minVelocity: Float
) {
    private val speed = abs(velocity)
    private val directional = speed >= minVelocity
    private val release = if (directional) velocity else 0f
    val target: Float
    private val coastDuration: Float
    private val handoffPosition: Float
    private val handoffVelocity: Float
    private val landingDuration: Float

    init {
        var destination = ((position + release / SHELF_DRAG_RATE) / pitch).roundToInt() * pitch
        if (directional && (destination - position) * release.sign < speed / SHELF_OMEGA) {
            val runway = position + release / SHELF_OMEGA
            destination = (if (release > 0f) ceil(runway / pitch) else floor(runway / pitch)) * pitch
        }
        target = destination
        val handoffSpeed = pitch * 4f
        coastDuration = if (speed > handoffSpeed && directional) ln(speed / handoffSpeed) / SHELF_DRAG_RATE else 0f
        handoffPosition = coastX(coastDuration)
        handoffVelocity = coastV(coastDuration)
        landingDuration = if (directional) 3f * (target - handoffPosition) / handoffVelocity else 0f
    }

    private fun coastX(time: Float): Float =
        position + (release / SHELF_DRAG_RATE) * (1f - exp(-SHELF_DRAG_RATE * time))

    private fun coastV(time: Float): Float = release * exp(-SHELF_DRAG_RATE * time)

    fun x(time: Float): Float {
        if (landingDuration == 0f) return springX(time)
        if (time < coastDuration) return coastX(time)
        val remaining = (1f - (time - coastDuration) / landingDuration).coerceIn(0f, 1f)
        return target - (target - handoffPosition) * remaining * remaining * remaining
    }

    private fun springX(time: Float): Float {
        val delta = position - target
        if (delta == 0f) return target
        return target + (delta + SHELF_OMEGA * delta * time) * exp(-SHELF_OMEGA * time)
    }

    private fun springV(time: Float): Float {
        val delta = position - target
        return -SHELF_OMEGA * SHELF_OMEGA * delta * time * exp(-SHELF_OMEGA * time)
    }

    fun isDone(time: Float): Boolean {
        if (landingDuration == 0f) {
            return abs(x(time) - target) < .01f && abs(springV(time)) < .1f
        }
        return time >= coastDuration + landingDuration
    }

    fun durationSeconds(): Float {
        val step = 1f / 240f
        var time = step
        while (time < 2f) {
            if (isDone(time)) return time
            time += step
        }
        return 2f
    }
}

// ---------------------------------------------------------------------------
// volume data — archive_spine_title.dart / archive_design.dart
// ---------------------------------------------------------------------------

private class SpineRun(val text: String, val latin: Boolean)

/** archive_spine_title.dart L5：拉丁连排、CJK 单字。 */
private fun archiveSpineRuns(title: String): List<SpineRun> {
    val runs = ArrayList<SpineRun>(title.length.coerceAtMost(16))
    val latin = StringBuilder()
    fun flush() {
        val text = latin.toString().trim()
        if (text.isNotEmpty()) runs += SpineRun(text, true)
        latin.setLength(0)
    }
    for (glyph in title) {
        if (glyph.code in 0x20..0x7e) latin.append(glyph) else {
            flush()
            runs += SpineRun(glyph.toString(), false)
        }
    }
    flush()
    return runs
}

private class ShelfVolume private constructor(
    val id: Int,
    val title: String,
    val number: String,
    val coverCode: String,
    val fileCode: String,
    val runs: List<SpineRun>,
    val truncated: Boolean
) {
    companion object {
        fun of(index: Int, book: Book): ShelfVolume {
            val ordinal = index + 1
            val all = archiveSpineRuns(book.title)
            val runs = all.take(7).map { run ->
                if (run.latin && run.text.length > 24) SpineRun(run.text.take(23) + "…", true) else run
            }
            return ShelfVolume(
                id = book.id,
                title = book.title,
                number = ordinal.toString().padStart(2, '0'),
                coverCode = "NO." + ordinal.toString().padStart(3, '0'),
                // 负 id = 「我喜欢的」映射条目（见 HomeScreen 的 favShelfBooks），书脊编号用 FAV-。
            fileCode = (if (book.id < 0) "FAV-" else "WK-") + kotlin.math.abs(book.id).toString().padStart(5, '0'),
                runs = runs,
                truncated = all.size > 7
            )
        }
    }
}

// ---------------------------------------------------------------------------
// cover bitmaps — Painter 每本只取一次
// ---------------------------------------------------------------------------

private class ShelfCoverStore(private val capacity: Int = 32) {
    private val entries = LinkedHashMap<Int, ImageBitmap>(16, .75f, true)

    fun get(bookId: Int): ImageBitmap? = entries[bookId]

    fun put(bookId: Int, image: ImageBitmap) {
        entries[bookId] = image
        while (entries.size > capacity) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }
}

private fun shelfWarmWindow(active: Int, count: Int): List<Int> {
    if (count <= 0) return emptyList()
    val low = (active - 5).coerceAtLeast(0)
    val high = (active + 5).coerceAtMost(count - 1)
    return (low..high).toList()
}

/** Painters 只在这里被光栅化一次；之后每帧都是 drawBitmap + 矩阵。 */
private fun rasterizeShelfCover(
    painter: Painter?,
    widthPx: Int,
    heightPx: Int,
    density: Density
): ImageBitmap? {
    if (widthPx <= 0 || heightPx <= 0) return null
    val success = (painter as? AsyncImagePainter)?.state as? AsyncImagePainter.State.Success ?: return null
    val drawable = success.result.drawable ?: return null
    return runCatching {
        val bitmap = android.graphics.Bitmap.createBitmap(widthPx, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        drawable.setBounds(0, 0, widthPx, heightPx)
        drawable.draw(canvas)
        bitmap.asImageBitmap()
    }.getOrNull()
}

private class ShelfPalette(colors: WenkuColors, density: Float) {
    val coverFace: Int = (if (colors.isDark) colors.panel else colors.milk).toArgb()
    val spineFace: Int = (if (colors.isDark) colors.raised else Color(0xFFF2F0E9)).toArgb()
    val capTop: Int = (if (colors.isDark) colors.raisedHigh else Color(0xFFE3DFD3)).toArgb()
    val capBottom: Int = (if (colors.isDark) colors.raised else colors.milk).toArgb()
    val capStroke: Int = (if (colors.isDark) WenkuTokens.MilkDark else Color(0xFF9A927F)).toArgb()
    val capStrokeAlpha: Float = .24f
    val floorInk: Int = (if (colors.isDark) colors.milk else Color(0xFF8A806B)).toArgb()
    val border: Int = colors.outline.toArgb()
    val ink: Int = colors.ink.toArgb()
    val secondary: Int = colors.inkSecondary.toArgb()
    val paperHint: Int = (if (colors.isDark) colors.raisedHigh else Color(0xFFE9E4D6)).toArgb()

    val codeSize: Float = 9f * density
    val codeLine: Float = 9f * 1.35f * density
    val codeSpacing: Float = 1.05f / 9f
    val titleSize: Float = 9f * density
    val titleLine: Float = 9f * 1.08f * density
    val coverPadH: Float = 10f * density
    val coverPadV: Float = 9f * density
    val coverPadBottom: Float = 8f * density
    val gapCoverCode: Float = 7f * density
    val gapFileCode: Float = 4f * density
    val spinePadH: Float = 1f * density
    val spinePadV: Float = 9f * density
    val gapAfterNumber: Float = 10f * density
    val gapBeforeDot: Float = 6f * density
    val dotRadius: Float = 3f * density
    val dotSize: Float = 6f * density
    val faceRadius: Float = 3f * density
    val hairline: Float = .7f * density
    val capHairline: Float = .4f * density
    val registration: Float = 7f * density
}

private class ShelfScenePainter {
    private var metrics: ShelfMetrics? = null
    private var volumes: List<ShelfVolume>? = null
    private var covers: ShelfCoverStore? = null
    private var palette: ShelfPalette? = null
    private var paletteDark: Boolean? = null

    private val capPath = Path()
    private val floorPath = Path()
    private val registrationPath = Path()
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val bounds = android.graphics.RectF()
    private val fontMetrics = Paint.FontMetrics()
    private val source = Rect()
    private val destination = Rect()
    private val widthCache = HashMap<String, Float>(32)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val capFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val capStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val floorFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val codePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mono: Typeface = Typeface.create("monospace", Typeface.NORMAL)

    private var slotIndices = IntArray(192)
    private var slotX = FloatArray(192)
    private var slotY = FloatArray(192)
    private var slotInk = FloatArray(192)

    fun bind(metrics: ShelfMetrics, volumes: List<ShelfVolume>, covers: ShelfCoverStore, colors: WenkuColors) {
        val sameGeometry = this.metrics === metrics
        val sameInk = this.paletteDark == colors.isDark
        if (sameGeometry && sameInk && this.volumes === volumes) {
            this.covers = covers
            return
        }
        this.metrics = metrics
        this.volumes = volumes
        this.covers = covers
        this.paletteDark = colors.isDark
        val palette = ShelfPalette(colors, metrics.density)
        this.palette = palette
        buildPaths(metrics, palette)
        buildShaders(metrics, palette)
        widthCache.clear()
    }

    /** Path.rewind() 复用：只在尺寸/配色变化时重建，draw 阶段零分配。 */
    private fun buildPaths(metrics: ShelfMetrics, palette: ShelfPalette) {
        val depth = Offset(metrics.coverPx * METRICS_DEPTH_X, metrics.coverPx * METRICS_DEPTH_Y)
        val front = Offset(metrics.spinePx, 1.4f * metrics.density)
        val tail = Offset(-10f * metrics.density, 10f * metrics.density)
        floorPath.rewind()
        floorPath.moveTo(0f, 0f)
        floorPath.lineTo(front.x, front.y)
        floorPath.lineTo(front.x + depth.x, front.y + depth.y)
        floorPath.lineTo(depth.x, depth.y)
        floorPath.lineTo(depth.x + tail.x, depth.y + tail.y)
        floorPath.lineTo(tail.x, tail.y)
        floorPath.close()

        val capFront = Offset(metrics.spinePx, metrics.spinePx * .10f)
        capPath.rewind()
        capPath.moveTo(0f, 0f)
        capPath.lineTo(capFront.x, capFront.y)
        capPath.lineTo(capFront.x + depth.x, capFront.y + depth.y)
        capPath.lineTo(depth.x, depth.y)
        capPath.close()

        // ArchiveRegistration (archive_design.dart L283)：四角 L 形标记。
        registrationPath.rewind()
        val inset = .5f * metrics.density
        val left = inset
        val top = inset
        val right = metrics.coverPx - inset
        val bottom = metrics.bodyPx - inset
        val length = minOf(palette.registration, minOf(right - left, bottom - top) / 3f)
        if (length > 0f) {
            registrationPath.moveTo(left + length, top)
            registrationPath.lineTo(left, top)
            registrationPath.lineTo(left, top + length)
            registrationPath.moveTo(right - length, top)
            registrationPath.lineTo(right, top)
            registrationPath.lineTo(right, top + length)
            registrationPath.moveTo(left + length, bottom)
            registrationPath.lineTo(left, bottom)
            registrationPath.lineTo(left, bottom - length)
            registrationPath.moveTo(right - length, bottom)
            registrationPath.lineTo(right, bottom)
            registrationPath.lineTo(right, bottom - length)
        }
    }

    private fun buildShaders(metrics: ShelfMetrics, palette: ShelfPalette) {
        capPath.computeBounds(bounds, true)
        capFill.shader = LinearGradient(
            bounds.left,
            bounds.top,
            bounds.right,
            bounds.bottom,
            palette.capTop,
            palette.capBottom,
            android.graphics.Shader.TileMode.CLAMP
        )
        capStroke.color = palette.capStroke
        capStroke.strokeWidth = palette.capHairline

        floorPath.computeBounds(bounds, true)
        floorFill.shader = LinearGradient(
            bounds.right,
            bounds.top,
            bounds.left,
            bounds.bottom,
            withAlpha(palette.floorInk, .10f),
            withAlpha(palette.floorInk, 0f),
            android.graphics.Shader.TileMode.CLAMP
        )
        hairline.strokeWidth = palette.hairline
        hairline.color = palette.border
        placeholder.color = palette.paperHint
        codePaint.typeface = mono
        codePaint.letterSpacing = palette.codeSpacing
        titlePaint.isFakeBoldText = true
    }

    fun draw(
        scope: DrawScope,
        logicalFocus: Float,
        entrance: Float,
        frameNanos: Long,
        reduceMotion: Boolean
    ) {
        val metrics = metrics ?: return
        val volumes = volumes ?: return
        val palette = palette ?: return
        val count = volumes.size
        if (count <= 0) return
        val width = scope.size.width
        val focus = metrics.focusOf(logicalFocus, count)
        val poolCount = metrics.poolCountOf(count)
        val first = floor(focus - metrics.edgeSlots - 2f).toInt().coerceAtLeast(0)
        val last = ceil(focus + metrics.edgeSlots + 2f).toInt().coerceAtMost(poolCount - 1)
        if (last < first) return
        if (slotX.size < last - first + 1) slotX = FloatArray(last - first + 1).also {
            slotY = FloatArray(it.size)
            slotInk = FloatArray(it.size)
            slotIndices = IntArray(it.size)
        }
        val cull = metrics.coverPx * 1.8f
        val entranceValue = if (reduceMotion) 1f else entrance

        scope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            var n = 0
            // ---- 地面墨影：全部先画（archive_wave.dart L1519）----
            for (slot in first..last) {
                val distance = slot - focus
                // 注意：参考的 _floor 用的是 _offset（x_i - focus*pitch, railY），
                // 不含 _origin —— 影子固定在地面，书被拉出/抬起时不动。
                val restX = metrics.startXPx + distance * metrics.pitchPx
                val restY = metrics.railY(distance)
                val ox = restX + metrics.originX(distance)
                if (ox > width + cull || ox + metrics.footprintPx < -cull) continue
                val oy = restY + metrics.originY(distance)
                val ink = if (reduceMotion) 1f else {
                    inkProgressOf(entranceValue, inkStartOf(distance))
                }
                if (ink <= .01f) continue
                if (n >= slotX.size) break
                slotIndices[n] = slot
                slotX[n] = ox
                slotInk[n] = ink
                slotY[n] = oy + if (reduceMotion) 0f else {
                    loadingLiftPx(frameNanos, distance, 1f - ink, metrics.density)
                }
                n++
                val save = native.save()
                native.translate(restX, restY + metrics.baseYPx + metrics.bodyPx)
                native.drawPath(floorPath, floorFill)
                native.restoreToCount(save)
            }
            // ---- 实体：槽位升序 = 左在后、右在前（固定遮挡序）----
            for (index in 0 until n) {
                val volume = volumes[floorMod(slotIndices[index] - metrics.edgeSlots, count)]
                drawVolume(native, volume, slotX[index], slotY[index], slotInk[index], metrics, palette)
            }
        }
    }

    private fun drawVolume(
        native: android.graphics.Canvas,
        volume: ShelfVolume,
        ox: Float,
        oy: Float,
        ink: Float,
        metrics: ShelfMetrics,
        palette: ShelfPalette
    ) {
        val cover = metrics.coverPx
        val body = metrics.bodyPx
        val spine = metrics.spinePx
        if (cover < 4f || body < 4f || spine < 2f) return
        val alpha = (255f * ink).roundToInt().coerceIn(0, 255)

        // ---- 1) 封面面：_coverTransform (archive_wave.dart L1689) ----
        val sx = ox + spine
        val sy = oy + spine * .10f
        values[0] = METRICS_DEPTH_X; values[1] = 0f; values[2] = sx
        values[3] = METRICS_DEPTH_Y; values[4] = 1f; values[5] = sy
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        val coverSave = native.save()
        native.concat(matrix)
        val imageLeft = palette.coverPadH
        val imageTop = palette.coverPadV + palette.codeLine + palette.gapCoverCode
        val imageWidth = (cover - palette.coverPadH * 2f).coerceAtLeast(1f)
        val imageHeight = imageWidth * 166f / 118f

        fill.color = palette.coverFace
        fill.alpha = alpha
        native.drawRoundRect(0f, 0f, cover, body, palette.faceRadius, palette.faceRadius, fill)

        val bitmap = covers?.get(volume.id)
        if (bitmap != null) {
            source.set(0, 0, bitmap.width, bitmap.height)
            destination.set(
                imageLeft.roundToInt(),
                imageTop.roundToInt(),
                (imageLeft + imageWidth).roundToInt(),
                (imageTop + imageHeight).roundToInt()
            )
            bitmapPaint.alpha = alpha
            val imageSave = native.save()
            native.clipRect(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)
            native.drawBitmap(bitmap.asAndroidBitmap(), source, destination, bitmapPaint)
            native.restoreToCount(imageSave)
        } else {
            // 没有封面图时保持"纸"的观感，绝不整面纯白。
            val imageSave = native.save()
            native.clipRect(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight)
            placeholder.alpha = alpha
            native.drawRect(imageLeft, imageTop, imageLeft + imageWidth, imageTop + imageHeight, placeholder)
            drawTitleFallback(native, volume, imageLeft, imageTop, imageWidth, imageHeight, palette, ink)
            native.restoreToCount(imageSave)
        }

        // NO.xxx
        codePaint.textSize = palette.codeSize
        codePaint.color = palette.secondary
        codePaint.alpha = alpha
        native.drawText(
            volume.coverCode,
            imageLeft,
            baseline(codePaint, palette.coverPadV, palette.codeLine),
            codePaint
        )
        // WK-00000
        native.drawText(
            volume.fileCode,
            imageLeft,
            baseline(codePaint, body - palette.coverPadBottom - palette.codeLine, palette.codeLine),
            codePaint
        )
        drawRegistration(native, cover, body, palette, ink)
        hairline.color = palette.border
        hairline.alpha = alpha
        native.drawRoundRect(
            palette.hairline * .5f,
            palette.hairline * .5f,
            cover - palette.hairline * .5f,
            body - palette.hairline * .5f,
            palette.faceRadius,
            palette.faceRadius,
            hairline
        )
        native.restoreToCount(coverSave)

        // ---- 2) 顶面：只用平移（archive_wave.dart L1826）----
        val capSave = native.save()
        native.translate(ox, oy)
        capFill.alpha = alpha
        native.drawPath(capPath, capFill)
        capStroke.alpha = (255f * palette.capStrokeAlpha * ink).roundToInt().coerceIn(0, 255)
        native.drawPath(capPath, capStroke)
        native.restoreToCount(capSave)

        // ---- 3) 书脊面：_base (archive_wave.dart L1679) ----
        values[0] = 1f; values[1] = 0f; values[2] = ox
        values[3] = .10f; values[4] = 1f; values[5] = oy
        values[6] = 0f; values[7] = 0f; values[8] = 1f
        matrix.setValues(values)
        val spineSave = native.save()
        native.concat(matrix)
        fill.color = palette.spineFace
        fill.alpha = alpha
        native.drawRect(0f, 0f, spine, body, fill)
        val clip = native.save()
        native.clipRect(0f, 0f, spine, body)
        drawSpineNumber(native, volume, spine, palette, alpha)
        drawSpineTitle(native, volume, spine, body, palette, ink, alpha)
        dotPaint.color = palette.secondary
        dotPaint.alpha = (255f * .9f * ink).roundToInt().coerceIn(0, 255)
        native.drawCircle(
            spine * .5f,
            body - palette.spinePadV - palette.dotRadius,
            palette.dotRadius,
            dotPaint
        )
        native.restoreToCount(clip)
        hairline.color = palette.border
        hairline.alpha = alpha
        native.drawRect(
            palette.hairline * .5f,
            palette.hairline * .5f,
            spine - palette.hairline * .5f,
            body - palette.hairline * .5f,
            hairline
        )
        native.restoreToCount(spineSave)
    }

    /** archive_wave.dart L692：FittedBox(scaleDown) 的 01。 */
    private fun drawSpineNumber(
        native: android.graphics.Canvas,
        volume: ShelfVolume,
        spine: Float,
        palette: ShelfPalette,
        alpha: Int
    ) {
        codePaint.textSize = palette.codeSize
        codePaint.letterSpacing = palette.codeSpacing
        codePaint.color = palette.secondary
        codePaint.alpha = alpha
        val available = (spine - palette.spinePadH * 2f).coerceAtLeast(1f)
        val width = codePaint.measureText(volume.number)
        if (width > available && width > 0f) {
            codePaint.textSize = palette.codeSize * (available / width)
        }
        val measured = codePaint.measureText(volume.number)
        native.drawText(
            volume.number,
            spine * .5f - measured / 2f,
            baseline(codePaint, palette.spinePadV, palette.codeLine),
            codePaint
        )
    }

    /**
     * archive_spine_title.dart L25：竖排标题。列在书脊内居中，
     * FittedBox(scaleDown) 只在放不下时缩小；拉丁段整体旋转 90°。
     */
    private fun drawSpineTitle(
        native: android.graphics.Canvas,
        volume: ShelfVolume,
        spine: Float,
        body: Float,
        palette: ShelfPalette,
        ink: Float,
        alpha: Int
    ) {
        val items = volume.runs.size + if (volume.truncated) 1 else 0
        if (items <= 0) return
        val bandTop = palette.spinePadV + palette.codeLine + palette.gapAfterNumber
        val bandBottom = body - palette.spinePadV - palette.dotSize - palette.gapBeforeDot
        val band = bandBottom - bandTop
        if (band <= palette.titleLine) return
        val needed = palette.titleLine * items
        val scale = if (needed > band) band / needed else 1f
        if (scale <= .30f) return
        val size = palette.titleSize * scale
        val line = palette.titleLine * scale
        titlePaint.textSize = size
        titlePaint.color = palette.ink
        titlePaint.alpha = alpha
        titlePaint.isFakeBoldText = true
        titlePaint.getFontMetrics(fontMetrics)
        // 单行垂直居中：baseline = 中心 - (ascent + descent) / 2
        val centerOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f
        var top = bandTop + (band - line * items) / 2f
        for (i in 0 until items) {
            val centerY = top + line / 2f
            val run = volume.runs.getOrNull(i)
            val text = if (run == null) "…" else run.text
            val latin = run?.latin == true
            val width = measure(titlePaint, text, scale >= .999f)
            val baselineY = centerY + centerOffset
            if (latin) {
                val save = native.save()
                native.rotate(90f, spine * .5f, centerY)
                native.drawText(text, spine * .5f - width / 2f, baselineY, titlePaint)
                native.restoreToCount(save)
            } else {
                native.drawText(text, spine * .5f - width / 2f, baselineY, titlePaint)
            }
            top += line
        }
    }

    private fun drawTitleFallback(
        native: android.graphics.Canvas,
        volume: ShelfVolume,
        left: Float,
        top: Float,
        width: Float,
        height: Float,
        palette: ShelfPalette,
        ink: Float
    ) {
        titlePaint.textSize = palette.titleSize * 1.35f
        titlePaint.color = palette.ink
        titlePaint.alpha = (255f * .48f * ink).roundToInt().coerceIn(0, 255)
        titlePaint.isFakeBoldText = true
        val text = volume.title.take(4)
        val measured = titlePaint.measureText(text)
        native.drawText(
            text,
            left + (width - measured) / 2f,
            top + height / 2f + palette.titleSize * .5f,
            titlePaint
        )
    }

    private fun drawRegistration(
        native: android.graphics.Canvas,
        cover: Float,
        body: Float,
        palette: ShelfPalette,
        ink: Float
    ) {
        if (registrationPath.isEmpty) return
        hairline.color = palette.ink
        hairline.alpha = (255f * .42f * ink).roundToInt().coerceIn(0, 255)
        hairline.strokeWidth = palette.hairline * .8f
        native.drawPath(registrationPath, hairline)
        hairline.strokeWidth = palette.hairline
        hairline.color = palette.border
    }

    private fun baseline(paint: Paint, top: Float, lineHeight: Float): Float {
        paint.getFontMetrics(fontMetrics)
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        return top + (lineHeight - textHeight) / 2f - fontMetrics.ascent
    }

    private fun measure(paint: Paint, text: String, cache: Boolean): Float {
        if (!cache) return paint.measureText(text)
        return widthCache.getOrPut(text) { paint.measureText(text) }
    }
}

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (android.graphics.Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
    return (color and 0x00ffffff) or (a shl 24)
}

private fun Modifier.shelfSemanticBox(
    index: Int,
    logicalFocus: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    metrics: ShelfMetrics,
    count: Int
): Modifier = layout { measurable, constraints ->
    val widthPx = metrics.footprintPx.roundToInt()
    val heightPx = (metrics.bodyPx + metrics.coverPx * (-METRICS_DEPTH_Y)).roundToInt()
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = widthPx,
            maxWidth = widthPx,
            minHeight = heightPx,
            maxHeight = heightPx
        )
    )
    val focus = metrics.focusOf(logicalFocus.value, count)
    val base = metrics.edgeSlots + index
    val slot = if (count <= 1) 0 else {
        val k = ((focus - base) / count.toFloat()).roundToInt()
        base + k * count
    }
    val distance = slot - focus
    val left = (metrics.startXPx + distance * metrics.pitchPx + metrics.originX(distance)).roundToInt()
    val top = (metrics.railY(distance) + metrics.originY(distance) + metrics.coverPx * METRICS_DEPTH_Y).roundToInt()
    layout(widthPx, heightPx) {
        placeable.place(left, top)
    }
}
