package com.example.ui.components

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Coordinates are dp, so the same springs behave identically at every display density. */
internal class LiquidSegmentPhysics {
    class Spring(var x: Float = 0f, var v: Float = 0f, var target: Float = 0f)
    class Satellite(val anchor: Float, val stiffness: Float, val radius: Float, val offsetY: Float) {
        var x = 0f; var y = 0f; var vx = 0f; var vy = 0f; var r = 0f; var vr = 0f
        val damping = 2f * sqrt(stiffness) * .62f
    }
    val left = Spring()
    val right = Spring()
    val wobble = Spring()
    val press = Spring()
    val satellites = arrayOf(Satellite(-.3f, 140f, 11f, 3f), Satellite(.15f, 95f, 8f, -4f), Satellite(-.05f, 65f, 5.5f, 1f))
    var width = 0f; private set
    var height = 0f; private set
    var count = 0; private set
    var index = 0; private set
    var dragging = false; private set
    private var previousVelocity = 0f
    private var dragLeft = 0f
    private var dragDistance = 0f
    val slotWidth get() = ((width - 8f) / count.coerceAtLeast(1)).coerceAtLeast(1f)
    val center get() = (left.x + right.x) * .5f
    val velocity get() = (left.v + right.v) * .5f

    fun resize(w: Float, h: Float, n: Int, selectedIndex: Int) {
        width = w; height = h; count = n
        dragging = false
        select(selectedIndex, snap = true)
    }

    fun select(i: Int, snap: Boolean = false) {
        index = i.coerceIn(0, (count - 1).coerceAtLeast(0))
        left.target = 4f + slotWidth * index
        right.target = left.target + slotWidth
        if (snap) settle()
    }

    fun settle() {
        left.x = left.target; right.x = right.target
        left.v = 0f; right.v = 0f; previousVelocity = 0f
        wobble.x = 0f; wobble.v = 0f; press.x = press.target; press.v = 0f
        satellites.forEach { q ->
            q.x = center + q.anchor * slotWidth * .5f; q.y = height * .5f + q.offsetY
            q.vx = 0f; q.vy = 0f; q.r = 0f; q.vr = 0f
        }
    }

    fun beginDrag() {
        dragging = true; dragLeft = left.x; dragDistance = 0f; press.target = 1f
    }

    fun dragBy(distance: Float, speed: Float, reduceMotion: Boolean) {
        dragDistance += distance
        val limit = 4f + slotWidth * (count - 1).coerceAtLeast(0)
        var x = dragLeft + dragDistance
        if (x < 4f) x = 4f - (4f - x) * .25f
        if (x > limit) x = limit + (x - limit) * .25f
        val v = if (reduceMotion) 0f else speed.coerceIn(-2400f, 2400f)
        val stretch = min(abs(v) / 60f, 14f)
        left.x = x - if (v < 0f) stretch else 0f
        right.x = x + slotWidth + if (v > 0f) stretch else 0f
        left.v = v; right.v = v
        index = ((x - 4f) / slotWidth + .5f).toInt().coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    fun endDrag(i: Int = index, reduceMotion: Boolean = false) {
        dragging = false; press.target = 0f
        left.v = left.v.coerceIn(-1500f, 1500f); right.v = right.v.coerceIn(-1500f, 1500f)
        select(i, reduceMotion)
    }

    /** Returns false after convergence; the renderer then removes its frame callback. */
    fun step(seconds: Float): Boolean {
        var remaining = seconds.coerceIn(0f, 1f / 30f)
        while (remaining > 0f) {
            val dt = min(remaining, 1f / 120f)
            remaining -= dt
            if (!dragging) {
                val movingRight = right.target > right.x || left.target > left.x
                integrate(if (movingRight) right else left, 520f, 30f, dt)
                integrate(if (movingRight) left else right, 230f, 21f, dt)
            } else {
                // A stationary finger must not keep injecting artificial velocity indefinitely.
                left.v *= exp(-12f * dt); right.v = left.v
            }
            val v = velocity
            val acceleration = (v - previousVelocity) / dt
            previousVelocity = v
            wobble.v += (-190f * wobble.x - 7.5f * wobble.v - .0036f * acceleration) * dt
            wobble.x = (wobble.x + wobble.v * dt).coerceIn(-.55f, .55f)
            integrate(press, 260f, 22f, dt)
            val halfWidth = (right.x - left.x) * .5f
            val speedFactor = ((abs(v) - 120f) / 900f).coerceIn(0f, 1f)
            satellites.forEach { q ->
                q.vx += (-q.stiffness * (q.x - center - q.anchor * halfWidth) - q.damping * q.vx) * dt
                q.x += q.vx * dt
                q.vy += (-120f * (q.y - height * .5f - q.offsetY) - 12f * q.vy) * dt
                q.y += q.vy * dt
                val bounded = q.x.coerceIn(4f + q.r, max(4f + q.r, width - 4f - q.r))
                if (bounded != q.x) { q.x = bounded; q.vx = 0f }
                val far = ((hypot(q.x - center, q.y - height * .5f) - halfWidth * .55f) / 26f).coerceIn(0f, 1f)
                val targetRadius = q.radius * max(speedFactor, far)
                q.vr += (-220f * (q.r - targetRadius) - 22f * q.vr) * dt
                q.r = max(0f, q.r + q.vr * dt)
            }
        }
        val calm = (dragging || (abs(left.v) < 2f && abs(right.v) < 2f && abs(left.x - left.target) < .1f && abs(right.x - right.target) < .1f)) &&
            abs(velocity) < 2f && abs(wobble.x) < .002f && abs(wobble.v) < .02f &&
            abs(press.x - press.target) < .002f && abs(press.v) < .02f &&
            satellites.all { it.r < .05f && abs(it.vx) < 1f && abs(it.vy) < 1f && abs(it.vr) < .05f }
        if (calm && !dragging) settle()
        return !calm
    }

    private fun integrate(s: Spring, stiffness: Float, damping: Float, dt: Float) {
        s.v += (-stiffness * (s.x - s.target) - damping * s.v) * dt
        s.x += s.v * dt
    }
}
