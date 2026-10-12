package com.example.ui.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One heavy engine at a time. GPU must pass a real FP32 CPU accuracy/speed probe. */
internal class MangaJaNaiFullRuntime(private val context: Context) : AutoCloseable {
    private var gpu = 0L
    private var cpu: OrtSession? = null
    private var cpuSide = 0
    private val prefs = context.getSharedPreferences("mangajanai-full-runtime", Context.MODE_PRIVATE)
    private val key = "ncnn-20260526-f32-v1:${Build.FINGERPRINT}:${MangaJaNaiFull.SHA256}"
    var tile = 48
        private set
    val name get() = if (gpu != 0L) "Vulkan FP32" else "CPU FP32"

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
    }
    private fun createCpu(file: File, side: Int): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(true)
        options.setCPUArenaAllocator(true)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        options.addConfigEntry("session.inter_op.allow_spinning", "0")
        options.addConfigEntry("session.dynamic_block_base", "4")
        options.setSymbolicDimensionValue("batch_size", 1)
        options.setSymbolicDimensionValue("width", side.toLong())
        options.setSymbolicDimensionValue("height", side.toLong())
        OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
    }
    private fun runCpu(file: File, input: ByteBuffer, output: ByteBuffer, side: Int) {
        if (cpu == null || cpuSide != side) {
            cpu?.close(); cpu = null
            cpu = createCpu(file, side); cpuSide = side
        }
        val env = OrtEnvironment.getEnvironment()
        input.position(0); output.position(0)
        OnnxTensor.createTensor(env, input.asFloatBuffer(), longArrayOf(1, 3, side.toLong(), side.toLong())).use { x ->
            OnnxTensor.createTensor(env, output.asFloatBuffer(), longArrayOf(1, 3, side * 2L, side * 2L)).use { y ->
                val options = OrtSession.RunOptions()
                // A cancelled background job must not hold up the current page for a large CPU tile.
                val worker = Thread.currentThread()
                var open = true
                val timer = watchdog.scheduleAtFixedRate({ synchronized(options) {
                    if (open && worker.isInterrupted) runCatching { options.setTerminate(true) }
                } }, 25, 25, java.util.concurrent.TimeUnit.MILLISECONDS)
                try { checkNotNull(cpu).run(mapOf("input" to x), emptySet(), mapOf("output" to y), options).use { } }
                finally {
                    synchronized(options) { open = false; timer.cancel(false); options.close() }
                }
            }
        }
    }
    fun prepare(directory: File, actualInput: ByteBuffer) {
        if (gpu != 0L || cpu != null) return
        val model = File(directory, "full.onnx")
        val saved = if (prefs.getString("device", null) == key) prefs.getBoolean("gpu", false) else null
        if (saved == false || !MangaJaNaiFull.verifyNative(directory)) {
            cpu = createCpu(model, 80); cpuSide = 80; return
        }
        val cache = File(context.cacheDir, "mangajanai-full-${Build.VERSION.SDK_INT}.vkcache")
        fun openGpu() { gpu = MangaJaNaiGpu.create(File(directory, "full.param").path, File(directory, "full.bin").path, cache.path) }
        if (saved == true) {
            try { openGpu(); chooseTile(); return }
            catch (error: Throwable) {
                if (error is InterruptedException) throw error
                Log.w("MangaJaNaiFull", "Cached GPU unavailable", error); close()
            }
        }
        val side = preferredTile() + 32
        val inputs = listOf(actualInput, probeInput(side))
        val output = direct(side * side * 12)
        val reference = ArrayList<FloatArray>(2)
        var cpuNs = 0L
        try {
            for (input in inputs) {
                cancelled()
                runCpu(model, input, output, side)
                val start = System.nanoTime(); runCpu(model, input, output, side); cpuNs += System.nanoTime() - start
                reference.add(FloatArray(output.capacity() / 4).also { output.asFloatBuffer().get(it) })
            }
            cpu?.close(); cpu = null // release CPU weights before creating the GPU model
            cancelled(); openGpu()
            var gpuNs = 0L
            var maxError = 0f; var totalError = 0.0
            for ((index, input) in inputs.withIndex()) {
                cancelled(); MangaJaNaiGpu.run(gpu, input, output, side)
                val start = System.nanoTime(); MangaJaNaiGpu.run(gpu, input, output, side); gpuNs += System.nanoTime() - start
                val values = output.asFloatBuffer()
                for (i in reference[index].indices) {
                    val error = kotlin.math.abs(values.get(i) - reference[index][i])
                    maxError = if (error.isFinite()) maxOf(maxError, error) else Float.POSITIVE_INFINITY
                    totalError += error
                }
            }
            val accurate = acceptableError(maxError, totalError / (reference.size * reference[0].size))
            val faster = gpuNs < cpuNs * 0.85
            Log.i("MangaJaNaiFull", "GPU probe cpuMs=${cpuNs/1_000_000} gpuMs=${gpuNs/1_000_000} max=$maxError accurate=$accurate")
            if (!accurate || !faster) close() else { MangaJaNaiGpu.save(gpu); chooseTile() }
            prefs.edit().putString("device", key).putBoolean("gpu", gpu != 0L).apply()
        } catch (error: Throwable) {
            close()
            if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
            if (error is OutOfMemoryError) throw error
            Log.w("MangaJaNaiFull", "GPU unavailable; using original FP32 CPU model", error)
            prefs.edit().putString("device", key).putBoolean("gpu", false).apply()
        }
        if (gpu == 0L) { cpu = createCpu(model, 80); cpuSide = 80 }
    }
    private fun chooseTile() {
        // Larger original-resolution tiles reduce halo overlap/dispatches; memory stays bounded.
        tile = preferredTile()
    }
    fun run(directory: File, input: ByteBuffer, output: ByteBuffer, side: Int) {
        cancelled()
        if (gpu != 0L) {
            try { MangaJaNaiGpu.run(gpu, input, output, side) }
            catch (error: java.io.IOException) {
                close(); prefs.edit().putString("device", key).putBoolean("gpu", false).apply()
                Log.w("MangaJaNaiFull", "GPU failed; retrying exact tile on CPU", error)
                cancelled(); runCpu(File(directory, "full.onnx"), input, output, side)
            }
        } else runCpu(File(directory, "full.onnx"), input, output, side)
        cancelled()
    }
    override fun close() {
        cpu?.close(); cpu = null; cpuSide = 0
        if (gpu != 0L) { val previous = gpu; gpu = 0L; MangaJaNaiGpu.destroy(previous) }
        tile = 48
    }
    companion object {
        private val watchdog = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "mangajanai-full-cancel").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        internal fun acceptableError(max: Float, mean: Double): Boolean =
            max.isFinite() && mean.isFinite() && max <= 0.0015f && mean <= 0.0001
        internal fun preferredTile(): Int = if (Runtime.getRuntime().maxMemory() >= 384L * 1024 * 1024) 240 else 128
        private fun direct(floats: Int) = ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder())
        private fun probeInput(side: Int): ByteBuffer = direct(side * side * 3).apply {
            val values = asFloatBuffer()
            for (c in 0..2) for (y in 0 until side) for (x in 0 until side)
                values.put(((x * 13 + y * 17 + c * 31) % 256) / 255f)
        }
    }
}
