package com.example.data

import android.content.Context
import android.os.SystemClock

/** Recover only from an actual UI render failure during entry, never from a normal restart. */
internal object HomeRenderRecovery {
    const val ENTRY_WINDOW_MS = 20_000L
    private var nextToken = 0L
    private var activeToken = 0L
    private var enteredAt = 0L

    @Synchronized fun arm(): Long {
        enteredAt = SystemClock.uptimeMillis()
        activeToken = ++nextToken
        return activeToken
    }

    @Synchronized fun disarm(token: Long) {
        if (activeToken == token) activeToken = 0L
    }

    @Synchronized fun onUncaught(context: Context, failure: Throwable) {
        if (activeToken == 0L || SystemClock.uptimeMillis() - enteredAt !in 0..ENTRY_WINDOW_MS) return
        if (!isRenderFailure(failure)) return
        val prefs = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
        if (prefs.getInt("render_quality", 2) < 2) return
        // The process is about to exit. apply() can lose this write before Android kills it.
        // Only the display preference changes; no database, source or onboarding state is reset.
        prefs.edit().putInt("render_quality", -1).putBoolean("home_render_recovery_notice", true).commit()
        activeToken = 0L
    }

    fun consumeNotice(context: Context): Boolean {
        val prefs = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("home_render_recovery_notice", false)) return false
        prefs.edit().remove("home_render_recovery_notice").apply()
        return true
    }

    private fun isRenderFailure(failure: Throwable): Boolean {
        var cause: Throwable? = failure
        var renderFrame = false
        repeat(8) {
            val error = cause ?: return renderFrame
            if (error is VirtualMachineError || error is ThreadDeath) return false
            if (error.stackTrace.any { frame ->
                frame.className.startsWith("android.graphics.") ||
                    frame.className.startsWith("androidx.compose.ui.graphics.") ||
                    (frame.className.startsWith("androidx.compose.ui.node.") &&
                        listOf("measure", "layout", "draw", "place").any { frame.methodName.contains(it, ignoreCase = true) }) ||
                    listOf("GlassCard", "LiquidSegmentView", "LiquidSliderView", "PullOutRender").any {
                        frame.className.startsWith("com.example.ui.components.$it")
                    } ||
                    frame.className.startsWith("com.kashif_e.backdrop.") ||
                    frame.className.startsWith("dev.liquidglass.")
            }) renderFrame = true
            cause = error.cause?.takeUnless { it === error }
        }
        return renderFrame
    }
}
