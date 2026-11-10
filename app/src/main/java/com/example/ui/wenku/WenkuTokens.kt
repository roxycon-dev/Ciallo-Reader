package com.example.ui.wenku

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.feedback.LocalReduceMotion
import com.example.ui.theme.LocalAppDarkTheme
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * Wenku8 设计令牌。详见 docs/wenku8-ui-port-plan.md。
 */
object WenkuTokens {
    val PaperLight = Color(0xFFF4F3EF)
    val PanelLight = Color(0xFFFDFCFA)
    val MilkLight = Color(0xFFFEFDF8)
    val InkLight = Color(0xFF161613)
    val InkSecondaryLight = Color(0xFF646B62)

    val PaperDark = Color(0xFF191919)
    val PanelDark = Color(0xFF242424)
    val MilkDark = Color(0xFFF6F6F6)
    val RaisedDark = Color(0xFF2D2D2D)
    val RaisedHighDark = Color(0xFF3A3A3A)
    val OutlineDark = Color(0xFF3C3C3C)
    val InkDark = Color(0xFFF2F2F2)
    val InkSecondaryDark = Color(0xFFB7B7B7)

    val Scrim = Color.Black.copy(alpha = 0.16f)

    val PanelRadius: Dp = 4.dp
    val CoverRadius: Dp = 3.dp
    val GlassRadius: Dp = 28.dp
    val IconButtonRadius: Dp = 24.dp
    val PillRadius: Dp = 22.dp
    val TextButtonRadius: Dp = 22.dp
    val MenuRadius: Dp = 4.dp
    val SheetRadius: Dp = 28.dp

    val Space1: Dp = 4.dp
    val Space2: Dp = 8.dp
    val Space3: Dp = 12.dp
    val Space4: Dp = 16.dp
    val Space5: Dp = 20.dp
    val Space6: Dp = 24.dp
    val Space8: Dp = 32.dp

    val TypeDisplay = 34.sp
    val TypeTitle = 24.sp
    val TypeSection = 12.sp
    val TypeBody = 15.sp
    val TypeCaption = 12.sp
    val TypeMicro = 10.sp

    val IconButtonSize: Dp = 46.dp
    val IconButtonCompactWidth: Dp = 48.dp
    val IconGlyph: Dp = 21.dp
    val PillHeight: Dp = 54.dp
    val PillThickness: Dp = 16.dp
    val TextButtonMinWidth: Dp = 64.dp
    val TextButtonMinHeight: Dp = 48.dp
    val MenuWidth: Dp = 248.dp
    val MenuMaxOverlayWidth: Dp = 440.dp
    val SheetHandleWidth: Dp = 32.dp
    val SheetHandleHeight: Dp = 3.dp
    const val SheetDismissVelocity = 550f
    const val SheetDismissFraction = 0.24f

    val shapes = WenkuShapes
    val spacing = WenkuSpacing

    val panelShape get() = RoundedCornerShape(PanelRadius)
    val coverShape get() = RoundedCornerShape(CoverRadius)
    val glassShape get() = RoundedCornerShape(GlassRadius)
    val iconButtonShape get() = RoundedCornerShape(IconButtonRadius)
    val pillShape get() = RoundedCornerShape(PillRadius)
}

object WenkuShapes {
    val Panel: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.PanelRadius)
    val Cover: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.CoverRadius)
    val Glass: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.GlassRadius)
    val IconButton: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.IconButtonRadius)
    val Pill: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.PillRadius)
    val TextButton: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.TextButtonRadius)
    val Menu: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.MenuRadius)
    val Sheet: RoundedCornerShape get() = RoundedCornerShape(WenkuTokens.SheetRadius)
}

object WenkuSpacing {
    val Hairline = 0.5.dp
    val One = 4.dp
    val Two = 8.dp
    val Three = 12.dp
    val Four = 16.dp
    val Five = 20.dp
    val Six = 24.dp
    val Eight = 32.dp
    val Page = 20.dp
}

