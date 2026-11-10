package com.example.ui.wenku

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 封面飞行的「两端数据桥」。
 *
 * 参考实现里 Hero 飞行由 Flutter 的 Overlay 自己完成（`PaperCoverRender` 同时知道
 * 源与目标两端的矩形）；Compose 这边 NavHost 是分开的两棵树，必须有一个中立容器把
 * 两端的几何收集起来：
 *
 * 1. 点击书架卡片时 [begin]：登记书 key + 源矩形 + 封面位图（一次光栅化，见
 *    [WenkuCoverSnapshots]）；
 * 2. 目标页（阅读器）首次布局封面时 [arrive]：登记落点矩形；
 * 3. [WenkuFlightOverlay] 放在 `MainActivity` 根 Box，两端齐全才真正开画；
 * 4. 播完 [clear]，覆盖层卸下，期间不参与任何布局。
 *
 * 单槽、不排队：封面飞行是「用户打开某一本」的瞬时行为。`bookKey` 用来防止
 * 「书架点了 A、阅读器却因为别的原因打开了 B」时把 A 的纸飞进 B 的页面。
 */
data class WenkuFlightRequest(
    val bookKey: String,
    val sourceBounds: Rect,
    val sourceImage: ImageBitmap?,
    val sourceRadius: Dp = 14.dp,
    val targetBounds: Rect? = null,
    val targetImage: ImageBitmap? = null,
    val targetRadius: Dp = 0.dp,
    /** true = 阅读器 → 书架（840ms 反向）；false = 起飞（1000ms）。 */
    val returning: Boolean = false
) {
    val ready: Boolean get() = targetBounds != null
}

/**
 * 全局桥。只存一份请求：封面飞行是「用户打开某一本」的瞬时行为，不允许排队。
 * 状态用 Compose `mutableStateOf`，覆盖层读取它会自动参与重组。
 */
object WenkuFlightBridge {

    var request by mutableStateOf<WenkuFlightRequest?>(null)
        private set

    /** 点击封面：登记起点。重复调用会覆盖上一次未完成的请求。 */
    fun begin(
        bookKey: String,
        bounds: Rect,
        image: ImageBitmap?,
        radius: Dp = 14.dp
    ) {
        if (bounds.width <= 0f || bounds.height <= 0f) return
        request = WenkuFlightRequest(
            bookKey = bookKey,
            sourceBounds = bounds,
            sourceImage = image,
            sourceRadius = radius
        )
    }

    /**
     * 目标页封面首次布局：登记落点。
     *
     * 只接受**同一个 bookKey** 的落点，且只登记一次（重复布局回调不重启动画）。
     */
    fun arrive(
        bookKey: String,
        bounds: Rect,
        image: ImageBitmap? = null,
        radius: Dp = 0.dp
    ) {
        val current = request ?: return
        if (current.bookKey != bookKey) return
        if (current.targetBounds != null) return
        if (bounds.width <= 0f || bounds.height <= 0f) return
        request = current.copy(
            targetBounds = bounds,
            targetImage = image ?: current.sourceImage,
            targetRadius = radius
        )
    }

    /** 这本书是否正在飞（阅读器据此暂时让出自己的封面，避免两张封面叠在一起）。 */
    fun isActiveFor(bookKey: String): Boolean = request?.bookKey == bookKey

    /** 放弃当前请求（例如卡片其实没进阅读器，或者用户中途返回）。 */
    fun cancel(bookKey: String? = null) {
        val current = request ?: return
        if (bookKey == null || current.bookKey == bookKey) request = null
    }

    /** 最近一次播完的飞行：返回时用它把纸从阅读器送回书架原位。 */
    private var last: WenkuFlightRequest? = null

    /** 目标端（阅读器）落点已知、且已经播完一次，才算「可以反向飞」。 */
    fun canReturn(bookKey: String): Boolean =
        last?.let { it.bookKey == bookKey && it.targetBounds != null } == true

    /**
     * 反向飞行：阅读器返回书架。
     *
     * 起点用上次的落点（整屏），终点用上次的起飞矩形（书架卡片），
     * 方向交给 `WenkuCoverFlight(departing = false)` 走 840ms。
     */
    fun reverse(bookKey: String): Boolean {
        val previous = last ?: return false
        val target = previous.targetBounds ?: return false
        if (previous.bookKey != bookKey) return false
        request = previous.copy(
            sourceBounds = target,
            sourceImage = previous.targetImage ?: previous.sourceImage,
            sourceRadius = previous.targetRadius,
            targetBounds = previous.sourceBounds,
            targetImage = previous.sourceImage,
            targetRadius = previous.sourceRadius,
            returning = true
        )
        return true
    }

    /** 覆盖层播完回调：清空当前请求，但记住这一次（供返回使用）。 */
    fun finished() {
        last = request
        request = null
    }

    fun clear() {
        request = null
    }
}

/**
 * 覆盖层：两端齐了才画，播完自动清空。放在 `MainActivity` 的根 Box 里（覆盖 NavHost）。
 *
 * 方向：`departing = true` 是「书架 → 阅读器」，走 1000ms 的 `COVER_FLIGHT_MS`；
 * 不是参考实现里 840ms 的返回方向。桥里存的矩形是 root 坐标，这里减去覆盖层自己的
 * root 原点后再交给 [WenkuCoverFlight]（它在自己的局部坐标系里画）。
 */
@Composable
fun WenkuFlightOverlay(modifier: Modifier = Modifier) {
    val current = WenkuFlightBridge.request ?: return
    val target = current.targetBounds ?: return
    // 先量出自己的原点再开画：第一帧只做布局，等 onGloballyPositioned 回来才启动动画，
    // 否则动画可能在错误的原点下跑掉一帧。
    var origin by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        val o = origin ?: return@Box
        WenkuCoverFlight(
            visible = true,
            source = WenkuCoverSource(
                bounds = current.sourceBounds.translate(-o.x, -o.y),
                image = current.sourceImage,
                cornerRadius = current.sourceRadius
            ),
            target = WenkuCoverTarget(
                bounds = target.translate(-o.x, -o.y),
                image = current.targetImage ?: current.sourceImage,
                cornerRadius = current.targetRadius
            ),
            departing = !current.returning,
            modifier = Modifier.fillMaxSize(),
            onFinished = { WenkuFlightBridge.finished() }
        )
    }
}
