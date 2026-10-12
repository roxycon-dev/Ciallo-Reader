package com.example.ui.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.util.EnumSet

/** FP32 execution only. Model, input resolution, 48 px cores and 16 px halos stay unchanged.
 * All calls are protected by MangaJaNai's page lock. At most one native session is alive.
 */
internal class MangaJaNaiRuntime(private val context: Context) : AutoCloseable {
    enum class Backend { CPU, CPU_BALANCED, XNNPACK, NNAPI }
    private var session: OrtSession? = null
    private var backend = Backend.CPU
    private var modelPath = ""
    private val preferences = context.getSharedPreferences("mangajanai-runtime", Context.MODE_PRIVATE)
    private val deviceKey = "fp32-80-v2:${Build.FINGERPRINT}:${MangaJaNai.SHA256}"
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    private val balancedThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 5)

    private fun create(file: File, kind: Backend): OrtSession = OrtSession.SessionOptions().use { options ->
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setIntraOpNumThreads(when (kind) {
            Backend.XNNPACK -> 1
            Backend.CPU_BALANCED -> balancedThreads
            else -> threads
        })
        options.setInterOpNumThreads(1)
        options.setMemoryPatternOptimization(true)
        options.setCPUArenaAllocator(true)
        options.addConfigEntry("session.intra_op.allow_spinning", "0")
        options.addConfigEntry("session.inter_op.allow_spinning", "0")
        // Smaller work chunks can balance big/little cores. Use only if the real probe wins.
        if (kind == Backend.CPU_BALANCED) options.addConfigEntry("session.dynamic_block_base", "4")
        // NNAPI requires known shapes; the original graph and weights are not modified.
        options.setSymbolicDimensionValue("batch_size", 1)
        options.setSymbolicDimensionValue("width", 80)
        options.setSymbolicDimensionValue("height", 80)
        when (kind) {
            Backend.CPU, Backend.CPU_BALANCED -> Unit
            Backend.XNNPACK -> options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
            Backend.NNAPI -> options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
        }
        OrtEnvironment.getEnvironment().createSession(file.absolutePath, options)
    }

    private fun cancelled() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("MangaJaNai cancelled")
    }

    /** Benchmarks real first-tile data once per OS/model; rejects slower or inaccurate drivers.
     * CPU reference closes before a candidate opens, avoiding two copies of the weights.
     */
    fun prepare(file: File, input: OnnxTensor, output: OnnxTensor, buffer: FloatBuffer) {
        if (session != null && modelPath == file.absolutePath) return
        close()
        modelPath = file.absolutePath
        val saved = if (preferences.getString("device", null) == deviceKey)
            preferences.getString("backend", null)?.let { runCatching { Backend.valueOf(it) }.getOrNull() } else null
        if (saved != null) {
            try { session = create(file, saved); backend = saved; return }
            catch (_: OrtException) { preferences.edit().remove("backend").apply() }
        }
        val inputs = mapOf("input" to input)
        val outputs = mapOf("output" to output)
        fun timed(candidate: OrtSession): Long {
            cancelled()
            val start = System.nanoTime()
            candidate.run(inputs, outputs).use { }
            cancelled()
            return System.nanoTime() - start
        }
        val reference = FloatArray(buffer.capacity())
        var best = Backend.CPU
        var bestNs: Long
        create(file, Backend.CPU).use { cpu ->
            timed(cpu) // warmup is excluded from throughput comparison
            bestNs = timed(cpu)
            for (i in reference.indices) reference[i] = buffer.get(i)
        }
        val providers = OrtEnvironment.getAvailableProviders().map { it.name }.toSet()
        val candidates = buildList {
            if (balancedThreads >= 4) add(Backend.CPU_BALANCED)
            if ("XNNPACK" in providers) add(Backend.XNNPACK)
            // CPU_DISABLED is effective from API 29; avoid slow NNAPI reference CPU on older OSes.
            if (Build.VERSION.SDK_INT >= 29 && "NNAPI" in providers) add(Backend.NNAPI)
        }
        try {
            for (candidate in candidates) {
                cancelled()
                var trial: OrtSession? = null
                try {
                    trial = create(file, candidate)
                    timed(trial)
                    val ns = timed(trial)
                    var maxError = 0f
                    var totalError = 0.0
                    for (i in reference.indices) {
                        val error = kotlin.math.abs(buffer.get(i) - reference[i])
                        if (!error.isFinite()) { maxError = Float.POSITIVE_INFINITY; break }
                        maxError = maxOf(maxError, error)
                        totalError += error
                    }
                    val accurate = acceptableError(maxError, totalError / reference.size)
                    Log.i("MangaJaNai", "probe=$candidate tileMs=${ns / 1_000_000} maxError=$maxError accurate=$accurate")
                    if (accurate && ns < bestNs * 0.85) {
                        best = candidate
                        bestNs = ns
                        // Last candidate can remain warm; otherwise close before trying another.
                        if (candidate == candidates.last()) {
                            session = trial
                            trial = null
                        }
                    }
                } catch (error: OrtException) {
                    Log.w("MangaJaNai", "Rejecting $candidate", error)
                } finally { trial?.close() }
            }
            backend = best
            if (session == null) session = create(file, best)
            preferences.edit().putString("device", deviceKey).putString("backend", best.name).apply()
            Log.i("MangaJaNai", "selected=$best threads=${if (best == Backend.CPU_BALANCED) balancedThreads else threads} tileMs=${bestNs / 1_000_000}")
        } catch (error: Throwable) { close(); throw error }
    }

    fun run(file: File, inputs: Map<String, OnnxTensor>, outputs: Map<String, OnnxTensor>) {
        cancelled()
        try { checkNotNull(session).run(inputs, outputs).use { } }
        catch (error: OrtException) {
            if (backend == Backend.CPU) throw error
            // A driver can reject a later run; retry this exact tile using the same FP32 CPU graph.
            close()
            cancelled()
            backend = Backend.CPU
            session = create(file, backend)
            preferences.edit().putString("device", deviceKey).putString("backend", backend.name).apply()
            Log.w("MangaJaNai", "Driver failed; retrying tile on optimized CPU", error)
            checkNotNull(session).run(inputs, outputs).use { }
        }
        cancelled()
    }

    override fun close() { session?.close(); session = null }

    companion object {
        internal fun acceptableError(max: Float, mean: Double): Boolean =
            max.isFinite() && mean.isFinite() && max <= 0.0015f && mean <= 0.0001
    }
}
