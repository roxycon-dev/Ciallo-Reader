package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer

/** Compose 1.7's API 28+ toImageBitmap calls Bitmap.createBitmap(Picture) synchronously.
 * Keep recording/RenderNode access on the UI thread; consume the offscreen surface promptly
 * and copy its pixels on a worker, without waiting for a GPU bitmap on the UI thread.
 */
internal suspend fun capturePaperBitmap(layer: GraphicsLayer): Bitmap = withContext(Dispatchers.Main.immediate) {
    if (Build.VERSION.SDK_INT >= 29) {
        try { return@withContext PaperSnapshotTargets.capture(layer) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // A device rejecting the offscreen Surface still gets the exact full-quality
            // capture path. Backend compatibility never changes its paper or glass style.
            PaperRenderStats.readbackFallbacks++
            if (com.example.BuildConfig.DEBUG) android.util.Log.w("PaperCapture", "Offscreen readback: ${layer.size}", failure)
        }
    }
    PaperRenderStats.legacySnapshots++
    layer.toImageBitmap().asAndroidBitmap()
}

/** The caller's readback semaphore serializes requests. Pool access is UI-thread confined. */
@RequiresApi(29)
private object PaperSnapshotTargets {
    private const val CACHE_TARGETS = 2
    private const val IDLE_MS = 15_000L
    private const val RESULT_TIMEOUT_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val targets = LinkedHashMap<Long, Target>(CACHE_TARGETS, .75f, true)
    private val trim = Runnable { targets.values.forEach { it.close() }; targets.clear() }

    suspend fun capture(layer: GraphicsLayer): Bitmap {
        check(Looper.myLooper() == Looper.getMainLooper())
        handler.removeCallbacks(trim)
        val width = layer.size.width; val height = layer.size.height
        require(width > 0 && height > 0)
        val key = (width.toLong() shl 32) or height.toLong()
        val target = targets[key] ?: Target(width, height).also {
            if (targets.size >= CACHE_TARGETS) {
                val oldest = targets.entries.iterator().next()
                targets.remove(oldest.key); oldest.value.close()
            }
            targets[key] = it
        }
        var complete = false
        var image: Image? = null
        try {
            target.drain()
            val result = CompletableDeferred<Image>()
            target.receiver = result
            val canvas = target.node.beginRecording(width, height)
            try {
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(canvas), Size(width.toFloat(), height.toFloat())) {
                    drawLayer(layer)
                }
            } finally { target.node.endRecording() }
            val status = target.renderer.createRenderRequest().setVsyncTime(System.nanoTime())
                .setWaitForPresent(false).syncAndDraw()
            val errors = HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
                HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or HardwareRenderer.SYNC_FRAME_DROPPED
            check(status and errors == 0) { "Paper surface did not produce a frame ($status)" }
            val captured = withTimeoutOrNull(RESULT_TIMEOUT_MS) { result.await() }
                ?: throw IllegalStateException("Paper surface readback timed out")
            image = captured
            val bitmap = withContext(Dispatchers.Default) {
                val plane = captured.planes[0]
                target.pixels.copy(plane.buffer, plane.rowStride, plane.pixelStride)
            }
            PaperRenderStats.asyncSnapshots++
            complete = true
            return bitmap
        } finally {
            image?.close(); target.receiver = null
            // Never let a pooled target retain a previous window's child display lists.
            target.node.discardDisplayList()
            if (!complete) {
                // Retire cancelled requests so a late frame cannot satisfy the next window.
                targets.remove(key); target.close()
            }
            handler.postDelayed(trim, IDLE_MS)
        }
    }

    private class Target(val width: Int, val height: Int) {
        val pixels = PaperPixelBuffer(width, height)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val node = RenderNode("Paper snapshot").apply { setPosition(0, 0, width, height); clipToBounds = false }
        val renderer = HardwareRenderer().apply { isOpaque = false; setSurface(reader.surface); setContentRoot(node) }
        var receiver: CompletableDeferred<Image>? = null
        private var closed = false
        init {
            reader.setOnImageAvailableListener({ available ->
                try {
                    val image = available.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val pending = receiver
                    if (pending != null && !pending.isCompleted) pending.complete(image) else image.close()
                } catch (failure: IllegalStateException) { receiver?.completeExceptionally(failure) }
            }, handler)
        }
        fun drain() { while (true) (reader.acquireLatestImage() ?: break).close() }
        fun close() {
            if (closed) return
            closed = true; receiver?.cancel(); receiver = null
            renderer.stop(); renderer.destroy(); reader.close(); node.discardDisplayList()
        }
    }
}

/** Reusable row packing, with a fresh immutable-to-the-renderer bitmap for each capture. */
internal class PaperPixelBuffer(private val width: Int, private val height: Int) {
    private val row = ByteArray(width * 4)
    private var packed: ByteBuffer? = null
    fun copy(source: ByteBuffer, rowStride: Int, pixelStride: Int): Bitmap {
        require(pixelStride == 4 && rowStride >= row.size) { "Unsupported paper pixel stride" }
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        if (rowStride == row.size) {
            source.rewind(); result.copyPixelsFromBuffer(source)
        } else {
            // The final row need not include padding. Copy only its visible bytes.
            val data = packed ?: ByteBuffer.allocateDirect(width * height * 4).also { packed = it }
            data.clear()
            repeat(height) { y -> source.position(y * rowStride); source.get(row); data.put(row) }
            data.rewind(); result.copyPixelsFromBuffer(data)
        }
        result.prepareToDraw()
        return result
    }
}
