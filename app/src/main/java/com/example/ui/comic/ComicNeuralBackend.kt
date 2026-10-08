package com.example.ui.comic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.nio.FloatBuffer

/** Two tiny bundled graphs, CPU only. JNI failure falls back to the deterministic Kotlin engine. */
internal object ComicNeuralBackend {
    @Volatile private var applicationContext: Context? = null
    @Volatile private var unavailable = false
    @Volatile internal var usedNative = false
        private set
    private val lock = Any()
    private val sessions = mutableMapOf<String, OrtSession>()

    fun configure(context: Context) { applicationContext = context.applicationContext }

    fun run(model: String, rgba: FloatArray, width: Int, height: Int): FloatArray? {
        val context = applicationContext ?: return null
        if (unavailable) return null
        if (Thread.currentThread().isInterrupted) throw InterruptedException("CNN cancelled")
        return synchronized(lock) {
            if (unavailable) return@synchronized null
            try {
                val env = OrtEnvironment.getEnvironment()
                val session = sessions.getOrPut(model) {
                    val bytes = context.assets.open("comic_enhancement/$model-s.onnx").use { it.readBytes() }
                    OrtSession.SessionOptions().use { options ->
                        options.setIntraOpNumThreads(2)
                        options.setInterOpNumThreads(1)
                        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
                        options.setMemoryPatternOptimization(false)
                        options.setCPUArenaAllocator(false)
                        env.createSession(bytes, options)
                    }
                }
                val area = width * height
                val nchw = FloatArray(rgba.size)
                for (i in 0 until area) for (ch in 0..3) nchw[ch * area + i] = rgba[i * 4 + ch]
                OnnxTensor.createTensor(env, FloatBuffer.wrap(nchw), longArrayOf(1, 4, height.toLong(), width.toLong())).use { tensor ->
                    session.run(mapOf("rgba" to tensor)).use { result ->
                        if (Thread.currentThread().isInterrupted) throw InterruptedException("CNN cancelled")
                        val output = result[0] as OnnxTensor
                        output.floatBuffer.get(nchw)
                        val packed = FloatArray(rgba.size)
                        for (i in 0 until area) for (ch in 0..3) packed[i * 4 + ch] = nchw[ch * area + i]
                        usedNative = true
                        packed
                    }
                }
            } catch (error: InterruptedException) {
                throw error
            } catch (error: Exception) {
                disable(error)
                null
            } catch (error: LinkageError) {
                disable(error)
                null
            }
        }
    }

    private fun disable(error: Throwable) {
        unavailable = true
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        Log.w("ComicEnhance", "Native CNN unavailable; using Kotlin reference", error)
    }
}
