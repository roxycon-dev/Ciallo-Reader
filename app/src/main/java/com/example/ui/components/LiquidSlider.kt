package com.example.ui.components

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderColors
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.*

/** One physical and visual specification for every slider in the app. Units are dp/seconds. */
object LiquidSliderConfig {
    const val HEIGHT = 110f
    const val SAMPLES = 96
    const val MAX_STOPS = 200
    const val TRACK = 6f
    const val BEND = 11f
    const val TAIL = .045f
    const val RADIUS = 14f
    const val PRESS_RADIUS = 3.5f
    const val LENS = 1.6f
    const val POSITION_K = 760f
    const val POSITION_D = 36f
    const val STEP_K = 620f
    const val STEP_D = 30f
    const val BEND_K = 260f
    const val BEND_D = 9f
    const val PRESS_RATE = 14f
    const val MAX_DT = .033f
}

/** [rangeEnd] selects the two-lens variant; [steps] is the number of intermediate stops. */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    reverse: Boolean = false,
    rangeEnd: Float? = null,
    minGap: Float = 0f,
    onRangeChange: ((Float, Float) -> Unit)? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    onDraggingChange: ((Boolean) -> Unit)? = null,
    formatLabel: (Float) -> String = { "${it.roundToInt()}" },
) {
    if (!LocalRenderQuality.current.enhancedMotion) {
        OrdinarySlider(value, onValueChange, modifier, valueRange, steps, enabled, reverse,
            rangeEnd, minGap, onRangeChange, onValueChangeFinished, onDraggingChange, formatLabel)
        return
    }
    val palette = MaterialTheme.colorScheme
    // Reading panels may be dark glass even when the rest of the app uses a light theme.
    val dark = LocalContentColor.current.luminance() > .5f
    val reduce = com.example.ui.feedback.LocalReduceMotion.current
    val haptics = com.example.ui.feedback.LocalHapticsEnabled.current
    AndroidView(
        // An identity RenderNode isolates the View's frame invalidations. Keep Auto
        // compositing (no offscreen texture or clip) so the native pixels are unchanged.
        modifier = modifier.fillMaxWidth().height(LiquidSliderConfig.HEIGHT.dp).graphicsLayer {}.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(valueRange), valueRange, steps)
            stateDescription = if (rangeEnd == null) formatLabel(value) else "${formatLabel(value)} – ${formatLabel(rangeEnd)}"
            if (!enabled) disabled()
            setProgress { requested ->
                if (!enabled) false else {
                    val interval = (valueRange.endInclusive - valueRange.start) / (steps + 1)
                    val next = if (steps > 0 && interval > 0) valueRange.start +
                        ((requested - valueRange.start) / interval).roundToInt() * interval else requested
                    val safe = next.coerceIn(valueRange).coerceAtMost(rangeEnd?.minus(minGap) ?: valueRange.endInclusive)
                    if (rangeEnd != null) onRangeChange?.invoke(safe, rangeEnd) else onValueChange(safe)
                    onValueChangeFinished?.invoke(); true
                }
            }
            if (rangeEnd != null) customActions = listOf(
                CustomAccessibilityAction("增加范围终点") {
                    onRangeChange?.invoke(value, (rangeEnd + (valueRange.endInclusive - valueRange.start) / (steps + 1).coerceAtLeast(20)).coerceAtMost(valueRange.endInclusive)); true
                },
                CustomAccessibilityAction("减少范围终点") {
                    onRangeChange?.invoke(value, (rangeEnd - (valueRange.endInclusive - valueRange.start) / (steps + 1).coerceAtLeast(20)).coerceAtLeast(value + minGap)); true
                },
            )
        },
        factory = { LiquidSliderView(it) },
        update = {
            it.configure(valueRange, steps, enabled, reverse, minGap, palette.primary.toArgb(), dark, reduce, haptics)
            it.change = { a, b -> if (rangeEnd == null) onValueChange(a) else onRangeChange?.invoke(a, b) }
            it.finished = { onValueChangeFinished?.invoke() }
            it.draggingChanged = { active -> onDraggingChange?.invoke(active) }
            it.label = formatLabel
            it.setValues(value, rangeEnd)
        },
        onRelease = { it.stop() },
    )
}

