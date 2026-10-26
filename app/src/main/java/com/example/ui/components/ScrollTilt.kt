package com.example.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlin.math.abs
import kotlin.math.exp

/**
 * 全局滚动惯性倾斜（任务书「整卡倾斜」§2）：
 * 列表滚动速度 → 归一化 → 倾斜角信号。全 App 单实例共享一个信号源：
 *
 *  - 滚动中的列表通过 scrollTiltSource() 扩展每帧上报像素速度（px/ms），
 *    这里做低通滤波并映射到 [SCROLL_TILT_MAX_DEG] 以内的角度目标；
 *  - [ScrollTiltHost] 的帧循环把当前角指数趋近到目标：跟随用短时间常数（跟手），
 *    释放（滚动停止）切换到长时间常数（任务书 VERY_LOW stiffness 的极软缓慢回正，
 *    指数趋近天然无回弹过冲 = NO_BOUNCY）；
 *  - 各 GlassCard 在 graphicsLayer 内延迟读取 [tiltDeg]（不触发重组，只更新图层参数），
 *    天然只对可见卡片生效。
 *
 * 速度基准 [SCROLL_VELOCITY_REFERENCE_PX_MS] 为任务书标注的实机调试项。
 */
class ScrollTiltController {
    /** 当前平滑后的滚动倾斜角（度），卡片 graphicsLayer 延迟读取 */
    val tiltDeg = mutableFloatStateOf(0f)

    internal var targetDeg = 0f
    internal var lastEmitNanos = 0L
    private val motionRequests = Channel<Unit>(Channel.CONFLATED)

    /** 滚动信号源每帧上报像素速度（px/ms，向下滚动为正） */
    internal fun submitVelocity(pxPerMs: Float) {
        // 低通：单帧速度噪声大（布局抖动），新旧各半平滑后再钳制
        val target = (targetDeg * 0.5f + velocityToDeg(pxPerMs) * 0.5f)
            .coerceIn(-SCROLL_TILT_MAX_DEG, SCROLL_TILT_MAX_DEG)
        targetDeg = target
        lastEmitNanos = System.nanoTime()
        motionRequests.trySend(Unit)
    }

    private fun velocityToDeg(v: Float): Float =
        (v / SCROLL_VELOCITY_REFERENCE_PX_MS) * SCROLL_TILT_MAX_DEG

    /** 滚动是否已闲置（超过 [IDLE_TIMEOUT_NANOS] 无新速度）——闲置即目标归零 */
    internal fun isIdle(nowNanos: Long): Boolean =
        nowNanos - lastEmitNanos > IDLE_TIMEOUT_NANOS

    /** 画质降档卸载 Host 时复位信号，避免重进 MAX 档后残留旧角度缓慢回零 */
    fun reset() {
        targetDeg = 0f
        lastEmitNanos = 0L
        tiltDeg.floatValue = 0f
        motionRequests.tryReceive()
    }

    /** Suspend without requesting frames after the original settling rule reaches zero. */
    internal suspend fun runFrames() {
        while (true) {
            motionRequests.receive()
            var lastNanos = 0L
            do {
                // Requests arriving during motion are already represented by targetDeg.
                motionRequests.tryReceive()
                val moving = withFrameNanos { now ->
                    val dtMs = if (lastNanos == 0L) 16.7f
                        else ((now - lastNanos) / 1_000_000f).coerceAtLeast(1f)
                    lastNanos = now
                    advance(now, dtMs)
                }
            } while (moving)
        }
    }

    internal fun advance(nowNanos: Long, dtMs: Float): Boolean {
        val idle = isIdle(nowNanos)
        if (idle) targetDeg = 0f
        val target = targetDeg
        val current = tiltDeg.floatValue
        val tau = if (idle) RELEASE_TAU_MS else FOLLOW_TAU_MS
        val k = 1f - exp(-dtMs / tau)
        var next = current + (target - current) * k
        if (target == 0f && abs(next) < 0.02f) next = 0f
        if (next != current) tiltDeg.floatValue = next
        return !idle || next != 0f
    }

    companion object {
        /** 速度归一化基准（px/ms）：2.2 ≈ 2200px/s 快速甩动时打满上限。实机调试项 */
        internal const val SCROLL_VELOCITY_REFERENCE_PX_MS = 2.2f

        /** 滚动惯性倾斜上限（度）：任务书建议 3°~8° 克制区间取中值 */
        internal const val SCROLL_TILT_MAX_DEG = 4.5f

        private const val IDLE_TIMEOUT_NANOS = 120_000_000L
    }
}

/** 跟随手感时间常数（ms）：快速跟随滚动 */
private const val FOLLOW_TAU_MS = 55f

/** 释放回正时间常数（ms）：≈0.9s 完全回正，等效 StiffnessVeryLow 极软弹簧 */
private const val RELEASE_TAU_MS = 260f

/** 默认共享实例：MainActivity 组合根 provide 同一实例，卡片侧直接读取 */
val LocalScrollTilt = staticCompositionLocalOf { ScrollTiltController() }

