package com.example.ui.wenku

import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 接触态（对应 Flutter 端 ArchiveIconContact）
// ---------------------------------------------------------------------------

/**
 * 按钮把"被按住"这件事借给图标，但自己不注册手势识别器、也不处理动作：
 * 滚动与取消仍然归原来的控件。这里用组合局部替代 Dart 端的 InheritedWidget。
 */
@Immutable
data class WenkuIconContact(
    val pressed: Boolean = false,
    val activation: Int = 0,
)

/** 由 [WenkuIconResponse] 提供；也可在任意位置手动 provide 以驱动一组图标的接触位移。 */
val LocalWenkuIconContact = staticCompositionLocalOf { WenkuIconContact() }

// ---------------------------------------------------------------------------
// 时钟采样与常量
// ---------------------------------------------------------------------------

/** Flutter `Curves.easeOutCubic` = `Cubic(0.215, 0.61, 0.355, 1.0)`。 */
private val WenkuEaseOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

/** 入场级联里"非入场重播"的哨兵值（对应 Dart 端 `entranceDelay == null`）。 */
private const val WENKU_ENTRANCE_NONE = -1

/** 常驻图标在两次手势之间完全静止的时长：期间不排任何一帧。 */
private const val WENKU_AMBIENT_REST_MS = 2600L

/** 手势相位：`((value - start) / span).clamp(0, 1)`。 */
private fun gesturePhase(value: Float, start: Float, span: Float): Float =
    if (span <= 0f) 1f else ((value - start) / span).coerceIn(0f, 1f)

/**
 * 采样当前手势：新的一帧叠加在残余偏移（origin）上，残余由 easeOutCubic 在相位前 24% 内交还。
 */
private fun sampleGesture(phase: Float, origin: WenkuIconGesture): WenkuIconGesture {
    val carry = 1f - WenkuEaseOutCubic.transform((phase / 0.24f).coerceIn(0f, 1f))
    return WenkuIconMotion.frame(phase).plus(origin, carry)
}

/** 按压附加量 `press = easeOutCubic(contact) * .16`，三等分给 lead / follow / trail。 */
private fun pressGesture(contact: Float): WenkuIconGesture {
    val press = WenkuEaseOutCubic.transform(contact.coerceIn(0f, 1f)) * 0.16f
    return WenkuIconGesture(press, press, press)
}

// ---------------------------------------------------------------------------
// 绘制
// ---------------------------------------------------------------------------

/** 线宽：`contrast ? 2.05 : (shortestSide < 18 ? 1.9 : 1.72)`，再乘每条描边的 width。 */
private fun baseStrokeWidth(contrast: Boolean, sizeDp: Float): Float = when {
    contrast -> 2.05f
    sizeDp < 18f -> 1.9f
    else -> 1.72f
}

/**
 * 24×24 逻辑坐标系落屏：先按 `size / 24` 缩放，再按 RTL 对方向性图标做水平镜像。
 * 与 `_ArchiveGlyphPainter.paint` 一致。
 */
private fun DrawScope.drawWenkuPose(
    from: WenkuIconPose,
    to: WenkuIconPose,
    change: Float,
    motion: WenkuIconGesture,
    color: Color,
    contrast: Boolean,
    mirrored: Boolean,
    sizeDp: Float,
) {
    val t = WenkuIconMotion.stateCurve.transform(change.coerceIn(0f, 1f))
    val width = baseStrokeWidth(contrast, sizeDp)
    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
        if (mirrored) {
            translate(24f, 0f) {
                scale(-1f, 1f, pivot = Offset.Zero) {
                    drawWenkuCrossfade(from, to, t, motion, color, width)
                }
            }
        } else {
            drawWenkuCrossfade(from, to, t, motion, color, width)
        }
    }
}

/**
 * 同一族内做姿态插值；族与族之间用透明度交叉淡出（`t == 1` 时只剩目标姿态）。
 */
private fun DrawScope.drawWenkuCrossfade(
    from: WenkuIconPose,
    to: WenkuIconPose,
    t: Float,
    motion: WenkuIconGesture,
    color: Color,
    strokeWidth: Float,
) {
    if (from.symbol == to.symbol || t == 1f) {
        drawWenkuStrokes(WenkuIconPoses.lerp(from, to, t), 1f, motion, color, strokeWidth)
    } else {
        drawWenkuStrokes(from, 1f - t, motion, color, strokeWidth)
        drawWenkuStrokes(to, t, motion, color, strokeWidth)
    }
}

