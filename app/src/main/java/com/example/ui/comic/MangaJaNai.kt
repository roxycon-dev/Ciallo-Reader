package com.example.ui.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

internal class MangaJaNaiBudgetExceeded : IOException("增强超时，已保留原图")

/** Author's mobile SPAN model, bundled for reliable offline operation.
 * Measured neural work budget, original-detail residual composition and one shared session.
 */
internal object MangaJaNai {
    const val MODEL_BYTES = 1_673_288L
    const val SHA256 = "718b8670ab396a5174e5b4b38f9d3947beaac3afbe9647d494d65925180a2a7b"
    const val TILE = 128
    const val HALO = 24
    private const val SIDE = TILE + HALO * 2
    private const val DEADLINE_NS = 4_300_000_000L
    private val lock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var context: Context? = null
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private val silent = ThreadLocal<Boolean>()
    private var runtime: MangaJaNaiRuntime? = null
    private val tileCache = MangaJaNaiTileCache(1)
    private var verifiedStamp: String? = null
    private var generation = 0L
    private var idleRelease: java.util.concurrent.ScheduledFuture<*>? = null
    private val cleanup = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mangajanai-release").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    // Never share with memory-pressure cleanup: that worker may be waiting for the page lock.
    private val watchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mangajanai-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    internal fun <T> withSilentStatus(background: Boolean, work: () -> T): T {
        val previous = silent.get()
        silent.set(background)
        return try { work() } finally { silent.set(previous) }
    }
    private fun report(value: String) { if (silent.get() != true) _status.value = value }

    fun configure(context: Context) {
        val app = context.applicationContext
        if (this.context === app) return
        this.context = app
        app.registerComponentCallbacks(object : android.content.ComponentCallbacks2 {
            override fun onConfigurationChanged(config: android.content.res.Configuration) = Unit
            override fun onLowMemory() { cleanup.execute { release() } }
            override fun onTrimMemory(level: Int) {
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cleanup.execute { release() }
            }
        })
    }

    fun release() {
        lock.lock()
        try { generation++; idleRelease?.cancel(false); runtime?.close(); runtime = null; tileCache.clear() }
        finally { lock.unlock() }
    }

