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
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.roundToInt

/** Official MangaJaNai V1 weights, community ONNX conversion. No extra inference runtime.
 * One page/session at a time, reusable tiny RGB tiles, no full-page float tensors or 2x intermediate.
 * Weight memory is unavoidable (~64 MiB); sessions and native activations close after every page.
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
    fun configure(context: Context) { this.context = context.applicationContext }

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
        if (verify(file)) return file
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
            checkCancelled()
            val file = model()
            val environment = OrtEnvironment.getEnvironment()
            // Create from file path: avoid another 64 MB Java byte[] copy of weights.
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
                options.setMemoryPatternOptimization(false)
                options.setCPUArenaAllocator(false)
                environment.createSession(file.absolutePath, options).use { session ->
                    val (width, height) = outputSize(src.width, src.height)
                    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    val area = SIDE * SIDE
                    val input = FloatBuffer.allocate(area * 3)
                    val row = IntArray(SIDE)
                    val pixels = IntArray(TILE * 2 * TILE * 2)
                    val tile = Bitmap.createBitmap(TILE * 2, TILE * 2, Bitmap.Config.ARGB_8888)
                    val sx = width.toFloat() / src.width
                    val sy = height.toFloat() / src.height
                    try {
                        val total = ((src.width + TILE - 1) / TILE) * ((src.height + TILE - 1) / TILE)
                        var completed = 0
                        for (y in 0 until src.height step TILE) for (x in 0 until src.width step TILE) {
                            checkCancelled()
                            // Replicated-edge halo: every tile has exactly the same inference shape.
                            for (ty in 0 until SIDE) {
                                val sourceY = (y + ty - HALO).coerceIn(0, src.height - 1)
                                val left = (x - HALO).coerceAtLeast(0)
                                val right = (x + TILE + HALO).coerceAtMost(src.width)
                                src.getPixels(row, 0, right - left, left, sourceY, right - left, 1)
                                for (tx in 0 until SIDE) {
                                    val p = row[(x + tx - HALO).coerceIn(0, src.width - 1) - left]
                                    val i = ty * SIDE + tx
                                    input.put(i, ((p shr 16) and 255) / 255f)
                                    input.put(area + i, ((p shr 8) and 255) / 255f)
                                    input.put(area * 2 + i, (p and 255) / 255f)
                                }
                            }
                            val tw = min(TILE, src.width - x) * 2
                            val th = min(TILE, src.height - y) * 2
                            OnnxTensor.createTensor(environment, input, longArrayOf(1, 3, SIDE.toLong(), SIDE.toLong())).use { tensor ->
                                session.run(mapOf("input" to tensor)).use { result ->
                                    checkCancelled()
                                    val tensorOut = result[0] as OnnxTensor
                                    val shape = tensorOut.info.shape
                                    check(shape.contentEquals(longArrayOf(1, 3, (SIDE * 2).toLong(), (SIDE * 2).toLong()))) { "MangaJaNai 输出格式不符" }
                                    val buffer = tensorOut.floatBuffer
                                    val outArea = area * 4
                                    for (ty in 0 until th) {
                                        src.getPixels(row, 0, tw / 2, x, y + ty / 2, tw / 2, 1)
                                        for (tx in 0 until tw) {
                                            val i = (ty + HALO * 2) * SIDE * 2 + tx + HALO * 2
                                            val original = row[tx / 2]
                                            fun channel(c: Int): Int {
                                                val from = (original shr ((2 - c) * 8)) and 255
                                                val to = buffer.get(c * outArea + i).coerceIn(0f, 1f) * 255f
                                                return (from + (to - from) * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
                                            }
                                            pixels[ty * TILE * 2 + tx] = (original and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                                        }
                                    }
                                }
                            }
                            tile.setPixels(pixels, 0, TILE * 2, 0, 0, tw, th)
                            canvas.drawBitmap(tile, Rect(0, 0, tw, th), RectF(x * sx, y * sy, (x + tw / 2) * sx, (y + th / 2) * sy), paint)
                            _status.value = "增强 ${++completed * 100 / total}%"
                        }
                        _status.value = ""
                        output
                    } catch (error: Throwable) {
                        output.recycle()
                        _status.value = "增强失败，点击重试"
                        throw error
                    } finally { tile.recycle() }
                }
            }
        } catch (error: LinkageError) {
            _status.value = "增强失败，点击重试"
            throw IOException("当前设备无法加载增强引擎", error)
        } finally { lock.unlock() }
    }
}
