package com.example.ui.wenku

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.delay

/**
 * **3D 书架的打开 / 归位飞行（单渲染器）。**
 *
 * 旧实现是"书架画布把书转正 → 清空 → 另一个渲染器把封面贴图放大"。即使把几何、贴图、
 * 圆角、透明度都对到像素级，运动本身仍然是两段：第一段只有旋转、缩放几乎为 0，
 * 第二段缩放从 0 速度重新起步 —— 那个速度断点就是用户看到的"两段动画"。
 *
 * 现在整段动画只有一个渲染器（[ShelfVolumeArtist]）和一条路径（[ShelfOpenTimeline]）：
 * 点下去的那一刻，书架把这本书的**绘制素材 + 静止姿态**交给根覆盖层，覆盖层从
 * `p = 0`（与书架静止帧逐参数相同）一路画到 `p = 1`（封面铺满整屏）。书架在
 * `p > HANDOFF_P` 之后不再画它，交接处两边画的是同一套参数、同一份素材。
 */
internal data class WenkuVolumeFlightRequest(
    val bookKey: String,
    val bookId: Int,
    val art: ShelfVolumeArt,
    /** 在 root 坐标系里的静止位置（书左上后角）。 */
    val restOx: Float,
    val restOy: Float,
    /** 归位方向：封面从阅读器这一侧淡入，几何走同一条路径的反向。 */
    val returning: Boolean = false,
    /** 打开时封面铺满屏幕的那一帧要做什么（切路由）。 */
    val onArrive: (() -> Unit)? = null
) {
    /** 供覆盖层与书架共享的当前进度：`0` = 还在架子上，`1` = 铺满整屏。 */
    var progress: Float = 0f
}

internal object WenkuVolumeFlightBridge {
    /** 书架读它来决定"这本书现在不归我画"。 */
    var request by mutableStateOf<WenkuVolumeFlightRequest?>(null, referentialEqualityPolicy())
        private set

    /** 当前进度（Compose 状态：书架在绘制阶段读它，只失效绘制，不触发重组）。 */
    var progress by mutableFloatStateOf(0f)
        private set

    /**
     * 飞行期间不要播页面的横向滑动转场：封面已经盖住整屏，滑动只会在边缘露出来。
     * 普通 `var`（非快照状态）：NavHost 的 transition lambda 在导航那一刻读一次就够了。
     */
    @Volatile
    var suppressRouteMotion: Boolean = false

    /**
     * 书架在 `progress > HANDOFF_P` 之后不再画这本书。
     *
     * 必须是 0：`p = 0` 那一帧覆盖层画的和书架上一帧**逐像素相同**，所以让位本身看不出来。
     * 一旦等到 `p = .06` 才让位，书架那份还停在静止姿态、覆盖层已经把它挪了 3px，
     * 边上就会露出一条"重影"。
     */
    const val HANDOFF_P = 0f

    internal fun begin(
        bookKey: String,
        bookId: Int,
        art: ShelfVolumeArt,
        restOx: Float,
        restOy: Float,
        onArrive: (() -> Unit)? = null
    ): Boolean {
        if (art.cover == null) return false
        progress = 0f
        suppressRouteMotion = true
        request = WenkuVolumeFlightRequest(
            bookKey = bookKey,
            bookId = bookId,
            art = art,
            restOx = restOx,
            restOy = restOy,
            returning = false,
            onArrive = onArrive
        )
        return true
    }

    /** 归位：`last` 里存着起飞时的同一份素材与静止姿态，所以回程是去程的反向。 */
    internal fun reverse(bookKey: String): Boolean {
        val previous = request ?: last ?: return false
        if (previous.bookKey != bookKey) return false
        progress = 1f
        suppressRouteMotion = true
        request = previous.copy(returning = true, onArrive = null)
        return true
    }

    private var last: WenkuVolumeFlightRequest? = null

    internal fun publish(value: Float) {
        progress = value
        request?.progress = value
    }

    internal fun finished(current: WenkuVolumeFlightRequest) {
        if (request !== current) return
        if (!current.returning) last = current
        request = null
        progress = 0f
        suppressRouteMotion = false
    }

    fun cancel() {
        request = null
        progress = 0f
        last = null
        suppressRouteMotion = false
    }

