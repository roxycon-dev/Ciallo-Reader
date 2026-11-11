package com.example.ui.wenku

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.positionInRoot
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

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
    showChrome: Boolean = true,
    scopeKey: String = "shelf"
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
    // 封面贴图是画的时候才读的普通对象，画布不会自己去失效；
    // 每光栅化一批就把它 +1，让绘制块重新跑一遍（否则新贴图要等到下次动效才显示）。
    var coverGeneration by remember { mutableIntStateOf(0) }
    // 连点保护：飞行期间不再拿起第二本。
    var openingLocked by remember { mutableStateOf(false) }

    val starting = remember(shelfBooks, initialIndex) {
        // 打开一本书再返回时，必须回到刚才那本：先看调用方给的 index，
        // 再看本作用域的内存（跨重组/跨返回都活着），最后才按最近阅读时间兜底。
        (initialIndex
            ?: ArchiveShelfMemory.indexFor(scopeKey, shelfBooks)
            ?: shelfBooks.indexOf(shelfBooks.maxByOrNull { it.lastReadTime } ?: shelfBooks.first()))
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
        logicalFocus.snapTo(wrapFocus(logicalFocus.value, shelfBooks.size))
    }
    // 无限轮播：logicalFocus 可以无限增大/减小（12 后面就是 1、2、3…），
    // 书号一律对 count 取模；这里只把"当前是哪一本"折回 0..count-1。
    val activeIndex by remember(shelfBooks.size) {
        derivedStateOf { floorMod(logicalFocus.value.roundToInt(), shelfBooks.size) }
    }
    // 记住停在哪一本（按作用域分开：书架 / 我喜欢的各记各的），
    // 这样打开阅读器再返回、或切去别的 Tab 再回来，都停在刚才那本。
    LaunchedEffect(scopeKey, activeIndex, shelfBooks.size) {
        shelfBooks.getOrNull(activeIndex)?.let { ArchiveShelfMemory.remember(scopeKey, it.id) }
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
        // 封面贴图 = 封面面本身的比例（cover × body），满版裁切，不留纸边。
        val bitmapWidth = metrics.coverPx.roundToInt().coerceIn(64, 288)
        val bitmapHeight = (bitmapWidth * metrics.bodyPx / metrics.coverPx).roundToInt().coerceIn(64, 512)
        val warm = remember(activeIndex, shelfBooks.size, metrics) { shelfWarmWindow(activeIndex, shelfBooks.size, metrics) }
        // 封面每本只光栅化一次（Painter -> ImageBitmap），draw 阶段只做矩阵贴图。
        // 没有封面的书在这里生成一张印刷风封面（同一张图给 3D 书 + 飞行动画 + 阅读器落点），
        // 之后再也不会出现"打开时变成纯黑一块"。
        // 每帧最多补几本：一次性把 18 本都光栅化会在切书那一帧掉十几毫秒，
        // 分批补完既不掉帧，也不会出现"某本书没有封面"的中间态（见 shelfWarmWindow）。
        SideEffect {
            val portrait = Density(density.density)
            var budget = COVER_RASTER_BUDGET
            var changed = false
            for (index in warm) {
                val book = shelfBooks[index]
                if (covers.get(book.id) != null && !covers.isPlaceholder(book.id)) continue
                if (budget <= 0) break
                val ready = coverReady.getOrElse(index) { false }
                val raster = if (ready) {
                    rasterizeShelfCover(painters.getOrNull(index), bitmapWidth, bitmapHeight, portrait)
                } else null
                if (raster != null) {
                    covers.put(book.id, raster)
                    changed = true
                    budget--
                } else if (covers.get(book.id) == null) {
                    // 解码还没回来：先给一张生成封面顶上，真封面到达后由上面的分支替换。
                    covers.put(
                        book.id,
                        generateShelfCover(
                            title = book.title,
                            author = book.author,
                            tag = if (book.isComic) "COMIC" else "TXT",
                            dark = colors.isDark,
                            widthPx = bitmapWidth,
                            heightPx = bitmapHeight,
                            density = portrait
                        ),
                        placeholder = true
                    )
                    changed = true
                    budget--
                }
            }
            // 补到东西就 +1：组合重跑一轮，接着补下一批（每轮最多 COVER_RASTER_BUDGET 本）。
            if (changed) coverGeneration++
        }

        val center = metrics.startXPx + metrics.pullPx * METRICS_DEPTH_X

        // 画布在 root 里的位置：飞行落点必须用 root 坐标，不能拿行内坐标直接喂给覆盖层。
        // 普通 FloatArray 而非 state：onGloballyPositioned 在布局阶段写 state 会触发
        // 「布局→重组→布局」死循环（R15 实测：切列表模式直接 ANR），这里只在点击时读。
        val sceneOrigin = remember { FloatArray(2) }

        /**
         * 把这本书整本交给根覆盖层：**同一个渲染器、同一条时间轴**，从书架上的斜书
         * 一路画到铺满全屏。书架这边只要在起飞后不再画它，交接处就没有第二个渲染器可言。
         */
        fun launchVolume(book: Book, index: Int): Boolean {
            if (book.isComic) return false
            val art = scene.artAt(index) ?: return false
            // 书架上的静止姿态（行内坐标）→ 换成 root 坐标，覆盖层拿到的第一帧和刚看到的一模一样。
            val restOx = slotOriginX(metrics, logicalFocus.value, shelfBooks.size, index) + sceneOrigin[0]
            val restOy = slotOriginY(metrics, logicalFocus.value, shelfBooks.size, index) + sceneOrigin[1]
            return com.example.ui.wenku.WenkuVolumeFlightBridge.begin(
                bookKey = book.id.toString(),
                bookId = book.id,
                art = art,
                restOx = restOx,
                restOy = restOy,
                onArrive = { onBookClick(book) }
            )
        }

        fun openBook(book: Book) {
            val index = shelfBooks.indexOf(book)
            if (index < 0) {
                onBookClick(book)
                return
            }
            ArchiveShelfMemory.remember(scopeKey, book.id)
            if (openingLocked) return
            // 起飞失败（没有封面贴图 / 素材还没准备好 / 减少动效）：直接进阅读器，
            // 不能出现"点了没反应"。
            if (a11y.reduceMotion || !launchVolume(book, index)) {
                onBookClick(book)
                return
            }
            openingLocked = true
            scope.launch {
                // 解锁放在飞行结束之后：连点不会把两本书同时拿起来。
                com.example.ui.wenku.WenkuVolumeFlightBridge.awaitIdle(book.id.toString())
                openingLocked = false
            }
        }

        fun centre(target: Int) {
            val count = shelfBooks.size
            if (count < 2) return
            // 无限轮播：同一本书有无数个等价槽位，取离当前焦点最近的那个，
            // 于是"下一本"从最后一本跨到第一本只需要动一格，而不是倒着扫回去。
            val base = floorMod(target, count)
            val k = ((logicalFocus.value - base) / count).roundToInt()
            val destination = base + k * count
            if (abs(logicalFocus.value - destination) < .01f) return
            scope.launch {
                if (a11y.reduceMotion) {
                    logicalFocus.snapTo(wrapFocus(destination.toFloat(), count))
                } else {
                    logicalFocus.animateTo(
                        destination.toFloat(),
                        tween(WenkuMotion.ENTER_MS, easing = SHELF_CENTRE_CURVE)
                    )
                    // 动画结束后折回 0 起步，避免焦点数值无限增长。
                    logicalFocus.snapTo(wrapFocus(logicalFocus.value, count))
                }
            }
        }

        fun slotAt(point: Offset): Int? {
            val count = shelfBooks.size
            if (count <= 0) return null
            val focus = metrics.focusOf(logicalFocus.value, count)
            val first = floor(focus - metrics.edgeSlots - 2f).toInt()
            val last = ceil(focus + metrics.edgeSlots + 2f).toInt()
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
                val centerX = metrics.startXPx + metrics.pullPx * METRICS_DEPTH_X
                if (count <= 1 || abs(point.x - centerX) <= metrics.coverPx * .42f) {
                    shelfBooks.getOrNull(activeIndex)?.let(::openBook)
                }
                return
            }
            // archive_wave.dart L161 _tap: 命中的就是中心书才打开，否则先滚到中心。
            val bookIndex = metrics.bookIndexOf(slot, count)
            if (abs(slot - focus) < .30f) shelfBooks.getOrNull(bookIndex)?.let(::openBook)
            else centre(bookIndex)
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
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInRoot()
                            sceneOrigin[0] = origin.x
                            sceneOrigin[1] = origin.y
                        }
                        .drawWithCache {
                            // 读一次 coverGeneration：贴图补上后重新构建缓存块并重画，
                            // 不然新光栅化的封面要等到下一次动效才出现。
                            @Suppress("UNUSED_VARIABLE") val coversVersion = coverGeneration
                            scene.bind(metrics, volumes, covers, colors, coverGeneration)
                            onDrawBehind {
                                // 在**绘制阶段**读飞行状态：进度每帧都变，读在组合里会让整个
                                // 书架每帧重组；读在这里只会让这一块画布重绘。
                                val flight = com.example.ui.wenku.WenkuVolumeFlightBridge.request
                                scene.draw(
                                    scope = this,
                                    logicalFocus = logicalFocus.value,
                                    entrance = reveal.value,
                                    frameNanos = frameNanos.value,
                                    reduceMotion = a11y.reduceMotion,
                                    flyingBookId = flight?.bookId,
                                    flyingProgress = com.example.ui.wenku.WenkuVolumeFlightBridge.progress
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
                            .semantics {
                                role = Role.Button
                                selected = selectedNow
                                contentDescription =
                                    "《${book.title}》，${book.author.ifBlank { com.example.i18n.AppLocale.t("未知作者") }}${if (selectedNow) com.example.i18n.AppLocale.t("，当前选中") else ""}"
                                onClick(label = com.example.i18n.AppLocale.t("打开")) {
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
                                        // 无限轮播：不夹在 0..last，划到最后一本继续划就是第 1 本。
                                        val next = logicalFocus.value - amount / metrics.pitchPx
                                        scope.launch { logicalFocus.snapTo(next) }
                                    }
                                }
                                if (!moved && !cancelled) {
                                    tap(down.position)
                                } else if (horizontal) {
                                    // ⚠️ 符号：拖拽里 `next = focus - amount/pitch`，即"手指往左
                                    // （amount<0）→ 焦点往后翻"。惯性模拟的 position 用的就是
                                    // focus*pitch，所以必须喂**取反后的**手指速度；直接喂
                                    // tracker 的 x 速度会让松手瞬间往反方向甩，表现为
                                    // "怎么划都到不了尽头 / 划一下又被拉回来"。
                                    val velocity = -tracker.calculateVelocity().x
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
                                        val target = simulation.target / metrics.pitchPx
                                        val fromPx = from * metrics.pitchPx
                                        val toPx = target * metrics.pitchPx
                                        hapticsPerform(haptics)
                                        if (abs(toPx - fromPx) < .01f) {
                                            scope.launch {
                                                logicalFocus.snapTo(wrapFocus(target, shelfBooks.size))
                                            }
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
                                                // 落定后折回 0..count，渲染对 ±count 平移不变，看不出跳变。
                                                logicalFocus.snapTo(wrapFocus(logicalFocus.value, shelfBooks.size))
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
    val str = com.example.i18n.LocalStrings.current
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
                        append(book.author.ifBlank { str.t("未知作者") })
                        if (book.totalChapters > 0) {
                            append(" · " + str.chapters((book.currentChapterIndex + 1).coerceAtMost(book.totalChapters), book.totalChapters))
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
                    label = str.t("上一本"),
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
                    label = str.t("下一本"),
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
            // 收藏板块的本地缩略图是**裸路径**（`/data/data/…/thumb.jpg`），
            // 而 Book.isCoverValid 只对数据库里的本地书有意义 —— 收藏是运行时拼出来的
            // Book，这个字段天然是 false。过去这里直接判死，收藏 3D 书架就全变空白纸卡。
            else -> {
                val path = if (uri.startsWith("file://")) uri.substring(7) else uri
                java.io.File(path)
            }
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
private const val METRICS_DEPTH_Y = -.16f
private const val METRICS_ROW_STEP = 2.5f
private const val METRICS_RADIUS = 1.48f
private val METRICS_MAX_VIEWPORT = 520.dp
private val SHELF_CENTRE_CURVE = CubicBezierEasing(.2f, 1.06f, .35f, 1f)

/** 平滑起停（smoothstep）：0 处导数为 0，动画第一帧不会有速度跳变。 */
private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

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

    /**
     * 槽位 → 书号。**无限轮播**：取模回绕，12 的右边接回 1、2、3…
     *
     * 这样任何槽位都有书，滑到最后一本继续滑就是第 1 本 —— 和初版一致。
     * 焦点书永远在中央，由 [focusOf] 保证。
     */
    fun bookIndexOf(slot: Int, count: Int): Int =
        if (count < 2) 0 else floorMod(slot - edgeSlots, count)
}

/** 取模到 `0 until count`（负数也落到正区间）。 */
private fun floorMod(value: Int, count: Int): Int {
    if (count <= 0) return 0
    val result = value % count
    return if (result < 0) result + count else result
}

/** 把无限增长的焦点折回 `0 until count`；小数部分保留，渲染对 ±count 平移不变。 */
private fun wrapFocus(value: Float, count: Int): Float {
    if (count <= 0) return 0f
    val result = value % count
    return if (result < 0f) result + count else result
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
    val truncated: Boolean,
    /** 全书章数：厚度映射的唯一真实来源（0 = 不知道，按 id 稳定兜底）。 */
    val chapters: Int
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
                truncated = all.size > 7,
                chapters = book.totalChapters
            )
        }
    }
}

// ---------------------------------------------------------------------------
// cover bitmaps
// ---------------------------------------------------------------------------

/**
 * 每本书一张"封面面像素"：真实封面按封面比例中心裁切，或由 [generateShelfCover]
 * 生成。3D 书、飞行贴图、阅读器落点共用同一张，颜色与纸质不会三处不一致。
 */
private class ShelfCoverStore(private val capacity: Int = 64) {
    private val entries = LinkedHashMap<Int, ImageBitmap>(16, .75f, true)
    private val tints = HashMap<Int, Int>()
    private val placeholders = HashSet<Int>()

    fun get(bookId: Int): ImageBitmap? = entries[bookId]

    /** true = 当前这张是生成的兜底封面，真封面解码完成后要顶掉它。 */
    fun isPlaceholder(bookId: Int): Boolean = placeholders.contains(bookId)

    /** 书脊包裹色：取封面左侧竖带的平均色；生成封面同样适用。 */
    fun tintOf(bookId: Int): Int? = tints[bookId]

    fun put(bookId: Int, image: ImageBitmap, placeholder: Boolean = false) {
        entries[bookId] = image
        if (placeholder) placeholders.add(bookId) else placeholders.remove(bookId)
        tints[bookId] = dominantEdgeColor(image)
        while (entries.size > capacity) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            placeholders.remove(eldest.key)
            tints.remove(eldest.key)
            iterator.remove()
        }
    }
}

/**
 * **会被真正画到屏幕上的书号**，按"离焦点由近到远"排序，用于预光栅化封面。
 *
 * 之前这里是死板的 `active ± 5`：而一行里真正画出来的是 `focus ± (edgeSlots + 2)` 个槽位
 * （411dp 屏上是 18 个槽位、屏幕上同时能看到 18 本），12 本书会**全部**出现在屏幕里。
 * 于是落在 ±5 之外的那本永远拿不到封面贴图，`drawVolume` 只能填 `fallbackTint`，
 * 其中 1/3 的 id 会拿到深蓝灰 —— 用户看到的就是"中间书右边某一本变成了黑封面"。
 * 退出阅读器再回来时最明显：那时以刚读完的那本为中心，±5 的窗口刚好把某一本挤出去。
 */
private fun shelfWarmWindow(active: Int, count: Int, metrics: ShelfMetrics): List<Int> {
    if (count <= 0) return emptyList()
    val result = LinkedHashSet<Int>(count.coerceAtMost(24))
    val reach = metrics.edgeSlots + 2
    val cull = metrics.coverPx * 1.8f
    // 由近到远：0、+1、-1、+2、-2 …（近处的书先补，远处即使这一轮没补到也看不出）
    for (step in 0..reach) {
        for (d in if (step == 0) intArrayOf(0) else intArrayOf(step, -step)) {
            val x = metrics.startXPx + d * metrics.pitchPx + metrics.originX(d.toFloat())
            if (x > metrics.viewportPx + cull) continue
            if (x + metrics.footprintPx < -cull) continue
            result.add(floorMod(active + d, count))
            if (result.size >= count) return result.toList()
        }
    }
    return result.toList()
}

/** 每轮组合最多光栅化几本封面：18 本一次画完会在切书那一帧掉十几毫秒。 */
private const val COVER_RASTER_BUDGET = 6

// ---------------------------------------------------------------------------
// painting
// ---------------------------------------------------------------------------

/**
 * 没有封面贴图时的兜底书脊/封面色。**必须是纸色系**：
 * 以前这里会给 1/3 的书 `#3F4A56` 深蓝灰，只要那本书的贴图晚到一帧，
 * 画出来就是一整块"黑封面"。宁可像一本空白纸书，也不要像一块黑砖。
 */
private fun fallbackTint(volume: ShelfVolume): Int = when (abs(volume.id) % 4) {
    0 -> 0xFFEFE9DC.toInt()
    1 -> 0xFFD9C9AE.toInt()
    2 -> 0xFFC9C2B4.toInt()
    else -> 0xFFDCD3C2.toInt()
}

/** 打开一本书之后，回到书架要停在它身上 —— 按作用域记住最后停下的那本。 */
internal object ArchiveShelfMemory {
    private val focus = HashMap<String, Int>()

    fun remember(scope: String, bookId: Int) {
        focus[scope] = bookId
    }

    fun indexFor(scope: String, books: List<Book>): Int? {
        val id = focus[scope] ?: return null
        val index = books.indexOfFirst { it.id == id }
        return if (index >= 0) index else null
    }
}

/** 某一本书"离焦点最近的那个副本"的槽位号（无限轮播里同一本有多份）。 */
private fun shelfSlotOf(metrics: ShelfMetrics, logicalFocus: Float, count: Int, index: Int): Float {
    if (count <= 1) return 0f
    val focus = metrics.focusOf(logicalFocus, count)
    val base = metrics.edgeSlots + index
    val k = ((focus - base) / count.toFloat()).roundToInt()
    return (base + k * count).toFloat()
}

/** 那本书在画布里的左上角 x（行内坐标 = 与绘制用的一致）。 */
private fun slotOriginX(metrics: ShelfMetrics, logicalFocus: Float, count: Int, index: Int): Float {
    val distance = shelfSlotOf(metrics, logicalFocus, count, index) - metrics.focusOf(logicalFocus, count)
    return metrics.startXPx + distance * metrics.pitchPx + metrics.originX(distance)
}

/** 那本书在画布里的左上角 y（不含入场时的浮动，静止态即最终值）。 */
private fun slotOriginY(metrics: ShelfMetrics, logicalFocus: Float, count: Int, index: Int): Float {
    val distance = shelfSlotOf(metrics, logicalFocus, count, index) - metrics.focusOf(logicalFocus, count)
    return metrics.railY(distance) + metrics.originY(distance)
}

private class ShelfScenePainter {
    private var metrics: ShelfMetrics? = null
    private var volumes: List<ShelfVolume>? = null
    private var covers: ShelfCoverStore? = null
    private var palette: ShelfPalette? = null
    private var paletteDark: Boolean? = null
    private var arts: List<ShelfVolumeArt> = emptyList()
    private var artGeneration: Int = -1

    private val artist = ShelfVolumeArtist()
    private val floorPath = Path()
    private val floorBounds = RectF()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val floorFill = Paint(Paint.ANTI_ALIAS_FLAG)

    private var slotIndices = IntArray(192)
    private var slotX = FloatArray(192)
    private var slotY = FloatArray(192)
    private var slotInk = FloatArray(192)

    fun bind(
        metrics: ShelfMetrics,
        volumes: List<ShelfVolume>,
        covers: ShelfCoverStore,
        colors: WenkuColors,
        generation: Int
    ) {
        val sameGeometry = this.metrics === metrics
        val sameInk = this.paletteDark == colors.isDark
        this.covers = covers
        if (sameGeometry && sameInk && this.volumes === volumes && this.artGeneration == generation) return
        this.metrics = metrics
        this.volumes = volumes
        this.paletteDark = colors.isDark
        this.artGeneration = generation
        val palette = ShelfPalette(colors, metrics.density)
        this.palette = palette
        buildFloor(metrics, palette)
        artist.clearCache()
        // 每本书的绘制素材（封面贴图 / 主色 / 厚度 / 材质）在 bind 时定下来：
        // 书架每帧只做矩阵变换，覆盖层拿到的是同一份 art 去画飞行中的那一本。
        arts = volumes.map { volume -> artFor(volume, metrics, covers, palette) }
    }

    /** 覆盖层起飞时用：把某一本的绘制素材交出去（和书架上画的那份是同一份数据）。 */
    fun artAt(bookIndex: Int): ShelfVolumeArt? = arts.getOrNull(bookIndex)

    private fun artFor(
        volume: ShelfVolume,
        metrics: ShelfMetrics,
        covers: ShelfCoverStore,
        palette: ShelfPalette
    ): ShelfVolumeArt {
        val heightScale = volumeHeightScale(volume.id)
        return ShelfVolumeArt(
            id = volume.id,
            coverWidth = metrics.coverPx,
            body = metrics.bodyPx * heightScale,
            thickness = volumeThicknessPx(volume.chapters, volume.id, metrics.density),
            topInset = metrics.bodyPx * (heightScale - 1f),
            tint = covers.tintOf(volume.id) ?: fallbackTint(volume),
            material = volumeMaterial(volume.id, volume.title.length),
            cover = covers.get(volume.id),
            number = volume.number,
            fileCode = volume.fileCode,
            runs = volume.runs,
            truncated = volume.truncated,
            palette = palette,
            unit = metrics.density
        )
    }

    private fun buildFloor(metrics: ShelfMetrics, palette: ShelfPalette) {
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
        // 地面墨影必须带渐变：右上方 10% 墨色 → 左下方全透明（archive_wave.dart L1492）。
        // 少了这个 shader，floorFill 就是一块不透明纯黑多边形 —— 每本书脚下那几根
        // "不明黑色线条"就是它。
        floorPath.computeBounds(floorBounds, true)
        floorFill.shader = LinearGradient(
            floorBounds.right,
            floorBounds.top,
            floorBounds.left,
            floorBounds.bottom,
            withAlpha(palette.floorInk, .10f),
            withAlpha(palette.floorInk, 0f),
            Shader.TileMode.CLAMP
        )
    }

    fun draw(
        scope: DrawScope,
        logicalFocus: Float,
        entrance: Float,
        frameNanos: Long,
        reduceMotion: Boolean,
        /** 正在被"拿起来"的那本：它的**最近副本**不在书架上画（覆盖层在同一个坐标上画它）。 */
        flyingBookId: Int?,
        flyingProgress: Float
    ) {
        val metrics = metrics ?: return
        val volumes = volumes ?: return
        val palette = palette ?: return
        val count = volumes.size
        if (count <= 0) return
        val width = scope.size.width
        val focus = metrics.focusOf(logicalFocus, count)
        val first = floor(focus - metrics.edgeSlots - 2f).toInt()
        val last = ceil(focus + metrics.edgeSlots + 2f).toInt()
        if (last < first) return
        if (slotX.size < last - first + 1) slotX = FloatArray(last - first + 1).also {
            slotY = FloatArray(it.size)
            slotInk = FloatArray(it.size)
            slotIndices = IntArray(it.size)
        }
        val cull = metrics.coverPx * 1.8f
        val entranceValue = if (reduceMotion) 1f else entrance
        // 起飞前的一小段还留在书架上画（与覆盖层的参数逐位相同，两层重叠 = 一层），
        // 之后交给覆盖层，书架这边只留另一侧的那份副本。
        val hideFlying = flyingBookId != null && 
            flyingProgress > com.example.ui.wenku.WenkuVolumeFlightBridge.HANDOFF_P

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
            // ---- 实体：按槽位升序（左邻在后、右邻在前），这是静止时的遮挡关系 ----
            // 被拿起来的那本由覆盖层画，并且画在所有书之上 —— 它已经被"拿出手"了。
            var hiddenEntry = -1
            if (hideFlying && flyingBookId != null) {
                var bestDistance = Float.MAX_VALUE
                for (index in 0 until n) {
                    val bookIndex = metrics.bookIndexOf(slotIndices[index], count)
                    if (volumes[bookIndex].id != flyingBookId) continue
                    val slotDistance = abs(slotIndices[index] - focus)
                    if (slotDistance < bestDistance) {
                        bestDistance = slotDistance
                        hiddenEntry = index
                    }
                }
            }
            for (index in 0 until n) {
                if (index == hiddenEntry) continue
                val bookIndex = metrics.bookIndexOf(slotIndices[index], count)
                artist.draw(
                    native,
                    arts.getOrNull(bookIndex) ?: continue,
                    ShelfVolumePose(
                        ox = slotX[index],
                        oy = slotY[index],
                        depthX = METRICS_DEPTH_X,
                        depthY = METRICS_DEPTH_Y,
                        skew = .10f,
                        thicknessScale = 1f,
                        detail = 1f,
                        alpha = slotInk[index],
                        scale = 1f
                    )
                )
            }
        }
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
    // 无限轮播：同号书在前后各有一份，取离焦点最近的那一份当语义盒。
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
