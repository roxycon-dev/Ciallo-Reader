package com.example.ui.comic

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
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min
import kotlin.math.roundToInt

/** Full original ESRGAN network and FP32 weights; never downsamples the neural input. */
internal object MangaJaNaiFull {
    const val MODEL_BYTES = 67_191_396L
    const val SHA256 = "c985d6b78ed1a659867cd5697ce80743ad9ddd73868f463e69c824f15f169eb0"
    private const val PACK_URL = "https://github.com/roxycon-dev/Ciallo-Reader/releases/download/v1.3.6/MangaJaNai-full-v1-fp32.zip"
    private const val MODEL_URL = "https://huggingface.co/haesslerian/MangaJaNai_V1_ONNX/resolve/2f67ff30209f2e0c75b7c55e6f3ffc3adbc960f7/2x_MangaJaNai_1200p_V1_ESRGAN_70k.onnx"
    private const val HALO = 16
    private val lock get() = MangaJaNai.lock
    private var context: Context? = null
    private var runtime: MangaJaNaiFullRuntime? = null
    private val tileCache = MangaJaNaiTileCache(1)
    private var cacheCore = 0
    private val _status = MutableStateFlow("")
    val status = _status.asStateFlow()
    private var stamp: String? = null
    private var nativeStamp: String? = null
    private var generation = 0L
    private var idle: java.util.concurrent.ScheduledFuture<*>? = null
    private val cleanup = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mangajanai-full-release").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }
    private fun report(value: String) { if (!MangaJaNai.isSilent) _status.value = value }
    fun configure(app: Context) { context = app.applicationContext }
    fun release() {
        lock.lock()
        try { generation++; idle?.cancel(false); runtime?.close(); runtime = null; tileCache.clear(); cacheCore = 0 }
        finally { lock.unlock() }
    }
    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
    }
    private fun verified(file: File, bytes: Long, hash: String): Boolean {
        if (file.length() != bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buffer = ByteArray(64 * 1024)
            while (true) { cancelled(); val count = source.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == hash
    }
    private fun fileStamp(file: File) = "${file.path}:${file.length()}:${file.lastModified()}"
    internal fun verifyNative(directory: File): Boolean {
        val param = File(directory, "full.param"); val bin = File(directory, "full.bin")
        val current = fileStamp(param) + fileStamp(bin)
        if (nativeStamp == current) return true
        val valid = verified(param, 102_736L, "92bb351b492ba789925b345710ad45c4d5e6cc696540a55348c5f82fb4c36cf8") &&
            verified(bin, 66_814_088L, "c0f66830e66bbc75ce45364bfef1317fa7b16813bc427ff6903aceafb757cb80")
        if (valid) nativeStamp = current
        return valid
    }
    private fun download(url: String, file: File, limit: Long) {
        val client = com.example.source.SharedHttpTransport.builder().readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES).build()
        val call = client.newCall(okhttp3.Request.Builder().url(url).build())
        val worker = Thread.currentThread()
        val cancel = cleanup.scheduleAtFixedRate({ if (worker.isInterrupted) call.cancel() }, 100, 100, TimeUnit.MILLISECONDS)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("完整模型下载失败：${response.code}")
                val body = response.body ?: throw IOException("完整模型为空")
                body.byteStream().buffered().use { source -> file.outputStream().buffered().use { target ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        cancelled(); val count = source.read(buffer); if (count < 0) break
                        total += count; if (total > limit) throw IOException("完整模型大小不符")
                        target.write(buffer, 0, count)
                        report("完整模型 ${(total / (1024 * 1024))} MB")
                    }
                } }
            }
        } finally { cancel.cancel(false) }
    }
    /** Only exact known entry names are accepted; streamed files are verified before replacement. */
    internal fun unpack(archive: File, directory: File) {
        val expected = mapOf("full.onnx" to MODEL_BYTES, "full.bin" to 66_814_088L, "full.param" to 102_736L)
        val seen = HashSet<String>()
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    cancelled(); val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || !seen.add(entry.name)) throw IOException("完整模型包无效")
                    val limit = expected[entry.name] ?: if (entry.name in listOf("manifest.json", "NOTICE.txt")) 8192L else throw IOException("完整模型包无效")
                    val part = File(directory, entry.name + ".part")
                    part.outputStream().buffered().use { target ->
                        val buffer = ByteArray(64 * 1024); var total = 0L
                        while (true) { cancelled(); val count = zip.read(buffer); if (count < 0) break; total += count
                            if (total > limit) throw IOException("完整模型包过大")
                            target.write(buffer, 0, count)
                        }
                        if (entry.name in expected && total != limit) throw IOException("完整模型包不完整")
                    }
                    zip.closeEntry()
                }
            }
            if (!seen.containsAll(expected.keys) || !verified(File(directory, "full.onnx.part"), MODEL_BYTES, SHA256) ||
                !verified(File(directory, "full.param.part"), 102_736L, "92bb351b492ba789925b345710ad45c4d5e6cc696540a55348c5f82fb4c36cf8") ||
                !verified(File(directory, "full.bin.part"), 66_814_088L, "c0f66830e66bbc75ce45364bfef1317fa7b16813bc427ff6903aceafb757cb80")) throw IOException("完整模型校验失败")
            for (name in expected.keys) {
                val target = File(directory, name)
                if (target.exists() && !target.delete()) throw IOException("无法替换完整模型")
                if (!File(directory, "$name.part").renameTo(target)) throw IOException("无法保存完整模型")
            }
        } finally {
            for (name in expected.keys + listOf("manifest.json", "NOTICE.txt")) File(directory, "$name.part").delete()
        }
    }
    private fun model(): File {
        val app = context ?: throw IOException("完整增强尚未初始化")
        val directory = File(app.filesDir, "mangajanai/full").apply { mkdirs() }
        val onnx = File(directory, "full.onnx")
        val valid = stamp == fileStamp(onnx) || verified(onnx, MODEL_BYTES, SHA256)
        val prefs = app.getSharedPreferences("mangajanai-full-download", Context.MODE_PRIVATE)
        val retryAllowed = System.currentTimeMillis() - prefs.getLong("lastAttempt", 0L) >= 3_600_000L
        if (valid && (verifyNative(directory) || !retryAllowed)) { stamp = fileStamp(onnx); return directory }
        val archive = File(directory, "download.zip.part")
        try {
            report("完整模型首次下载…")
            prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
            try { download(PACK_URL, archive, 90_000_000L); report("模型校验中…"); unpack(archive, directory) }
            catch (error: IOException) {
                cancelled()
                android.util.Log.w("MangaJaNaiFull", "Model pack unavailable; trying original CPU weights", error)
                if (valid) { stamp = fileStamp(onnx); return directory }
                val part = File(directory, "full.onnx.part")
                try {
                    val previous = File(app.filesDir, "mangajanai/2x-1200p-v1.onnx")
                    if (verified(previous, MODEL_BYTES, SHA256)) previous.inputStream().use { input -> part.outputStream().use { input.copyTo(it, 64 * 1024) } }
                    else download(MODEL_URL, part, MODEL_BYTES)
                    if (!verified(part, MODEL_BYTES, SHA256)) throw IOException("完整模型校验失败")
                    if (onnx.exists() && !onnx.delete()) throw IOException("无法替换完整模型")
                    if (!part.renameTo(onnx)) throw IOException("无法保存完整模型")
                } finally { part.delete() }
            }
            stamp = fileStamp(onnx); return directory
        } finally { archive.delete() }
    }
    fun enhance(source: Bitmap, strength: Float): Bitmap {
        lock.lockInterruptibly()
        try {
            MangaJaNai.release() // single shared lock, one resident neural model across both modes
            generation++; idle?.cancel(false); cancelled()
            val started = System.nanoTime()
            val directory = model()
            val engine = runtime ?: MangaJaNaiFullRuntime(checkNotNull(context)).also { runtime = it }
            report("完整引擎准备中…")
            val probeTile = MangaJaNaiFullRuntime.preferredTile()
            val probeSide = probeTile + HALO * 2
            val probePixels = IntArray(probeSide * probeSide)
            readMangaJaNaiTile(source, 0, 0, probePixels, probeTile, HALO)
            val probe = direct(probeSide * probeSide * 3)
            pack(probePixels, probe)
            engine.prepare(directory, probe)
            val tileSize = engine.tile; val side = tileSize + HALO * 2; val area = side * side
            val coreSide = tileSize * 2; val coreArea = coreSide * coreSide
            if (cacheCore != tileSize) { tileCache.clear(); cacheCore = tileSize }
            val input = direct(area * 3); val outputBuffer = direct(area * 12); val floats = outputBuffer.asFloatBuffer()
            val sourceTile = IntArray(area); val coreValues = FloatArray(coreArea * 3); val pixels = IntArray(coreArea)
            val (width, height) = MangaJaNai.outputSize(source.width, source.height)
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val tile = Bitmap.createBitmap(coreSide, coreSide, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(output); val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                val sx = width.toFloat() / source.width; val sy = height.toFloat() / source.height
                val total = ((source.width + tileSize - 1) / tileSize) * ((source.height + tileSize - 1) / tileSize)
                var completed = 0; var inferred = 0
                for (y in 0 until source.height step tileSize) for (x in 0 until source.width step tileSize) {
                    cancelled(); readMangaJaNaiTile(source, x, y, sourceTile, tileSize, HALO)
                    val cached = tileCache.find(sourceTile)
                    val core = if (cached != null) cached else {
                        pack(sourceTile, input); engine.run(directory, input, outputBuffer, side); inferred++
                        for (c in 0..2) for (row in 0 until coreSide) {
                            floats.position(c * area * 4 + (row + HALO * 2) * side * 2 + HALO * 2)
                            floats.get(coreValues, c * coreArea + row * coreSide, coreSide)
                        }
                        tileCache.put(sourceTile, coreValues); coreValues
                    }
                    val tw = min(tileSize, source.width - x) * 2; val th = min(tileSize, source.height - y) * 2
                    for (row in 0 until th) for (col in 0 until tw) {
                        val i = row * coreSide + col
                        val original = sourceTile[(row / 2 + HALO) * side + col / 2 + HALO]
                        fun channel(c: Int): Int {
                            val from = (original shr ((2 - c) * 8)) and 255
                            val value = core[c * coreArea + i]
                            if (!value.isFinite()) throw IOException("完整增强输出无效")
                            val to = value.coerceIn(0f, 1f) * 255f
                            return (from + (to - from) * strength.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
                        }
                        pixels[i] = (original and -0x1000000) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
                    }
                    tile.setPixels(pixels, 0, coreSide, 0, 0, tw, th)
                    canvas.drawBitmap(tile, Rect(0, 0, tw, th), RectF(x * sx, y * sy, (x + tw / 2) * sx, (y + th / 2) * sy), paint)
                    report("完整增强 ${++completed * 100 / total}%")
                }
                report("")
                android.util.Log.i("MangaJaNaiFull", "engine=${engine.name} page=${source.width}x${source.height} core=$tileSize tiles=$total inferred=$inferred totalMs=${(System.nanoTime()-started)/1_000_000}")
                return output
            } catch (error: Throwable) { output.recycle(); throw error }
            finally { tile.recycle() }
        } catch (error: Throwable) {
            runtime?.close(); runtime = null; tileCache.clear(); cacheCore = 0
            report(if (error is InterruptedException) "" else "完整增强失败，点击重试")
            if (error is LinkageError) throw IOException("当前设备无法加载完整增强", error)
            throw error
        } finally {
            try {
                val expected = generation
                idle = cleanup.schedule({ if (lock.tryLock()) {
                    try { if (generation == expected) { runtime?.close(); runtime = null; tileCache.clear(); cacheCore = 0 } }
                    finally { lock.unlock() }
                } }, 60, TimeUnit.SECONDS)
            } finally { lock.unlock() }
        }
    }
    private fun direct(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
    private fun pack(pixels: IntArray, buffer: ByteBuffer) {
        val floats = buffer.asFloatBuffer(); val area = pixels.size
        for (i in pixels.indices) {
            val p = pixels[i]
            floats.put(i, ((p shr 16) and 255) / 255f)
            floats.put(area + i, ((p shr 8) and 255) / 255f)
            floats.put(area * 2 + i, (p and 255) / 255f)
        }
    }
}