/** Source-compatible adapter; rendering never delegates to Material's Slider. */
@Composable
fun Slider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
           enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
           steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors? = null,
           formatLabel: ((Float) -> String)? = null) =
    LiquidSlider(value, onValueChange, modifier, valueRange, steps, enabled,
        onValueChangeFinished = onValueChangeFinished,
        formatLabel = formatLabel ?: { if (valueRange == 0f..1f) "${(it * 100).roundToInt()}%" else "${it.roundToInt()}" })

internal class LiquidSliderView(context: Context) : View(context) {
    private val cfg = LiquidSliderConfig
    private var d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val trackPath = Path()
    private val liquidPath = Path()
    private val sampleY = FloatArray(LiquidSliderConfig.SAMPLES + 1)
    private val sampleHalfWidth = FloatArray(LiquidSliderConfig.SAMPLES + 1)
    private val lensPosition = FloatArray(2)
    private val lensVelocity = FloatArray(2)
    private val tailCenter = FloatArray(2)
    private val thicknessGain = FloatArray(2)
    private val stopX = FloatArray(LiquidSliderConfig.MAX_STOPS + 2)
    private val stopY = FloatArray(LiquidSliderConfig.MAX_STOPS + 2)
    private val stopRadius = FloatArray(LiquidSliderConfig.MAX_STOPS + 2)
    private val stopColor = IntArray(LiquidSliderConfig.MAX_STOPS + 2)
    private val lensGradientMatrix = Matrix()
    private var tintGradient: RadialGradient? = null
    private val lightGradient = RadialGradient(0f, 0f, 1f, 0xe6ffffff.toInt(), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    private val bubbleTypeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val bitmapDestination = Rect()
    private var bitmapCanvas: Canvas? = null
    private var liquidStart = 0f
    private var liquidEnd = 0f
    private val oval = RectF()
    private var buffer: Bitmap? = null
    private var lower = 0f
    private var upper = 1f
    private var stops = 0
    private var gap = 0f
    private var reverse = false
    private var ranged = false
    private var dark = false
    private var reduced = false
    private var haptics = true
    private var accent = Color.BLACK
    private var initialized = false
    private val position = floatArrayOf(0f, 1f)
    private val target = floatArrayOf(0f, 1f)
    private val velocity = FloatArray(2)
    private val press = FloatArray(2)
    private val bend = FloatArray(2)
    private val bendVelocity = FloatArray(2)
    private var tickPulse = FloatArray(2)
    private var lastStop = -1
    private var active = -1
    private var downX = 0f
    private var downY = 0f
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var thumbPress = false
    private var dragging = false
    private var touchSlop = ViewConfiguration.get(context).scaledTouchSlop / d
    private var horizontal = false
    private var abandoned = false
    private var published = false
    private var initialTarget = 0f
    private var running = false
    private var lastFrame = 0L
    var change: (Float, Float) -> Unit = { _, _ -> }
    var finished: () -> Unit = {}
    var draggingChanged: (Boolean) -> Unit = {}
    var label: (Float) -> String = { it.toString() }
    private val inset get() = min(26f, width / d / 3f)
    private val trackWidth get() = (width / d - inset * 2).coerceAtLeast(1f)
    private val cy get() = min(68f, height / d - 25f)
    private fun px(i: Int) = inset + (if (reverse) 1f - position[i] else position[i]) * trackWidth
    private fun norm(v: Float) = if (upper > lower && v.isFinite()) ((v - lower) / (upper - lower)).coerceIn(0f, 1f) else 0f
    private fun value(f: Float) = lower + f * (upper - lower)
    fun configure(range: ClosedFloatingPointRange<Float>, steps: Int, enabled: Boolean, reverse: Boolean,
                  minGap: Float, color: Int, dark: Boolean, reduce: Boolean, haptics: Boolean) {
        val appearanceChanged = accent != color || this.dark != dark || this.reverse != reverse || stops != steps.coerceIn(0, cfg.MAX_STOPS)
        val reductionChanged = reduced != reduce
        if (accent != color || tintGradient == null) {
            tintGradient = RadialGradient(0f, 0f, 1f,
                (color and 0x00ffffff) or ((.38f * 255).toInt() shl 24), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        }
        if (active >= 0 && (!enabled || lower != range.start || upper != range.endInclusive || this.reverse != reverse)) release(false)
        lower = range.start; upper = range.endInclusive.coerceAtLeast(lower); stops = steps.coerceIn(0, cfg.MAX_STOPS)
        gap = if (upper > lower) (minGap / (upper - lower)).coerceIn(0f, 1f) else 0f
        this.reverse = reverse; accent = color; this.dark = dark; reduced = reduce; this.haptics = haptics
        isEnabled = enabled; alpha = if (enabled) 1f else .4f
        if (appearanceChanged) invalidate()
        if (reductionChanged) wake()
    }
    fun setValues(a: Float, b: Float?) {
        val oldRanged = ranged
        val oldA = target[0]; val oldB = target[1]
        if (active >= 0 && ranged != (b != null)) release(false)
        ranged = b != null
        if (active < 0) {
            target[0] = norm(a).coerceAtMost(if (ranged) 1f - gap else 1f); target[1] = norm(b ?: upper).coerceAtLeast(target[0] + if (ranged) gap else 0f)
            if (!initialized) { target.copyInto(position); initialized = true; wake(); return }
        }
        if (oldRanged != ranged || oldA != target[0] || oldB != target[1]) wake()
    }
    private fun wake() { invalidate(); if (!running && isAttachedToWindow) { running = true; lastFrame = 0L; postOnAnimation(frame) } }
    private val frame = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = if (lastFrame == 0L) 1f / 120 else ((now - lastFrame) / 1e9f).coerceIn(.001f, cfg.MAX_DT)
            lastFrame = now
            val moving = advancePhysics(dt)
            invalidate()
            if (moving && windowVisibility == VISIBLE) postOnAnimation(this) else { running = false; lastFrame = 0 }
        }
    }
    internal fun advancePhysics(delta: Float): Boolean {
        val dt = delta.coerceIn(.001f, cfg.MAX_DT)
        val h = dt / 2f
        val k = if (stops > 0) cfg.STEP_K else cfg.POSITION_K
        val damping = if (stops > 0) cfg.STEP_D else cfg.POSITION_D
        val pressGain = 1f - exp(-cfg.PRESS_RATE * h)
        var moving = false
        repeat(2) {
            for (i in 0 until if (ranged) 2 else 1) {
                velocity[i] += ((target[i] - position[i]) * k - damping * velocity[i]) * h
                position[i] += velocity[i] * h
                position[i] = position[i].coerceIn(if (i == 1 && ranged) position[0] + gap else 0f,
                    if (i == 0 && ranged) max(0f, position[1] - gap) else 1f)
                press[i] += ((if (i == active) 1f else 0f) - press[i]) * pressGain
                bendVelocity[i] += ((cfg.BEND * press[i] - bend[i]) * cfg.BEND_K - cfg.BEND_D * bendVelocity[i]) * h
                bend[i] += bendVelocity[i] * h
                tickPulse[i] = (tickPulse[i] - h / .33f).coerceAtLeast(0f)
                if (reduced) { position[i] = target[i]; velocity[i] = 0f; bend[i] = 0f; bendVelocity[i] = 0f }
                moving = moving || abs(target[i] - position[i]) > .0001f || abs(velocity[i]) > .001f ||
                    abs(press[i] - if (i == active) 1f else 0f) > .002f || abs(bendVelocity[i]) > .02f || abs(bend[i] - cfg.BEND * press[i]) > .02f || tickPulse[i] > 0
            }
        }
        return moving
    }
    fun stop() { release(false); removeCallbacks(frame); running = false; buffer?.recycle(); buffer = null; bitmapCanvas = null; active = -1 }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val density = resources.displayMetrics.density
        if (d != density) {
            d = density
            touchSlop = ViewConfiguration.get(context).scaledTouchSlop / d
            buffer?.recycle(); buffer = null; bitmapCanvas = null
            release(false); wake()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { release(false); buffer?.recycle(); buffer = null; bitmapCanvas = null; wake() }
    private fun publish(f: Float) {
        if (active < 0) return
        val bounded = f.coerceIn(if (active == 1) target[0] + gap else 0f, if (active == 0 && ranged) target[1] - gap else 1f)
        if (abs(bounded - target[active]) < .000001f) return
        target[active] = bounded
        published = true
        if (stops > 0) {
            val tick = (bounded * (stops + 1)).roundToInt()
            if (tick != lastStop) {
                lastStop = tick; tickPulse[active] = 1f
                if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        // Discrete consumers only receive valid stops, while the lens follows the finger freely.
        fun output(i: Int) = value(if (stops > 0) (target[i] * (stops + 1)).roundToInt().toFloat() / (stops + 1) else target[i])
        change(output(0), output(1)); wake()
    }
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || upper <= lower) return false
        val index = if (e.actionMasked == MotionEvent.ACTION_DOWN) 0 else e.findPointerIndex(pointerId)
        if (index < 0) { release(false); return false }
        val x = e.getX(index) / d; val y = e.getY(index) / d
        fun fraction(at: Float): Float { val f = ((at - inset) / trackWidth).coerceIn(0f, 1f); return if (reverse) 1f - f else f }
        fun requested() = if (thumbPress) initialTarget + (x - downX) / trackWidth * (if (reverse) -1f else 1f) else fraction(x)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The reserved bubble space is not a seek target. A scrolling finger may
                // cross this View without changing playback, brightness or a range endpoint.
                if (abs(y - cy) > 28f + cfg.BEND) return false
                val distance0 = abs(x - px(0)); val distance1 = abs(x - px(1))
                active = if (ranged && (distance1 < distance0 || abs(distance1 - distance0) < .01f &&
                    (if (reverse) x < px(0) else x > px(0)))) 1 else 0
                downX = x; downY = y; horizontal = false; abandoned = false
                pointerId = e.getPointerId(0); dragging = false
                published = false; initialTarget = target[active]
                thumbPress = abs(x - px(active)) <= 28f
                lastStop = if (stops > 0) (target[active] * (stops + 1)).roundToInt() else -1
                wake(); return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { abandoned = true; release(false); return false }
            MotionEvent.ACTION_MOVE -> {
                if (abandoned || active < 0) return false
                if (!horizontal && abs(y - downY) > touchSlop && abs(y - downY) >= abs(x - downX)) {
                    abandoned = true; release(false); parent?.requestDisallowInterceptTouchEvent(false); return false
                }
                if (!horizontal && abs(x - downX) > touchSlop && abs(x - downX) > abs(y - downY)) {
                    horizontal = true; dragging = true; draggingChanged(true)
                }
                if (horizontal) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    publish(requested())
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!abandoned && active >= 0) {
                    if (horizontal) publish(requested())
                    else if (!thumbPress && abs(x - downX) <= touchSlop && abs(y - downY) <= touchSlop) publish(fraction(x))
                    performClick(); release(true)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> { release(false); return true }
        }
        return true
    }
    private fun release(commit: Boolean) {
        if (active < 0) return
        if (!commit && !published) target[active] = initialTarget
        if (stops > 0) target[active] = ((target[active] * (stops + 1)).roundToInt().toFloat() / (stops + 1))
            .coerceIn(if (active == 1) target[0] + gap else 0f, if (active == 0 && ranged) target[1] - gap else 1f)
        active = -1; pointerId = MotionEvent.INVALID_POINTER_ID
        if (dragging) { dragging = false; draggingChanged(false) }
        if (published) finished()
        parent?.requestDisallowInterceptTouchEvent(false); wake()
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun tint(alpha: Float) = (accent and 0x00ffffff) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
    private fun y(x: Float): Float {
        var result = cy
        for (i in 0 until if (ranged) 2 else 1) {
            val distance = (x - tailCenter[i]) / (trackWidth * .28f)
            result += bend[i] * exp(-(distance * distance))
        }
        return result
    }
    private fun liquidWidth(x: Float): Float {
        var thickness = cfg.TRACK
        for (i in 0 until if (ranged) 2 else 1) {
            val distance = (x - lensPosition[i]) / 34f
            thickness += thicknessGain[i] * exp(-(distance * distance))
        }
        return thickness
    }
    /** Build the physical curves once, then replay exactly the same paths through each lens. */
    private fun buildBaseGeometry() {
        for (i in 0 until if (ranged) 2 else 1) {
            lensPosition[i] = px(i)
            lensVelocity[i] = velocity[i] * trackWidth * if (reverse) -1 else 1
            tailCenter[i] = lensPosition[i] - lensVelocity[i] * cfg.TAIL
            thicknessGain[i] = 4.5f * (abs(velocity[i] * trackWidth) / 900f).coerceIn(0f, 1f) + 3f * press[i]
        }
        trackPath.reset()
        for (j in 0..cfg.SAMPLES) {
            val x = inset + trackWidth * j / cfg.SAMPLES
            if (j == 0) trackPath.moveTo(x, y(x)) else trackPath.lineTo(x, y(x))
        }
        liquidStart = if (ranged) min(px(0), px(1)) else if (reverse) px(0) else inset
        liquidEnd = if (ranged) max(px(0), px(1)) else if (reverse) inset + trackWidth else px(0)
        liquidPath.reset()
        for (j in 0..cfg.SAMPLES) {
            val x = liquidStart + (liquidEnd - liquidStart) * j / cfg.SAMPLES
            sampleY[j] = y(x); sampleHalfWidth[j] = liquidWidth(x) / 2f
            if (j == 0) liquidPath.moveTo(x, sampleY[j] - sampleHalfWidth[j]) else liquidPath.lineTo(x, sampleY[j] - sampleHalfWidth[j])
        }
        for (j in cfg.SAMPLES downTo 0) liquidPath.lineTo(liquidStart + (liquidEnd - liquidStart) * j / cfg.SAMPLES, sampleY[j] + sampleHalfWidth[j])
        liquidPath.close()
        if (stops > 0) for (j in 0..stops + 1) {
            val x = inset + trackWidth * j / (stops + 1)
            var pulse = 0f
            for (i in 0 until if (ranged) 2 else 1) if (abs(lensPosition[i] - x) < trackWidth / (stops + 1) * .5f) pulse = max(pulse, tickPulse[i])
            stopX[j] = x; stopY[j] = y(x)
            stopRadius[j] = 2.4f + if (pulse > 0f) 3f * sin(pulse * PI.toFloat()) else 0f
            stopColor[j] = if (x in liquidStart..liquidEnd) 0xe6ffffff.toInt() else if (dark) 0x66ffffff else 0x55000000
        }
    }
    private fun base(c: Canvas) {
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = cfg.TRACK; paint.color = if (dark) 0x29ffffff else 0x1a000000
        c.drawPath(trackPath, paint)
        val start = liquidStart; val end = liquidEnd
        paint.style = Paint.Style.FILL; paint.color = accent
        c.drawPath(liquidPath, paint)
        c.drawCircle(start, sampleY[0], sampleHalfWidth[0], paint)
        c.drawCircle(end, sampleY[cfg.SAMPLES], sampleHalfWidth[cfg.SAMPLES], paint)
        if (stops > 0) for (j in 0..stops + 1) {
            paint.color = stopColor[j]
            c.drawCircle(stopX[j], stopY[j], stopRadius[j], paint)
        }
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val scale = min(d, 2f)
        val bw = max(1, (width / d * scale).roundToInt()); val bh = max(1, (height / d * scale).roundToInt())
        var bitmap = buffer
        if (bitmap == null || bitmap.width != bw || bitmap.height != bh) { bitmap?.recycle(); bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); buffer = bitmap; bitmapCanvas = Canvas(bitmap) }
        bitmap.eraseColor(Color.TRANSPARENT)
        val c = bitmapCanvas ?: return
        val saved = c.save(); c.scale(scale, scale)
        buildBaseGeometry()
        base(c)
        for (i in 0 until if (ranged) 2 else 1) {
            val p = press[i]; val x = lensPosition[i]; val centerY = y(x)
            val v = lensVelocity[i]
            val stretch = (abs(v) / 1400f).coerceIn(0f, .45f)
            val radius = cfg.RADIUS + p * cfg.PRESS_RADIUS
            val rx = radius * (1 + stretch); val ry = radius * (1 - stretch * .5f)
            paint.color = tint(.16f * p); c.drawCircle(x, centerY, radius + 8f, paint)
            oval.set(x - rx, centerY - ry, x + rx, centerY + ry)
            paint.color = if (dark) 0x24ffffff else Color.rgb(255 - ((255 - Color.red(accent)) * .06f).toInt(), 255 - ((255 - Color.green(accent)) * .06f).toInt(), 255 - ((255 - Color.blue(accent)) * .06f).toInt())
            paint.setShadowLayer(10f + 8f * p, 0f, 3f + 2f * p, if (p > .02f) tint(.22f + .18f * p) else 0x38000000)
            c.drawOval(oval, paint); paint.clearShadowLayer()
            c.save(); path.reset(); path.addOval(oval, Path.Direction.CW); c.clipPath(path)
            c.save(); c.scale(cfg.LENS, cfg.LENS, x, centerY); base(c); c.restore()
            paint.style = Paint.Style.FILL
            lensGradientMatrix.setScale(ry * 1.4f, ry * 1.4f)
            lensGradientMatrix.postTranslate(x, centerY + ry)
            tintGradient?.setLocalMatrix(lensGradientMatrix)
            paint.shader = tintGradient
            c.drawOval(oval, paint)
            lensGradientMatrix.setScale(rx * .85f, rx * .85f)
            lensGradientMatrix.postTranslate(x - rx * .35f, centerY - ry * .5f)
            lightGradient.setLocalMatrix(lensGradientMatrix)
            paint.shader = lightGradient
            c.drawOval(oval, paint); paint.shader = null; c.restore()
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.6f; paint.color = if (dark) 0xb3ffffff.toInt() else 0xf2ffffff.toInt(); c.drawOval(oval, paint)
            oval.inset(-.9f, -.9f); paint.strokeWidth = 1f; paint.color = tint(.45f); c.drawOval(oval, paint)
            oval.set(x - rx + 4f, centerY - ry + 4f, x + rx - 4f, centerY + ry - 4f)
            paint.color = Color.WHITE; paint.strokeWidth = 2.2f; c.drawArc(oval, 194.4f, 61.2f, false, paint)
            paint.style = Paint.Style.FILL; c.drawCircle(x + rx * .43f, centerY + ry * .4f, 1.6f, paint)
            if (p > .005f) {
                val text = label(value(if (stops > 0) (target[i] * (stops + 1)).roundToInt().toFloat() / (stops + 1) else target[i]))
                paint.textSize = 12f; paint.typeface = bubbleTypeface
                val textWidth = paint.measureText(text)
                val half = min(width / d / 2f, textWidth / 2 + 9f)
                val bx = (x - v * .03f).coerceIn(half, max(half, width / d - half))
                val by = centerY - 42f - 4f * p
                paint.color = tint(p); c.drawRoundRect(bx - half, by - 12, bx + half, by + 12, 12f, 12f, paint)
                paint.color = Color.argb((255 * p).toInt(), 255, 255, 255)
                c.drawText(text, bx - textWidth / 2, by - (paint.ascent() + paint.descent()) / 2, paint)
            }
        }
        paint.shader = null; paint.color = Color.WHITE
        c.restoreToCount(saved)
        bitmapDestination.set(0, 0, width, height)
        canvas.drawBitmap(bitmap, null, bitmapDestination, paint)
    }
}
