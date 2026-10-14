package com.example.ui.components

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.view.View

/** One small native drawing node; no Compose state is written by the animation loop. */
internal class LiquidSegmentView(context: Context) : View(context) {
    internal val physics = LiquidSegmentPhysics()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inactiveLabelsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val activeLabelsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassMatrix = Matrix()
    private var fallbackGlass: LinearGradient? = null
    private val heart = Path()
    private val visibleRect = Rect()
    private var trackBitmap: Bitmap? = null
    private var glyphBitmap: Bitmap? = null
    private var optics: Optics? = null
    private var pendingFrame = false
    private var previousFrame = 0L
    private var active = true
    private var released = false
    private var reduceMotion = false
    private var useOptics = true
    private var options: List<LiquidSegmentOption> = emptyList()
    private var selectedId = 0
    private var baseColor = Color.WHITE
    private var accentColor = Color.BLACK
    private var inkColor = Color.GRAY
    private var secondaryColor = Color.GRAY
    private var dark = false
    private var fontSp = 12f
    private var scale = resources.displayMetrics.density
    private var fontScale = resources.configuration.fontScale

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        setWillNotDraw(false)
    }

    fun configure(items: List<LiquidSegmentOption>, selected: Int, base: Int, accent: Int, ink: Int,
                  secondary: Int, isDark: Boolean, textSp: Float, reduced: Boolean, enabled: Boolean, opticalEffects: Boolean) {
        val density = resources.displayMetrics.density
        val fs = resources.configuration.fontScale
        val textureChanged = options != items || baseColor != base || dark != isDark || fontSp != textSp || scale != density || fontScale != fs || useOptics != opticalEffects
        val colorChanged = accentColor != accent || inkColor != ink || secondaryColor != secondary
        val countChanged = options.size != items.size
        options = items; baseColor = base; accentColor = accent; inkColor = ink; secondaryColor = secondary
        dark = isDark; fontSp = textSp; scale = density; fontScale = fs
        useOptics = opticalEffects
        if (colorChanged || fallbackGlass == null) {
            inactiveLabelsPaint.colorFilter = android.graphics.PorterDuffColorFilter(inkColor, android.graphics.PorterDuff.Mode.SRC_IN)
            activeLabelsPaint.colorFilter = android.graphics.PorterDuffColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN)
            fallbackGlass = LinearGradient(0f, 0f, 1f, 1f,
                intArrayOf(0x66ffffff, (accentColor and 0x00ffffff) or 0x30000000, 0x22ffffff), null, Shader.TileMode.CLAMP)
        }
        val reductionChanged = reduceMotion != reduced
        reduceMotion = reduced
        if (countChanged || physics.count == 0) resizePhysics(selected)
        if (textureChanged) rebuildTextures()
        if (colorChanged) invalidate()
        if (selectedId != selected || countChanged) {
            selectedId = selected
            if (!physics.dragging) physics.select(indexOf(selected), reduceMotion || !active)
            requestFrame()
            invalidate()
        }
        if (reductionChanged && reduceMotion) { physics.settle(); stopFrames(); invalidate() }
        setActive(enabled)
    }

    private fun indexOf(id: Int) = options.indexOfFirst { it.id == id }.coerceAtLeast(0)

    private fun resizePhysics(selected: Int = selectedId) {
        if (width <= 0 || height <= 0) return
        physics.resize(width / scale, height / scale, options.size, indexOf(selected))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resizePhysics(); rebuildTextures()
    }

    private fun rebuildTextures() {
        if (width <= 0 || height <= 0 || options.isEmpty() || released) return
        // These are the only full-resolution textures. Reuse allocations on theme/label changes.
        if (trackBitmap?.width != width || trackBitmap?.height != height) {
            trackBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // The shader only reads glyph alpha: 1 byte/pixel preserves coverage, versus 4 for RGBA.
            glyphBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
        }
        val background = trackBitmap ?: return
        val glyphs = glyphBitmap ?: return
        background.eraseColor(baseColor); glyphs.eraseColor(Color.TRANSPARENT)
        val c = Canvas(background)
        val h = height.toFloat(); val w = width.toFloat()
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(if (dark) 0x56000000 else 0x2b825a50, if (dark) 0x0dffffff else 0x12825a50),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(0f, 0f, w, h, h / 2, h / 2, paint)
        // Inset shadow is baked once, clipped to the groove; bottom lip is baked too.
        c.save(); heart.reset(); heart.addRoundRect(0f, 0f, w, h, h / 2, h / 2, Path.Direction.CW); c.clipPath(heart)
        paint.shader = LinearGradient(0f, 0f, 0f, 8f * scale,
            if (dark) 0x66000000 else 0x38702319, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, 8f * scale, paint)
        paint.shader = null; paint.color = if (dark) 0x1affffff else 0x286e463c
        for (i in 1 until options.size) {
            val x = (4f + physics.slotWidth * i) * scale
            c.drawRect(x - .5f * scale, h * .32f, x + .5f * scale, h * .68f, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = scale
        paint.shader = LinearGradient(0f, h * .45f, 0f, h,
            Color.TRANSPARENT, if (dark) 0x24ffffff else 0xccffffff.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(.5f * scale, .5f * scale, w - .5f * scale, h - .5f * scale, h / 2, h / 2, paint)
        c.restore(); paint.style = Paint.Style.FILL; paint.shader = null
        val gc = Canvas(glyphs)
        glyphPaint.color = Color.WHITE
        glyphPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        options.forEachIndexed { i, option ->
            glyphPaint.textSize = fontSp * fontScale * scale
            // Leave a little breathing room even with long labels or a large system font.
            val badge = if (option.badge > 0) option.badge.toString() else ""
            val iconWidth = if (option.favorite) 18f * scale else 0f
            val gap = if (badge.isNotEmpty()) 5f * scale else 0f
            val badgeWidth = if (badge.isNotEmpty()) {
                glyphPaint.textSize = 10f * fontScale * scale
                glyphPaint.measureText(badge)
            } else 0f
            glyphPaint.textSize = fontSp * fontScale * scale
            val available = (physics.slotWidth - 10f).coerceAtLeast(1f) * scale
            val labelWidth = glyphPaint.measureText(option.label)
            val fit = (available / (labelWidth + iconWidth + gap + badgeWidth)).coerceAtMost(1f)
            gc.save()
            val cx = (4f + physics.slotWidth * (i + .5f)) * scale
            gc.scale(fit, fit, cx, h / 2)
            var x = cx - (labelWidth + iconWidth + gap + badgeWidth) / 2
            if (option.favorite) {
                drawHeart(gc, x, h / 2, glyphPaint); x += iconWidth
            }
            val metrics = glyphPaint.fontMetrics
            gc.drawText(option.label, x, h / 2 - (metrics.ascent + metrics.descent) / 2, glyphPaint)
            if (badge.isNotEmpty()) {
                glyphPaint.textSize = 10f * fontScale * scale
                val bm = glyphPaint.fontMetrics
                gc.drawText(badge, x + labelWidth + gap, h / 2 - (bm.ascent + bm.descent) / 2, glyphPaint)
            }
            gc.restore()
        }
        if (Build.VERSION.SDK_INT >= 33 && useOptics) {
            optics = runCatching { (optics ?: Optics()).also { it.bind(background, glyphs) } }.getOrNull()
        } else optics = null
        cacheIdleLayer()
        invalidate()
    }

    private fun drawHeart(c: Canvas, x: Float, cy: Float, p: Paint) {
        c.save(); c.translate(x, cy - 7f * scale); c.scale(scale, scale)
        heart.reset(); heart.moveTo(7f, 13f)
        heart.cubicTo(5f, 11f, 0f, 7f, 0f, 4f); heart.cubicTo(0f, -1f, 5f, -1f, 7f, 3f)
        heart.cubicTo(9f, -1f, 14f, -1f, 14f, 4f); heart.cubicTo(14f, 7f, 9f, 11f, 7f, 13f)
        heart.close(); c.drawPath(heart, p); c.restore()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (options.isEmpty() || trackBitmap == null) return
        val shader = optics
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated && shader != null) {
            try {
                shader.draw(canvas, physics, scale, accentColor, inkColor, secondaryColor, dark, hasSelection())
                return
            } catch (_: RuntimeException) {
                optics = null // Driver incompatibility: retain functioning native glass controls.
            }
        }
        drawFallback(canvas)
    }

    private fun drawFallback(c: Canvas) {
        val h = height.toFloat()
        c.save(); heart.reset(); heart.addRoundRect(0f, 0f, width.toFloat(), h, h / 2, h / 2, Path.Direction.CW); c.clipPath(heart)
        paint.shader = null; paint.color = Color.WHITE; paint.alpha = 255
        trackBitmap?.let { c.drawBitmap(it, 0f, 0f, paint) }
        if (!hasSelection()) {
            glyphBitmap?.let { c.drawBitmap(it, 0f, 0f, inactiveLabelsPaint) }
            c.restore()
            return
        }
        val l = physics.left.x * scale; val r = physics.right.x * scale
        glassMatrix.setScale(r - l, h - 8f * scale); glassMatrix.postTranslate(l, 4f * scale)
        fallbackGlass?.setLocalMatrix(glassMatrix)
        paint.shader = fallbackGlass
        c.drawRoundRect(l, 4f * scale, r, h - 4f * scale, h / 2, h / 2, paint)
        paint.shader = null; paint.style = Paint.Style.STROKE; paint.strokeWidth = scale; paint.color = 0x88ffffff.toInt()
        c.drawRoundRect(l, 4f * scale, r, h - 4f * scale, h / 2, h / 2, paint)
        paint.style = Paint.Style.FILL
        glyphBitmap?.let { c.drawBitmap(it, 0f, 0f, inactiveLabelsPaint) }
        c.save(); c.clipRect(l, 0f, r, h)
        glyphBitmap?.let { c.drawBitmap(it, 0f, 0f, activeLabelsPaint) }
        c.restore(); c.restore()
    }

    fun beginDrag() { if (!released) { physics.beginDrag(); requestFrame() } }
    private fun hasSelection() = physics.dragging || options.any { it.id == selectedId }
    fun setDropPressed(value: Boolean) {
        physics.press.target = if (value && !reduceMotion) 1f else 0f
        requestFrame()
    }
    fun dragBy(px: Float, velocityPx: Float) {
        physics.dragBy(px / scale, velocityPx / scale, reduceMotion)
        invalidate(); requestFrame()
    }
    fun endDrag(cancelled: Boolean): Int {
        val index = if (cancelled) indexOf(selectedId) else physics.index
        physics.endDrag(index, reduceMotion); requestFrame(); invalidate()
        return options.getOrNull(index)?.id ?: selectedId
    }
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (!value) {
            physics.endDrag(indexOf(selectedId), reduceMotion = true)
            stopFrames()
            cacheIdleLayer()
        } else { cacheIdleLayer(); invalidate() }
    }
    private fun requestFrame() {
        if (released || !active || !isAttachedToWindow || reduceMotion || pendingFrame) return
        if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        pendingFrame = true; previousFrame = 0L; postOnAnimation(frame)
    }
    private val frame = object : Runnable {
        override fun run() {
            pendingFrame = false
            if (released || !active || !isShown || !getLocalVisibleRect(visibleRect)) {
                physics.endDrag(indexOf(selectedId), reduceMotion = true)
                if (!released) invalidate()
                return
            }
            val now = System.nanoTime()
            val dt = if (previousFrame == 0L) 1f / 60f else (now - previousFrame) / 1_000_000_000f
            previousFrame = now
            val moving = physics.step(dt)
            invalidate()
            if (moving) { pendingFrame = true; postOnAnimation(this) } else cacheIdleLayer()
        }
    }
    private fun stopFrames() { removeCallbacks(frame); pendingFrame = false; previousFrame = 0L }
    private fun cacheIdleLayer() {
        // A resting drop is rasterized once, including while its parent scrolls. Only this small
        // control gets a layer, and it is discarded during motion, hiding, or disposal.
        val type = if (active && !pendingFrame && !physics.dragging && optics != null && isHardwareAccelerated) LAYER_TYPE_HARDWARE else LAYER_TYPE_NONE
        if (layerType != type) setLayerType(type, null)
    }
    override fun onDetachedFromWindow() {
        stopFrames(); physics.endDrag(indexOf(selectedId), reduceMotion = true)
        if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        super.onDetachedFromWindow()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) {
            stopFrames(); physics.endDrag(indexOf(selectedId), reduceMotion = true)
            if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        } else { cacheIdleLayer(); invalidate() }
    }
    fun release() {
        released = true; stopFrames(); optics = null; trackBitmap = null; glyphBitmap = null
        if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
    }

    @TargetApi(33)
    private class Optics {
        private val shader = RuntimeShader(LIQUID_SEGMENT_SHADER)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = this@Optics.shader }
        fun bind(track: Bitmap, glyphs: Bitmap) {
            shader.setInputShader("track", BitmapShader(track, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            shader.setInputShader("labels", BitmapShader(glyphs, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        }
        fun draw(c: Canvas, s: LiquidSegmentPhysics, density: Float, accent: Int, ink: Int, secondary: Int, dark: Boolean, selected: Boolean) {
            shader.setFloatUniform("selectionActive", if (selected) 1f else 0f)
            shader.setFloatUniform("resolution", s.width, s.height)
            shader.setFloatUniform("density", density)
            shader.setFloatUniform("slotWidth", s.slotWidth)
            shader.setFloatUniform("leftEdge", s.left.x); shader.setFloatUniform("rightEdge", s.right.x)
            shader.setFloatUniform("velocity", s.velocity); shader.setFloatUniform("wobble", s.wobble.x); shader.setFloatUniform("press", s.press.x)
            shader.setFloatUniform("accent", Color.red(accent) / 255f, Color.green(accent) / 255f, Color.blue(accent) / 255f)
            shader.setFloatUniform("ink", Color.red(ink) / 255f, Color.green(ink) / 255f, Color.blue(ink) / 255f)
            shader.setFloatUniform("glow", (Color.red(accent) * .75f + Color.red(secondary) * .25f) / 255f,
                (Color.green(accent) * .75f + Color.green(secondary) * .25f) / 255f,
                (Color.blue(accent) * .75f + Color.blue(secondary) * .25f) / 255f)
            shader.setFloatUniform("lip", if (dark) .14f else .8f)
            s.satellites.forEachIndexed { i, q -> shader.setFloatUniform(SATELLITE_NAMES[i], q.x, q.y, q.r) }
            c.drawRect(0f, 0f, s.width * density, s.height * density, paint)
        }
        companion object { private val SATELLITE_NAMES = arrayOf("satellite0", "satellite1", "satellite2") }
    }
}
