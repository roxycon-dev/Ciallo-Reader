package com.example.ui.components

import android.app.Activity
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.LruCache
import android.view.View
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap
import kotlin.math.roundToInt

private const val BLUR_STEPS_PER_PIXEL = 2f
private const val BLUR_CACHE_CAPACITY = 128
private const val WINDOW_DIM_STEPS = 64f
private const val GLASS_WINDOW_DIM = .12f
private const val FALLBACK_WINDOW_DIM = .42f

/** Observes the owning window's paper clock without recomposing its content.
 * Blur, native dim and Compose scrims use the same endpoint-smooth progress.
 */
@Composable
fun GlassDialogWindowEffect(activity: Activity?, blurRadiusPx: Float) {
    if (!LocalRenderQuality.current.realtimeGlass) return

    val view = LocalView.current
    val lifecycle = LocalPaperBackdropOwner.current ?: LocalPaperOwner.current
    val owner = remember(view) { Any() }
    val window = (view.parent as? DialogWindowProvider)?.window
    val background = activity?.window?.decorView

    DisposableEffect(view, background, window, owner) {
        window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        onDispose {
            if (Build.VERSION.SDK_INT >= 31 && background != null) {
                GlassWindowBlur.release(background, owner)
            }
            window?.setDimAmount(0f)
        }
    }
    LaunchedEffect(view, background, window, lifecycle, blurRadiusPx) {
        var previousDim = -1f
        snapshotFlow { paperBackdropProgress(lifecycle?.progress ?: 1f) }.collect { progress ->
            if (supportsRealtimeBlur && background != null) {
                GlassWindowBlur.acquire(background, owner, blurRadiusPx * progress)
            }
            val maximumDim = if (supportsRealtimeBlur) GLASS_WINDOW_DIM else FALLBACK_WINDOW_DIM
            val dim = maximumDim * (progress * WINDOW_DIM_STEPS).roundToInt() / WINDOW_DIM_STEPS
            if (dim != previousDim) {
                window?.setDimAmount(dim)
                previousDim = dim
            }
        }
    }
}

/** One native blur per activity. A fading child cannot erase its parent's blur.
 * Integer radius steps avoid replacing boxed Float map values every frame.
 */
internal object GlassWindowBlur {
    private class Owner(var radiusStep: Int)

    private class Host {
        val owners = mutableMapOf<Any, Owner>()
        val effects = LruCache<Int, RenderEffect>(BLUR_CACHE_CAPACITY)
        var appliedStep = 0
    }

    private val hosts = WeakHashMap<View, Host>()

    internal fun radius(view: View): Float = (hosts[view]?.appliedStep ?: 0) / BLUR_STEPS_PER_PIXEL

    @RequiresApi(31)
    fun acquire(view: View, owner: Any, radius: Float) {
        val host = hosts.getOrPut(view) { Host() }
        val step = (radius.coerceAtLeast(0f) * BLUR_STEPS_PER_PIXEL).roundToInt()
        val entry = host.owners[owner]
        if (entry == null) {
            host.owners[owner] = Owner(step)
        } else {
            if (entry.radiusStep == step) return
            entry.radiusStep = step
        }
        apply(view, host)
    }

    @RequiresApi(31)
    fun release(view: View, owner: Any) {
        val host = hosts[view] ?: return
        if (host.owners.remove(owner) == null) return
        apply(view, host)
        if (host.owners.isEmpty()) hosts.remove(view)
    }

    @RequiresApi(31)
    private fun apply(view: View, host: Host) {
        var step = 0
        for (owner in host.owners.values) step = maxOf(step, owner.radiusStep)
        if (step == host.appliedStep) return

        val effect = if (step == 0) null else host.effects[step] ?: run {
            val radius = step / BLUR_STEPS_PER_PIXEL
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP).also {
                host.effects.put(step, it)
            }
        }
        view.setRenderEffect(effect)
        host.appliedStep = step
    }
}