    /** 等这次飞行彻底结束（书架用它解锁"连点保护"）。 */
    suspend fun awaitIdle(bookKey: String) {
        while (true) {
            val current = request
            if (current == null || current.bookKey != bookKey) return
            delay(16)
        }
    }
}

/**
 * 根覆盖层：飞行中的那本书由它**独占**绘制。
 *
 * 位置、缩放、转正、书脊收薄、封面工艺、透明度全部由 [ShelfOpenTimeline.poseAt] 给出，
 * 与书架静止帧共享同一个函数 —— 所以"第一帧"不是调得像，而是同一个求值结果。
 */
@Composable
fun WenkuVolumeFlightOverlay(modifier: Modifier = Modifier) {
    val a11y = rememberWenkuAccessibility()
    var viewport by remember { mutableStateOf<Rect?>(null) }
    val request = WenkuVolumeFlightBridge.request
    val artist = remember { ShelfVolumeArtist() }
    val clock = remember { Animatable(0f) }

    DisposableEffect(Unit) { onDispose { WenkuVolumeFlightBridge.cancel() } }

    LaunchedEffect(request, a11y.reduceMotion) {
        val current = request ?: return@LaunchedEffect
        if (a11y.reduceMotion) {
            // 减少动效：不飞行，直接切页面。
            current.onArrive?.invoke()
            WenkuVolumeFlightBridge.finished(current)
            return@LaunchedEffect
        }
        val duration = if (current.returning) {
            ShelfOpenTimeline.CLOSE_MS
        } else ShelfOpenTimeline.OPEN_MS
        val from = if (current.returning) 1f else 0f
        val to = if (current.returning) 0f else 1f
        clock.snapTo(from)
        WenkuVolumeFlightBridge.publish(from)
        var arrived = false
        clock.animateTo(to, tween(durationMillis = duration, easing = LinearEasing)) {
            val value = this.value
            WenkuVolumeFlightBridge.publish(value)
            if (!current.returning && !arrived && value >= ARRIVE_P) {
                arrived = true
                current.onArrive?.invoke()
            }
        }
        if (!current.returning && !arrived) current.onArrive?.invoke()
        WenkuVolumeFlightBridge.finished(current)
    }

    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val size = coordinates.size
                if (size.width > 0 && size.height > 0) {
                    viewport = Rect(
                        coordinates.positionInRoot(),
                        Size(size.width.toFloat(), size.height.toFloat())
                    )
                }
            }
            .then(
                if (request == null) Modifier else Modifier.drawFlightVolume(
                    request = request,
                    origin = viewport,
                    clock = clock,
                    artist = artist
                )
            )
    )
}

/** 只有真正在飞的时候才挂绘制修饰符：平时这个覆盖层是一张完全不占绘制成本的空盒子。 */
private fun Modifier.drawFlightVolume(
    request: WenkuVolumeFlightRequest,
    origin: Rect?,
    clock: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>,
    artist: ShelfVolumeArtist
): Modifier = drawWithContent {
    drawContent()
    val view = origin ?: return@drawWithContent
    val p = clock.value.coerceIn(0f, 1f)
    // 归位：几何 p 从 1 走回 0，封面用前 1/6 的时间从阅读器这一侧淡入（与打开时对称）。
    val alphaOverride = if (request.returning) {
        val q = 1f - p
        val t = (q / CLOSE_FADE_IN).coerceIn(0f, 1f)
        t * t * (3f - 2f * t)
    } else null
    val targetScale = ShelfOpenTimeline.fillScale(request.art, view.width, view.height)
    val pose = ShelfOpenTimeline.poseAt(
        p = p,
        art = request.art,
        restOx = request.restOx - view.left,
        restOy = request.restOy - view.top,
        targetCenterX = view.width * .5f,
        targetCenterY = view.height * .5f,
        targetScale = targetScale,
        alphaOverride = alphaOverride
    )
    drawIntoCanvas { canvas ->
        artist.draw(canvas.nativeCanvas, request.art, pose)
    }
}

/** 打开时封面铺满屏幕的进度：到这里就切路由（后面只剩淡出交给阅读器）。 */
private const val ARRIVE_P = .78f

/** 归位时封面盖住阅读器所需的时间比例（对应 [ShelfOpenTimeline.CLOSE_COVER_IN_MS]）。 */
private val CLOSE_FADE_IN = ShelfOpenTimeline.CLOSE_COVER_IN_MS.toFloat() / ShelfOpenTimeline.CLOSE_MS