/** Wenku8 动效词表：时长、曲线、弹簧与列表延迟带。 */
object WenkuMotion {
    const val CONTACT_MS = 120
    const val ENTER_MS = 420
    const val EXIT_MS = 320
    const val COVER_FLIGHT_MS = 1000
    const val COVER_RETURN_MS = 840

    const val PAGE_CLOCK_MS = 620
    const val PAPER_TURN_MS = 440
    const val COVER_TURN_MS = 340
    const val FADE_TURN_MS = 300
    const val SLIDE_TURN_MS = 300

    const val LIST_WINDOW = 0.52f
    const val LIST_DELAY_STEP = 0.055f
    const val LIST_MAX_DELAY_BANDS = 8

    const val DOCK_FOLLOW = 0.8f
    const val DOCK_STRETCH_FACTOR = 0.028f
    const val DOCK_STRETCH_LIMIT = 24f
    const val DOCK_STRETCH_LERP = 0.55f
    const val DOCK_IDLE_MS = 100L
    const val DOCK_PROJECT = 0.055f
    const val DOCK_PROJECT_LIMIT = 0.22f
    const val DOCK_VELOCITY_CLAMP = 5f

    const val SHELF_FRICTION = 4.4f
    const val SHELF_HANDOFF_PITCH = 4f
    const val SHELF_WAVE_RADIUS = 1.48f

    const val READER_COMMIT_FRACTION = 0.10f
    const val READER_COMMIT_MIN_DP = 28f
    const val READER_COMMIT_MAX_DP = 48f
    const val READER_VELOCITY_THRESHOLD = 360f
    const val READER_FAST_DISTANCE = 8f
    const val READER_SPRING_MAX_VELOCITY = 2400f

    /** Curves.easeOutCubic */
    val ease: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    /** Curves.easeInCubic */
    val retract: Easing = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    val settle: Easing = CubicBezierEasing(0.2f, 1.08f, 0.34f, 1f)

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    val plane: Easing = CubicBezierEasing(0.18f, 0.72f, 0.18f, 1f)

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    val heroX: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    val springDampingRatio: Float = 19f / (2f * kotlin.math.sqrt(270f * 0.64f))

    fun <T> spring(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = springDampingRatio, stiffness = 270f, visibilityThreshold = visibilityThreshold)

    fun contactSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = CONTACT_MS,
        easing = ease
    )

    fun enterSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = ENTER_MS,
        easing = ease
    )

    fun exitSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = EXIT_MS,
        easing = retract
    )

    fun settleSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = ENTER_MS,
        easing = settle
    )

    fun coverFlightSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = COVER_FLIGHT_MS,
        easing = ease
    )

    fun coverReturnSpec(): FiniteAnimationSpec<Float> = androidx.compose.animation.core.tween(
        durationMillis = COVER_RETURN_MS,
        easing = ease
    )

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    fun jelly(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return (sin(3f * Math.PI.toFloat() * x) * (1f - x).pow(2)).toFloat()
    }

    /** 详见 docs/wenku8-ui-port-plan.md。 */
    fun listStart(order: Int): Float =
        order.coerceIn(0, LIST_MAX_DELAY_BANDS) * LIST_DELAY_STEP

    fun listEnd(order: Int): Float = listStart(order) + LIST_WINDOW
}

@Immutable
data class WenkuColors(
    val paper: Color,
    val panel: Color,
    val milk: Color,
    val raised: Color,
    val raisedHigh: Color,
    val outline: Color,
    val ink: Color,
    val inkSecondary: Color,
    val isDark: Boolean
) {
    val glassTint: Color get() = if (isDark) milk.copy(alpha = 0.024f) else milk.copy(alpha = 0.010f)
    val hairline: Color get() = ink.copy(alpha = if (isDark) 0.14f else 0.10f)
    val selection: Color get() = ink.copy(alpha = if (isDark) 0.10f else 0.07f)
    val scrim: Color get() = Color.Black.copy(alpha = 0.16f)
}