    private fun releaseWhenIdle() {
        val expected = generation
        idleRelease = cleanup.schedule({
            if (lock.tryLock()) {
                try { if (generation == expected) { runtime?.close(); runtime = null; tileCache.clear() } }
                finally { lock.unlock() }
            }
        }, 60, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun checkCancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
    }

    internal fun verify(file: File): Boolean {
        if (file.length() != MODEL_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                checkCancelled()
                val n = input.read(bytes)
                if (n < 0) break
                digest.update(bytes, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == SHA256
    }

    /** APK includes the lightweight weights; never wait for a network model download. */
    internal fun model(): File {
        val app = context ?: throw IOException("增强引擎尚未初始化")
        val directory = File(app.filesDir, "mangajanai").apply { mkdirs() }
        val file = File(directory, "janai-span-v3-f32.onnx")
        val stamp = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        if (verifiedStamp == stamp || verify(file)) {
            verifiedStamp = stamp
            File(directory, "2x-1200p-v1.onnx").delete()
            return file
        }
        val part = File(directory, "mobile.part")
        try {
            app.assets.open("comic_enhancement/janai-span-mobile.onnx").use { source ->
                part.outputStream().use { target -> source.copyTo(target, 64 * 1024) }
            }
            if (!verify(part)) throw IOException("增强模型校验失败")
            if (file.exists() && !file.delete()) throw IOException("无法替换增强模型")
            if (!part.renameTo(file)) throw IOException("无法保存增强模型")
            verifiedStamp = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            File(directory, "2x-1200p-v1.onnx").delete() // obsolete private model only
            return file
        } finally { part.delete() }
    }

    internal fun outputSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = min(2.0, min(3200.0 / maxOf(width, height), sqrt(4_000_000.0 / (width.toDouble() * height))))
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    fun enhance(src: Bitmap, strength: Float): Bitmap {
        lock.lockInterruptibly()
        return try {
            generation++
            idleRelease?.cancel(false)
            checkCancelled()
            val started = System.nanoTime()
            val deadline = started + DEADLINE_NS
            val file = model()
            val engine = runtime ?: MangaJaNaiRuntime(checkNotNull(context)).also { runtime = it }
            val area = SIDE * SIDE
            val coreSide = TILE * 2
            val coreArea = coreSide * coreSide
            val input = ByteBuffer.allocateDirect(area * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val buffer = ByteBuffer.allocateDirect(area * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val sourceTile = IntArray(area)
            val inputValues = FloatArray(area * 3)
            val coreValues = FloatArray(coreArea * 3)
            fun packInput() {
                for (i in sourceTile.indices) {
                    val p = sourceTile[i]
                    inputValues[i] = ((p shr 16) and 255) / 255f
                    inputValues[area + i] = ((p shr 8) and 255) / 255f
                    inputValues[area * 2 + i] = (p and 255) / 255f
                }
                input.position(0); input.put(inputValues); input.position(0)
            }
            readMangaJaNaiTile(src, 0, 0, sourceTile)
            packInput()
            val env = OrtEnvironment.getEnvironment()
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, (SIDE * 2).toLong(), (SIDE * 2).toLong())).use { tensorOut ->
                    val options = OrtSession.RunOptions()
                    var optionsOpen = true
                    val timer = watchdog.schedule({
                        synchronized(options) { if (optionsOpen) runCatching { options.setTerminate(true) } }
                    }, (deadline - System.nanoTime()).coerceAtLeast(1L), java.util.concurrent.TimeUnit.NANOSECONDS)
                    try {
                    report("引擎准备中…")
                    engine.prepare(file, tensor, tensorOut, buffer, options, deadline)
                    val neuralBudget = minOf(3_200_000_000L, deadline - System.nanoTime() - 700_000_000L)
                    if (neuralBudget <= 0) throw MangaJaNaiBudgetExceeded()
                    val countBudget = (neuralBudget / (engine.tileNanoseconds * 1.4)).toInt().coerceAtLeast(1)
                    val (iw, ih) = mangaMobileInputSize(src.width, src.height, countBudget)
                    val reduced = if (iw == src.width && ih == src.height) src else Bitmap.createScaledBitmap(src, iw, ih, true)
                    try {
                    val neural = Bitmap.createBitmap(iw * 2, ih * 2, Bitmap.Config.ARGB_8888)
                    try {
                    val tile = Bitmap.createBitmap(coreSide, coreSide, Bitmap.Config.ARGB_8888)
                    try {
                    val pixels = IntArray(coreArea)
                    val canvas = Canvas(neural)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    run {
                        val inputs = mapOf("input" to tensor)
                        val outputs = mapOf("output" to tensorOut)
                        val total = ((iw + TILE - 1) / TILE) * ((ih + TILE - 1) / TILE)
                        var completed = 0; var inferred = 0; var lastPercent = -1
                        for (y in 0 until ih step TILE) for (x in 0 until iw step TILE) {
                            checkCancelled()
                            if (System.nanoTime() >= deadline) throw MangaJaNaiBudgetExceeded()
                            readMangaJaNaiTile(reduced, x, y, sourceTile)
                            val cached = tileCache.find(sourceTile)
                            val core = cached ?: run {
                                packInput()
                                engine.run(file, inputs, outputs, options, deadline)
                                inferred++
                                for (c in 0..2) for (ty in 0 until coreSide) {
                                    buffer.position(c * area * 4 + (ty + HALO * 2) * SIDE * 2 + HALO * 2)
                                    buffer.get(coreValues, c * coreArea + ty * coreSide, coreSide)
                                }
                                buffer.position(0)
                                tileCache.put(sourceTile, coreValues)
                                coreValues
                            }
                            val tw = min(TILE, iw - x) * 2
                            val th = min(TILE, ih - y) * 2
                            for (ty in 0 until th) for (tx in 0 until tw) {
                                val i = ty * coreSide + tx
                                fun channel(c: Int) = (core[c * coreArea + i].coerceIn(0f, 1f) * 255f).roundToInt()
                                pixels[i] = -0x1000000 or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                            }
                            tile.setPixels(pixels, 0, coreSide, 0, 0, tw, th)
                            canvas.drawBitmap(tile, Rect(0, 0, tw, th), RectF(x * 2f, y * 2f, x * 2f + tw, y * 2f + th), paint)
                            val percent = ++completed * 100 / total
                            if (percent != lastPercent) { lastPercent = percent; report("增强 $percent%") }
                        }
                        val (ow, oh) = outputSize(src.width, src.height)
                        val output = combineMangaJaNaiDetail(src, reduced, neural, ow, oh, strength)
                        report("")
                        android.util.Log.i("MangaJaNai", "aiComplete=true source=${src.width}x${src.height} neural=${iw}x${ih} tiles=$total inferred=$inferred totalMs=${(System.nanoTime() - started) / 1_000_000}")
                        output
                    }
                    } finally { tile.recycle() }
                    } finally { neural.recycle() }
                    } finally { if (reduced !== src) reduced.recycle() }
                    } finally {
                        timer.cancel(false)
                        synchronized(options) { optionsOpen = false; options.close() }
                    }
                }
            }
        } catch (error: MangaJaNaiBudgetExceeded) {
            runtime?.penalizeTimeout()
            report("增强超时 · 已保留原图")
            throw error // No partial/fallback bitmap is cached or counted as a completed AI page.
        } catch (error: InterruptedException) { throw error }
        catch (error: OutOfMemoryError) {
            runtime?.close(); runtime = null; tileCache.clear()
            report("内存不足 · 已保留原图")
            throw IOException("增强内存不足", error)
        }
        catch (error: LinkageError) {
            runtime?.close(); runtime = null; tileCache.clear()
            report("增强不可用 · 已保留原图")
            throw IOException("当前设备无法加载增强引擎", error)
        } catch (error: Exception) {
            runtime?.close(); runtime = null; tileCache.clear()
            report("增强失败 · 已保留原图")
            throw error
        } finally { releaseWhenIdle(); lock.unlock() }
    }
}
