package com.example.ui.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
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

/** Official MangaJaNai V1 weights, community ONNX conversion. No extra inference runtime.
 * One page at a time, fixed original RGB tiles and FP32 weights; optimized CPU or validated hardware.
 * One warm session is shared while reading, then released after 15 idle seconds or reader exit.
 */
internal object MangaJaNai {
    const val MODEL_BYTES = 67_191_396L
    const val SHA256 = "c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0"
    const val REVISION = "2f67ff30209f2e0c75b7c55e6f3ffc3adbc960f7"
    const val MODEL_URL = "https://huggingface.co/haesslerian/MangaJaNai_V1_ONNX/resolve/$REVISION/2x_MangaJaNai_1200p_V1_ESRGAN_70k.onnx"
    const val TILE = 48
    const val HALO = 16
    private const val SIDE = TILE + HALO * 2
    private val lock = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var context: Context? = null
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private var runtime: MangaJaNaiRuntime? = null
    private val tileCache = MangaJaNaiTileCache()
    private var verifiedStamp: String? = null
    private var generation = 0L
    private var idleRelease: java.util.concurrent.ScheduledFuture<*>? = null
    private val cleanup = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mangajanai-release").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
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
        }, 15, java.util.concurrent.TimeUnit.SECONDS)
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

    /** Download streams straight to disk, then verifies before atomic rename. Never readBytes(). */
    private fun model(): File {
        val app = context ?: throw IOException("MangaJaNai 尚未初始化")
        val directory = File(app.filesDir, "mangajanai").apply { mkdirs() }
        val file = File(directory, "2x-1200p-v1.onnx")
        val stamp = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
        if (verifiedStamp == stamp || verify(file)) { verifiedStamp = stamp; return file }
        val part = File(directory, "model.part")
        val client = com.example.source.SharedHttpTransport.builder()
            .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(10, java.util.concurrent.TimeUnit.MINUTES).build()
        try {
            _status.value = "模型下载中…"
            val call = client.newCall(okhttp3.Request.Builder().url(MODEL_URL).build())
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("模型下载失败：${response.code}")
                val body = response.body ?: throw IOException("模型文件为空")
                body.byteStream().buffered().use { input ->
                    part.outputStream().buffered().use { output ->
                        val bytes = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            checkCancelled()
                            val n = input.read(bytes)
                            if (n < 0) break
                            total += n
                            if (total > MODEL_BYTES) throw IOException("模型大小不符")
                            output.write(bytes, 0, n)
                            _status.value = "模型下载 ${(total * 100 / MODEL_BYTES).coerceAtMost(100)}%"
                        }
                    }
                }
            }
            if (!verify(part)) throw IOException("模型校验失败，请重试")
            if (file.exists() && !file.delete()) throw IOException("无法替换模型")
            if (!part.renameTo(file)) throw IOException("无法保存模型")
            verifiedStamp = "${file.absolutePath}:${file.lastModified()}:${file.length()}"
            return file
        } catch (error: Exception) {
            _status.value = "模型下载失败，点击重试"
            throw error
        } finally { part.delete() } // Transport shares the application's dispatcher and connection pool.
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
            val file = model()
            val engine = runtime ?: MangaJaNaiRuntime(checkNotNull(context)).also { runtime = it }
            val environment = OrtEnvironment.getEnvironment()
            val (width, height) = outputSize(src.width, src.height)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val area = SIDE * SIDE
            // Direct buffers and pinned tensors are allocated once per page, not once per tile.
            val input = ByteBuffer.allocateDirect(area * 3 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val buffer = ByteBuffer.allocateDirect(area * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val sourceTile = IntArray(area)
            val inputValues = FloatArray(area * 3)
            val coreSide = TILE * 2
            val coreArea = coreSide * coreSide
            val coreValues = FloatArray(coreArea * 3)
            val pixels = IntArray(TILE * 2 * TILE * 2)
            val tile = Bitmap.createBitmap(TILE * 2, TILE * 2, Bitmap.Config.ARGB_8888)
            val sx = width.toFloat() / src.width
            val sy = height.toFloat() / src.height
            try {
                OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                    OnnxTensor.createTensor(environment, buffer, longArrayOf(1, 3, (SIDE * 2).toLong(), (SIDE * 2).toLong())).use { tensorOut ->
                        val inputs = mapOf("input" to tensor)
                        val outputs = mapOf("output" to tensorOut)
                        val total = ((src.width + TILE - 1) / TILE) * ((src.height + TILE - 1) / TILE)
                        var completed = 0
                        var inferred = 0
                        var cacheHits = 0
                        var lastPercent = -1
                        for (y in 0 until src.height step TILE) for (x in 0 until src.width step TILE) {
                            checkCancelled()
                            readMangaJaNaiTile(src, x, y, sourceTile)
                            val cached = tileCache.find(sourceTile)
                            val core = if (cached != null) {
                                cacheHits++
                                cached
                            } else {
                                for (i in sourceTile.indices) {
                                    val p = sourceTile[i]
                                    inputValues[i] = ((p shr 16) and 255) / 255f
                                    inputValues[area + i] = ((p shr 8) and 255) / 255f
                                    inputValues[area * 2 + i] = (p and 255) / 255f
                                }
                                input.position(0)
                                input.put(inputValues)
                                input.position(0)
                                if (inferred == 0) {
                                    _status.value = "引擎准备中…"
                                    engine.prepare(file, tensor, tensorOut, buffer)
                                }
                                engine.run(file, inputs, outputs)
                                inferred++
                                // Copy only the unchanged 96x96 FP32 core, using bulk reads.
                                for (c in 0..2) for (ty in 0 until coreSide) {
                                    buffer.position(c * area * 4 + (ty + HALO * 2) * SIDE * 2 + HALO * 2)
                                    buffer.get(coreValues, c * coreArea + ty * coreSide, coreSide)
                                }
                                buffer.position(0)
                                tileCache.put(sourceTile, coreValues)
                                coreValues
                            }
                            val tw = min(TILE, src.width - x) * 2
                            val th = min(TILE, src.height - y) * 2
                            for (ty in 0 until th) {
                                for (tx in 0 until tw) {
                                    val i = ty * coreSide + tx
                                    val original = sourceTile[(ty / 2 + HALO) * SIDE + tx / 2 + HALO]
                                    fun channel(c: Int): Int {
                                        val from = (original shr ((2 - c) * 8)) and 255
                                        val to = core[c * coreArea + i].coerceIn(0f, 1f) * 255f
                                        return (from + (to - from) * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
                                    }
                                    pixels[ty * TILE * 2 + tx] = (original and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                                }
                            }
                            tile.setPixels(pixels, 0, TILE * 2, 0, 0, tw, th)
                            canvas.drawBitmap(tile, Rect(0, 0, tw, th), RectF(x * sx, y * sy, (x + tw / 2) * sx, (y + th / 2) * sy), paint)
                            val percent = ++completed * 100 / total
                            if (percent != lastPercent) { lastPercent = percent; _status.value = "增强 $percent%" }
                        }
                        _status.value = ""
                        android.util.Log.i("MangaJaNai", "page=${src.width}x${src.height} tiles=$total inferred=$inferred exactReuse=$cacheHits totalMs=${(System.nanoTime() - started) / 1_000_000}")
                        output
                    }
                }
            } catch (error: Throwable) {
                output.recycle()
                _status.value = "增强失败，点击重试"
                if (error !is InterruptedException) { engine.close(); runtime = null; tileCache.clear() }
                throw error
            } finally { tile.recycle() }
        } catch (error: LinkageError) {
            _status.value = "增强失败，点击重试"
            throw IOException("当前设备无法加载增强引擎", error)
        } finally { releaseWhenIdle(); lock.unlock() }
    }
}