@Composable
fun wenkuColors(): WenkuColors {
    val dark = LocalAppDarkTheme.current
    return remember(dark) {
        if (dark) WenkuColors(
            paper = WenkuTokens.PaperDark,
            panel = WenkuTokens.PanelDark,
            milk = WenkuTokens.MilkDark,
            raised = WenkuTokens.RaisedDark,
            raisedHigh = WenkuTokens.RaisedHighDark,
            outline = WenkuTokens.OutlineDark,
            ink = WenkuTokens.InkDark,
            inkSecondary = WenkuTokens.InkSecondaryDark,
            isDark = true
        ) else WenkuColors(
            paper = WenkuTokens.PaperLight,
            panel = WenkuTokens.PanelLight,
            milk = WenkuTokens.MilkLight,
            raised = Color(0xFFF7F5EF),
            raisedHigh = Color(0xFFEFEDE6),
            outline = Color(0xFFD9D5CA),
            ink = WenkuTokens.InkLight,
            inkSecondary = WenkuTokens.InkSecondaryLight,
            isDark = false
        )
    }
}

/** 主界面作用域；阅读器路由会显式关闭，避免覆盖正文与控件的既有视觉。 */
val LocalWenkuMainSurface = compositionLocalOf { true }

/** 详见 docs/wenku8-ui-port-plan.md。 */
val LocalWenkuHighContrast = compositionLocalOf { false }

/** 详见 docs/wenku8-ui-port-plan.md。 */
val LocalWenkuReduceMotion = compositionLocalOf { false }

/** 详见 docs/wenku8-ui-port-plan.md。 */
val LocalWenkuBlurEnabled = compositionLocalOf { true }

/** 详见 docs/wenku8-ui-port-plan.md。 */
val LocalWenkuDecorativeMotion = compositionLocalOf { true }

data class WenkuAccessibility(
    val highContrast: Boolean,
    val reduceMotion: Boolean,
    val blurEnabled: Boolean,
    val decorativeMotion: Boolean
)

/** 详见 docs/wenku8-ui-port-plan.md。 */
@Composable
fun rememberWenkuAccessibility(): WenkuAccessibility {
    val context = LocalContext.current
    val highContrast = remember(context) { readHighContrast(context) }
    val reduce = LocalReduceMotion.current
    val quality = com.example.ui.components.LocalRenderQuality.current
    return remember(highContrast, reduce, quality) {
        WenkuAccessibility(
            highContrast = highContrast,
            reduceMotion = reduce,
            blurEnabled = quality.realtimeGlass,
            decorativeMotion = !reduce && !quality.minimal
        )
    }
}

private fun readHighContrast(context: Context): Boolean {
    return runCatching {
        Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1 ||
            Settings.Secure.getInt(context.contentResolver, "accessibility_high_text_contrast_enabled", 0) == 1 ||
            Settings.Global.getInt(context.contentResolver, "high_text_contrast_enabled", 0) == 1
    }.getOrDefault(false)
}

/** 详见 docs/wenku8-ui-port-plan.md。 */
fun wenkuSmooth(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** 文档 §10.6：翻页提交物理。 */
object WenkuReaderPhysics {
    fun commitDistancePx(viewportPx: Float, density: Float): Float =
        (viewportPx * WenkuMotion.READER_COMMIT_FRACTION)
            .coerceIn(
                WenkuMotion.READER_COMMIT_MIN_DP * density,
                WenkuMotion.READER_COMMIT_MAX_DP * density
            )

    /**
     * @param deltaPx finger release minus down
     * @param velocityPx VelocityTracker px/s
     */
    fun commit(
        viewportPx: Float,
        deltaPx: Offset,
        velocityPx: Offset,
        density: Float
    ): Boolean {
        val distance = abs(deltaPx.x)
        val speed = abs(velocityPx.x)
        val velocityThreshold = WenkuMotion.READER_VELOCITY_THRESHOLD * density
        val reversing = speed >= velocityThreshold && velocityPx.x * deltaPx.x < 0f
        val fast = speed >= velocityThreshold && distance >= WenkuMotion.READER_FAST_DISTANCE * density
        val commitDistance = commitDistancePx(viewportPx, density)
        return !reversing && (distance >= commitDistance || fast)
    }
}
