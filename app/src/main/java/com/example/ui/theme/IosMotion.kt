package com.example.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FiniteAnimationSpec
import com.example.ui.feedback.AppMotion

/**
 * iOS / SwiftUI 动效规格（2026-09-21 新增）。
 *
 * 项目此前全部使用 Material 默认动效：线性补间 + 偏长的时长，观感“慢、钝、橡皮筋”，
 * 这是“缺少苹果感”最容易被感知的一环。SwiftUI 的动效特征是：
 *  - 绝大多数交互反馈走 **弹簧**而不是补间（`.spring(response:dampingFraction:)`）；
 *  - 弹簧是**临界阻尼附近**（dampingFraction ≈ 0.8~0.9），几乎不回弹，但收尾极其顺滑；
 *  - 必须用补间时，走 easeOut 系曲线（起步快、尾部缓），时长集中在 0.15~0.4s。
 *
 * 用法： `animationSpec = IosMotion.spring()` / `IosMotion.easeOut(220)`。
 */
object IosMotion {

    // ── 时长（毫秒），对齐 iOS 常见档位 ──
    /** 即时反馈：按压、高亮、小图标切换 */
    const val FAST = AppMotion.PRESS_MS
    /** 常规：内容淡入淡出、行内展开收起 */
    const val NORMAL = AppMotion.CONTENT_MS
    /** 页面/视图转场 */
    const val TRANSITION = AppMotion.PAGE_MS
    /** Sheet / 弹窗进出场 */
    const val SHEET = AppMotion.PAGE_MS

    // ── 缓动曲线（与 iOS CAMediaTimingFunction 对应）──
    /** easeOut：起步快、尾部缓 —— iOS 默认出场曲线 */
    val EaseOut: Easing = AppMotion.easeOutCubic
    /** easeInOut：对称缓动，用于同一元素的位置迁移 */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** easeOutQuart：更陡的尾部衰减，Sheet 上推、大位移用 */
    val EaseOutQuart: Easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f)

    /**
     * iOS 标准弹簧（对应 `.spring(response: 0.35, dampingFraction: 0.85)`）。
     * 临界阻尼附近：不弹跳，收尾干脆。适合弹窗、卡片、尺寸变化。
     */
    fun <T> spring(): SpringSpec<T> = AppMotion.settle()

    /**
     * 更灵敏的弹簧（response ≈ 0.25）。适合按压反馈、开关、小控件，
     * 手感“跟手”而不拖沓。
     */
    fun <T> springSnappy(): SpringSpec<T> = AppMotion.press()

    /**
     * 偏软的弹簧（response ≈ 0.5）。适合大位移 / 整页转场，
     * 位移大时需要更长的收敛时间才不会显得生硬。
     */
    fun <T> springSoft(): SpringSpec<T> = AppMotion.reorder()

    /** easeOut 补间，[duration] 默认 250ms */
    fun <T> easeOut(duration: Int = NORMAL): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = EaseOut)

    /** easeInOut 补间 */
    fun <T> easeInOut(duration: Int = NORMAL): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = EaseInOut)

    /** easeOutQuart 补间，用于 Sheet / 大位移 */
    fun <T> easeOutQuart(duration: Int = SHEET): FiniteAnimationSpec<T> =
        tween(durationMillis = duration, easing = EaseOutQuart)
}