/** 逐描边出图：fill 用 Fill，其余用 Stroke（Round cap / Round join），alpha 相乘。 */
private fun DrawScope.drawWenkuStrokes(
    pose: WenkuIconPose,
    fader: Float,
    motion: WenkuIconGesture,
    color: Color,
    strokeWidth: Float,
) {
    if (fader <= 0f) return
    val alphaScale = fader.coerceIn(0f, 1f)
    for (stroke in WenkuIconArt.strokes(pose, gesture = motion)) {
        val alpha = color.alpha * alphaScale * stroke.opacity
        if (alpha <= 0f) continue
        val paint = color.copy(alpha = alpha)
        if (stroke.fill) {
            drawPath(stroke.path, paint, style = Fill)
        } else {
            drawPath(
                stroke.path,
                paint,
                style = Stroke(
                    width = strokeWidth * stroke.width,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 级联批次（对应 _ArchiveIconEntranceBatch）
// ---------------------------------------------------------------------------

/**
 * 一次布局知道当前可见的行：同排图标一起起播，整页自上而下的级联因此仍然清晰。
 * 批次只活到本帧微任务结束后，不保留任何 ticker。
 */
private object WenkuIconEntranceBatch {
    private class Entry(val y: Float, val start: (Int) -> Unit)

    private val pending = ArrayList<Entry>()
    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private val flushRunnable = Runnable { flushPending() }

    fun submit(y: Float, start: (Int) -> Unit) {
        pending.add(Entry(y, start))
        if (scheduled) return
        scheduled = true
        handler.post(flushRunnable)
    }

    private fun flushPending() {
        scheduled = false
        val entries = ArrayList(pending)
        pending.clear()
        entries.sortBy { it.y }
        val rows = ArrayList<MutableList<Entry>>()
        for (entry in entries) {
            if (rows.isEmpty() || entry.y - rows[rows.size - 1][0].y > 16f) {
                rows.add(ArrayList())
            }
            rows[rows.size - 1].add(entry)
        }
        for (i in rows.indices) {
            val delayMs = if (rows.size == 1) 0 else (i * 360f / (rows.size - 1)).roundToInt()
            for (entry in rows[i]) entry.start(delayMs)
        }
    }
}

// ---------------------------------------------------------------------------
// 动画时钟（对应 _ArchiveGlyphState 的三个 controller）
// ---------------------------------------------------------------------------

/**
 * 一个图标的三时钟：change(380ms morph) / contact(按下 140ms、抬起 240ms 反向) /
 * gesture(640ms 一次手势)。不可见、减少动效或关闭装饰动效时整块不进入组合，因此不建 ticker。
 */
private class WenkuGlyphClock(symbol: WenkuSymbol, active: Boolean) {
    val change = Animatable(1f)
    val contact = Animatable(0f)
    val gesture = Animatable(1f)

    var from by mutableStateOf(WenkuIconPoses.of(symbol, active))
    var to by mutableStateOf(WenkuIconPoses.of(symbol, active))
    var origin by mutableStateOf(WenkuIconGesture.Rest)
    var start by mutableFloatStateOf(0f)
    var span by mutableFloatStateOf(1f)
}

// ---------------------------------------------------------------------------
// 对外 API
// ---------------------------------------------------------------------------

/**
 * 真正的手绘矢量图标（不是被变换的字体字形）。状态切换做 morph；按下时纸片的相应部位移动；
 * 可见图标在页面入场时致意一次，不淡入、不改变布局。
 *
 * @param symbol 89 个符号之一；Pause / Back / Up / Down / ArrowLeft / ArrowUp / ArrowDown /
 * External / Bookmarked / LocationFilled / CheckCircle / Selected / EyeOff 这 13 个变体符号
 * 会先折叠成族符号 + 变体角，再由同一份几何绘制。
 * @param size 光学尺寸，默认 21dp；线宽系数 `contrast ? 2.05 : (size < 18dp ? 1.9 : 1.72)`。
 * @param active 选中量 0→1，380ms 走 `stateCurve`；书签/定位/圆环等族会附加几何。
 * @param contact 外部接管的接触量 0..1；非 0 时直接驱动按压位移（等价于 `easeOutCubic(contact)*.16`），
 * 否则跟随 [LocalWenkuIconContact] 的按住状态（按下 140ms、抬起 240ms）。
 * @param gesture 外部给定的手势帧；非 null 时跳过内部 640ms 手势时钟的采样，用于逐帧核对与导出。
 * @param animated false 时只画静止姿态，不建 ticker（等价于减少动效）。
 * @param ambient 常驻图标（空态/提示）才开启：每段手势结束后完全静止 2600ms 再重播一次。
 * @param entrance 页面入场时是否参与自上而下的级联致意；懒加载行在滚动中创建时应传 false。
 */
@Composable
fun WenkuIcon(
    symbol: WenkuSymbol,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    tint: Color = LocalContentColor.current,
    active: Boolean = false,
    contact: Float = 0f,
    gesture: WenkuIconGesture? = null,
    animated: Boolean = true,
    ambient: Boolean = false,
    entrance: Boolean = true,
) {
    val a11y = rememberWenkuAccessibility()
    val decorative = LocalWenkuDecorativeMotion.current
    val foreground = rememberWenkuForeground()
    val contrast = a11y.highContrast
    // 不可见 / 减少动效 / 关闭装饰动效：只画静止姿态，不建任何 ticker。
    val still = !animated || a11y.reduceMotion || !decorative || !foreground
    val box = modifier.size(size)
    val sizeDp = size.value
    val mirrored = LocalLayoutDirection.current == LayoutDirection.Rtl &&
        (symbol == WenkuSymbol.Back || symbol == WenkuSymbol.Forward)
    if (still) {
        WenkuIconStill(
            symbol = symbol,
            modifier = box,
            sizeDp = sizeDp,
            tint = tint,
            active = active,
            contact = contact,
            gesture = gesture,
            contrast = contrast,
            mirrored = mirrored,
        )
    } else {
        WenkuIconAnimated(
            symbol = symbol,
            modifier = box,
            sizeDp = sizeDp,
            tint = tint,
            active = active,
            externalContact = contact,
            externalGesture = gesture,
            ambient = ambient,
            entrance = entrance,
            contrast = contrast,
            mirrored = mirrored,
        )
    }
}

/**
 * 裸 Listener：按下/抬起只做观察，不消费事件，因此不抢手势竞技场，
 * 滚动、滑动取消仍归原控件。按下经 [LocalWenkuIconContact] 借给内部图标。
 *
 * [symbol] / [tint] / [active] / [animated] 描述 [content] 将要绘制的那枚图标，
 * 供槽位内容原样转交给 [WenkuIcon]。
 */
@Composable
fun WenkuIconResponse(
    symbol: WenkuSymbol,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 21.dp,
    tint: Color = LocalContentColor.current,
    active: Boolean = false,
    animated: Boolean = true,
    onTap: (() -> Unit)? = null,
    content: @Composable (interactionSource: MutableInteractionSource) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    var activation by remember { mutableIntStateOf(0) }
    val tapNow = rememberUpdatedState(onTap)
    val pressRef = remember { arrayOfNulls<PressInteraction.Press>(1) }

    val listener = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            var pointer: PointerId? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val current = pointer
                if (current == null) {
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                    if (down != null) {
                        pointer = down.id
                        val press = PressInteraction.Press(down.position)
                        pressRef[0] = press
                        pressed = true
                        activation++
                        interactionSource.tryEmit(press)
                    }
                } else {
                    val change = event.changes.firstOrNull { it.id == current }
                    if (change != null && !change.pressed) {
                        pointer = null
                        pressed = false
                        val press = pressRef[0]
                        pressRef[0] = null
                        if (press != null) interactionSource.tryEmit(PressInteraction.Release(press))
                        tapNow.value?.invoke()
                    }
                }
            }
        }
    }

    val description = label.ifBlank { symbol.name }
    Box(
        modifier = modifier
            .sizeIn(minWidth = size, minHeight = size)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                selected = active
                if (tapNow.value != null) onClick { tapNow.value?.invoke(); true }
            }
            .then(listener),
    ) {
        CompositionLocalProvider(
            LocalWenkuIconContact provides WenkuIconContact(
                pressed = pressed,
                activation = activation,
            ),
        ) {
            content(interactionSource)
        }
    }
}

// ---------------------------------------------------------------------------
// 内部实现
// ---------------------------------------------------------------------------

/** 静止（still）分支：不进入组合、不排帧，只画当前姿态。 */
@Composable
private fun WenkuIconStill(
    symbol: WenkuSymbol,
    modifier: Modifier,
    sizeDp: Float,
    tint: Color,
    active: Boolean,
    contact: Float,
    gesture: WenkuIconGesture?,
    contrast: Boolean,
    mirrored: Boolean,
) {
    Canvas(modifier) {
        val pose = WenkuIconPoses.of(symbol, active)
        val motion = (gesture ?: WenkuIconGesture.Rest).plus(pressGesture(contact), 1f)
        drawWenkuPose(
            from = pose,
            to = pose,
            change = 1f,
            motion = motion,
            color = tint,
            contrast = contrast,
            mirrored = mirrored,
            sizeDp = sizeDp,
        )
    }
}

/** 动画分支：三时钟驱动；入场级联、常驻重播、按压位移都在这里。 */
@Composable
private fun WenkuIconAnimated(
    symbol: WenkuSymbol,
    modifier: Modifier,
    sizeDp: Float,
    tint: Color,
    active: Boolean,
    externalContact: Float,
    externalGesture: WenkuIconGesture?,
    ambient: Boolean,
    entrance: Boolean,
    contrast: Boolean,
    mirrored: Boolean,
) {
    val clock = remember { WenkuGlyphClock(symbol, active) }
    val ambientNow = rememberUpdatedState(ambient)
    val inherited = LocalWenkuIconContact.current

    var replayId by remember { mutableIntStateOf(0) }
    var replayDelay by remember { mutableIntStateOf(WENKU_ENTRANCE_NONE) }
    var anchorY by remember { mutableFloatStateOf(Float.NaN) }
    val entryToken = remember { intArrayOf(0) }

    // 状态 morph：起点从当前插值位置接上，避免中途切换符号时"跳回去"。
    LaunchedEffect(symbol, active) {
        val target = WenkuIconPoses.of(symbol, active)
        if (target == clock.to) return@LaunchedEffect
        clock.from = WenkuIconPoses.lerp(
            clock.from,
            clock.to,
            WenkuIconMotion.stateCurve.transform(clock.change.value),
        )
        clock.to = target
        clock.change.snapTo(0f)
        clock.change.animateTo(1f, tween(WenkuIconMotion.MORPH_MS, easing = LinearEasing))
    }

    // 接触位移：外部给定值直接接管；否则跟随 LocalWenkuIconContact 的按住状态。
    val contactTarget = when {
        externalContact != 0f -> externalContact.coerceIn(0f, 1f)
        inherited.pressed -> 1f
        else -> 0f
    }
    LaunchedEffect(contactTarget) {
        if (externalContact != 0f) {
            clock.contact.snapTo(externalContact.coerceIn(0f, 1f))
        } else if (contactTarget > 0f) {
            clock.contact.animateTo(1f, tween(140, easing = LinearEasing))
        } else {
            clock.contact.animateTo(0f, tween(240, easing = LinearEasing))
        }
    }

    // 入场：立刻以静止姿态起播，随后由批次把这一排的延迟写进手势时钟的起点。
    LaunchedEffect(entrance) {
        if (!entrance) return@LaunchedEffect
        entryToken[0]++
        val token = entryToken[0]
        replayDelay = 0
        replayId++
        val y = snapshotFlow { anchorY }.first { !it.isNaN() }
        WenkuIconEntranceBatch.submit(y) { delayMs ->
            if (entryToken[0] == token) clock.start = delayMs / 880f
        }
    }

    // 一次被接受的交互：重播一整段手势（对应 Dart 端的 trigger/activation）。
    LaunchedEffect(inherited.activation) {
        if (inherited.activation == 0) return@LaunchedEffect
        replayDelay = WENKU_ENTRANCE_NONE
        replayId++
    }

    // 手势时钟本体：640ms，或入场时的 880ms 窗口 + 520ms 有效跨度。
    LaunchedEffect(replayId) {
        if (replayId == 0) return@LaunchedEffect
        val entranceDelay = replayDelay
        clock.origin = sampleGesture(
            gesturePhase(clock.gesture.value, clock.start, clock.span),
            clock.origin,
        )
        clock.start = if (entranceDelay < 0) 0f else entranceDelay / 880f
        clock.span = if (entranceDelay < 0) 1f else 520f / 880f
        val durationMs = if (entranceDelay < 0) WenkuIconMotion.DURATION_MS else 880
        clock.gesture.snapTo(0f)
        clock.gesture.animateTo(1f, tween(durationMs, easing = LinearEasing))
        // 常驻图标：手势结束后完全静止 2600ms 再重播；这期间不排帧。
        if (ambientNow.value) {
            delay(WENKU_AMBIENT_REST_MS)
            if (ambientNow.value) {
                replayDelay = WENKU_ENTRANCE_NONE
                replayId++
            }
        }
    }

    Canvas(
        modifier.onGloballyPositioned { coordinates ->
            // 只记录首次布局位置：级联只关心"哪一排"，滚动时不要反复写状态。
            if (anchorY.isNaN()) {
                anchorY = coordinates.positionInRoot().y + coordinates.size.height / 2f
            }
        },
    ) {
        val phase = gesturePhase(clock.gesture.value, clock.start, clock.span)
        val sampled = externalGesture ?: sampleGesture(phase, clock.origin)
        val motion = sampled.plus(pressGesture(clock.contact.value), 1f)
        drawWenkuPose(
            from = clock.from,
            to = clock.to,
            change = clock.change.value,
            motion = motion,
            color = tint,
            contrast = contrast,
            mirrored = mirrored,
            sizeDp = sizeDp,
        )
    }
}

/** 前台可见性：进入后台时等价于 Dart 端的 `TickerMode.enabled == false`。 */
@Composable
private fun rememberWenkuForeground(): Boolean {
    val owner = LocalLifecycleOwner.current
    var foreground by remember(owner) {
        mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return foreground
}