/**
 * 应用根挂载一次：每帧驱动「指数趋近 + 闲置衰减」。
 * 活动期间保留原有指数趋近与收敛规则；静止后挂起，收到滚动信号后在下一帧恢复。
 * 仅 MAX 画质档挂载（滚动倾斜是 MAX 卡片特效）。
 */
@Composable
fun ScrollTiltHost(controller: ScrollTiltController = LocalScrollTilt.current) {
    LaunchedEffect(controller) {
        controller.runFrames()
    }
}

/**
 * 纵向列表滚动 → 惯性倾斜信号源。挂在持有 GlassCard 的 Lazy 容器所在 Composable 内。
 * 帧率级 snapshotFlow 采集（滚动中每帧一发）；跨界帧用真实 item 尺寸做像素守恒
 * （瀑布流/不等高列表不再产生反向速度尖峰）；纯观察，不消费任何手势事件。
 */
@Composable
fun LazyListState.scrollTiltSource(controller: ScrollTiltController = LocalScrollTilt.current) {
    LaunchedEffect(this, controller) {
        var prevIdx = Int.MIN_VALUE
        var prevOffset = 0
        var prevSize = 0
        var prevNanos = 0L
        snapshotFlow {
            Triple(firstVisibleItemIndex, firstVisibleItemScrollOffset,
                layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0)
        }.collect { (idx, offset, itemSize) ->
            val now = System.nanoTime()
            if (prevIdx != Int.MIN_VALUE) {
                val dPx = scrollDeltaPx(idx - prevIdx, offset - prevOffset, prevSize, itemSize)
                if (dPx != null) {
                    val dtMs = ((now - prevNanos) / 1_000_000f).coerceAtLeast(1f)
                    controller.submitVelocity(dPx / dtMs)
                }
            }
            prevIdx = idx
            prevOffset = offset
            prevSize = itemSize
            prevNanos = now
        }
    }
}

/** 网格版信号源，语义同 [LazyListState.scrollTiltSource]（纵向滚动取 item 高度） */
@Composable
fun LazyGridState.scrollTiltSource(controller: ScrollTiltController = LocalScrollTilt.current) {
    LaunchedEffect(this, controller) {
        var prevIdx = Int.MIN_VALUE
        var prevOffset = 0
        var prevSize = 0
        var prevNanos = 0L
        snapshotFlow {
            Triple(firstVisibleItemIndex, firstVisibleItemScrollOffset,
                layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0)
        }.collect { (idx, offset, itemSize) ->
            val now = System.nanoTime()
            if (prevIdx != Int.MIN_VALUE) {
                val dPx = scrollDeltaPx(idx - prevIdx, offset - prevOffset, prevSize, itemSize)
                if (dPx != null) {
                    val dtMs = ((now - prevNanos) / 1_000_000f).coerceAtLeast(1f)
                    controller.submitVelocity(dPx / dtMs)
                }
            }
            prevIdx = idx
            prevOffset = offset
            prevSize = itemSize
            prevNanos = now
        }
    }
}

/** 瀑布流版信号源，语义同 [LazyListState.scrollTiltSource]（多 lane 结构下为近似守恒） */
@Composable
fun LazyStaggeredGridState.scrollTiltSource(controller: ScrollTiltController = LocalScrollTilt.current) {
    LaunchedEffect(this, controller) {
        var prevIdx = Int.MIN_VALUE
        var prevOffset = 0
        var prevSize = 0
        var prevNanos = 0L
        snapshotFlow {
            Triple(firstVisibleItemIndex, firstVisibleItemScrollOffset,
                layoutInfo.visibleItemsInfo.firstOrNull()?.size?.height ?: 0)
        }.collect { (idx, offset, itemSize) ->
            val now = System.nanoTime()
            if (prevIdx != Int.MIN_VALUE) {
                val dPx = scrollDeltaPx(idx - prevIdx, offset - prevOffset, prevSize, itemSize)
                if (dPx != null) {
                    val dtMs = ((now - prevNanos) / 1_000_000f).coerceAtLeast(1f)
                    controller.submitVelocity(dPx / dtMs)
                }
            }
            prevIdx = idx
            prevOffset = offset
            prevSize = itemSize
            prevNanos = now
        }
    }
}

/**
 * 帧间滚动像素位移（px，向下滚为正）：
 *  - 未跨界：offset 差分；
 *  - 跨 1 项：向下 = 旧首项真实高度 − 旧offset + 新offset；向上对称用新首项高度；
 *  - 跨多项/空布局：无法精确守恒，返回 null 丢弃该帧（宁缺勿假，防反向速度尖峰）。
 */
private fun scrollDeltaPx(
    dIdx: Int,
    dOffset: Int,
    prevSize: Int,
    itemSize: Int
): Float? = when {
    itemSize <= 0 || prevSize <= 0 -> null
    dIdx == 0 -> dOffset.toFloat()
    dIdx == 1 -> (prevSize + dOffset).toFloat()   // 旧首项高度 + offset 差分
    dIdx == -1 -> (dOffset - itemSize).toFloat()  // 新首项高度 + offset 差分（向上滚为负）
    else -> null
}
