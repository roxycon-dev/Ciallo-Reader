package com.example.ui.comic

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.Modifier
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 场景引擎：
 * - [ComicAmbientAudio]：环境音播放（第 21 条）。首选 assets/ambient/ 下的
 *   真实录音素材（全部 CC0，来源与作者见 assets/ambient/CREDITS.md），
 *   MediaPlayer 循环播放（素材已带交叉淡化循环缝，无接缝咔哒）；
 *   资产缺失时回退旧版程序合成（仅兜底，不再是主路径）。
 * - [ComicSceneEffectOverlay]：粒子特效（雨丝/雪花/樱花/萤火/灰尘余烬），Canvas 逐帧动画。
 * 声音与特效完全独立开关，互不绑定。
 */
class ComicAmbientAudio private constructor(
    private val context: android.content.Context,
) {
    private var player: android.media.MediaPlayer? = null
    private var track: android.media.AudioTrack? = null
    @Volatile private var running = false
    @Volatile private var volume = 0.4f
    private var thread: Thread? = null

    companion object {
        /** 场景 → 真实录音资产名（CC0；见 assets/ambient/CREDITS.md） */
        private fun assetOf(scene: ComicScene): String? = when (scene) {
            ComicScene.RAIN -> "scene_rain.ogg"        // Ylmir @ opengameart, Rain (loopable)
            ComicScene.SNOW -> "scene_snow.ogg"        // TinyWorlds, Forest Ambience（雪夜林间）
            ComicScene.SAKURA -> "scene_sakura.ogg"    // isaiah658, Ambient Bird Sounds（春樱鸟鸣）
            ComicScene.FIREFLY -> "scene_firefly.ogg"  // Wolfgang_, Crickets Ambient（夏夜蟋蟀）→ 新反馈7：+夜风层
            ComicScene.OCEAN -> "scene_ocean.ogg"      // RandomMind, Sea and river waves（海浪）
            ComicScene.CAMPFIRE -> "scene_campfire.ogg"// PagDev, Fireplace loop（篝火）
            ComicScene.NIGHT -> "scene_night.ogg"      // Siobhan Leachman, Chorus Cicada（蝉鸣）
            ComicScene.NONE -> null
        }

        fun create(context: android.content.Context): ComicAmbientAudio = ComicAmbientAudio(context.applicationContext)
    }

    val isRunning: Boolean get() = running

    fun start(scene: ComicScene, initialVolume: Float) {
        if (scene == ComicScene.NONE) return
        stop()
        volume = initialVolume.coerceIn(0f, 1f)
        val asset = assetOf(scene)
        // 真实录音主路径：assets → MediaPlayer 循环
        if (asset != null) {
            var opening: android.media.MediaPlayer? = null
            runCatching {
                val mp = android.media.MediaPlayer()
                opening = mp
                context.assets.openFd("ambient/$asset").use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                mp.isLooping = true
                mp.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                mp.setVolume(volume, volume)
                player = mp
                running = true
                mp.setOnPreparedListener { ready ->
                    if (player === ready && running) {
                        ready.setVolume(volume, volume)
                        ready.start()
                    }
                }
                mp.setOnErrorListener { failed, _, _ ->
                    if (player === failed) {
                        runCatching { failed.release() }
                        player = null
                        runCatching { startSynth(scene) }.onFailure { running = false }
                    }
                    true
                }
                mp.prepareAsync()
                return
            }.onFailure { runCatching { opening?.release() }; player = null; running = false }
        }
        // 兜底：资产缺失（异常打包等）时回退程序合成，保证功能不缺失
        runCatching { startSynth(scene) }.onFailure { running = false }
    }

    private fun startSynth(scene: ComicScene) {
        running = true
        val sampleRate = 22050
        val minBuf = android.media.AudioTrack.getMinBufferSize(
            sampleRate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(8192)
        track = android.media.AudioTrack(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            android.media.AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            minBuf, android.media.AudioTrack.MODE_STREAM, android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track?.setVolume(volume)
        track?.play()
        thread = Thread {
            try {
                val synth = Synth.forScene(scene, sampleRate)
                val chunk = ShortArray(4096)
                track?.let { t ->
                    while (running) {
                        synth.fill(chunk)
                        t.write(chunk, 0, chunk.size)
                    }
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true; name = "comic-ambient-audio"; start() }
    }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        runCatching { player?.setVolume(volume, volume) }
        runCatching { track?.setVolume(volume) }
    }

    fun stop() {
        running = false
        player?.run {
            runCatching { stop() }
            runCatching { release() }
        }
        player = null
        // Unblock a streaming write before joining its producer.
        runCatching { track?.pause(); track?.flush() }
        try {
            thread?.join(300)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        thread = null
        try {
            track?.stop()
            track?.release()
        } catch (_: Throwable) {
        }
        track = null
    }

    /** 程序化合成器：逐 chunk 生成 PCM */
    abstract class Synth(protected val sampleRate: Int) {
        abstract fun fill(out: ShortArray)
        protected fun toSample(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

        companion object {
            fun forScene(scene: ComicScene, sampleRate: Int): Synth = when (scene) {
                ComicScene.RAIN -> Rain(sampleRate)
                ComicScene.OCEAN -> Ocean(sampleRate)
                ComicScene.CAMPFIRE -> Campfire(sampleRate)
                ComicScene.NIGHT -> Night(sampleRate)
                else -> Breeze(sampleRate)
            }
        }
    }

    /** 白噪声 + 一阶低通 */
    class Rain(sampleRate: Int) : Synth(sampleRate) {
        private var lp = 0f
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val white = kotlin.random.Random.nextFloat() * 2f - 1f
                lp += 0.18f * (white - lp)
                val v = lp * 1.6f
                out[i] = toSample(v)
            }
        }
    }

    /** 布朗噪声 + 慢速 LFO（海浪涨落） */
    class Ocean(sampleRate: Int) : Synth(sampleRate) {
        private var brown = 0f
        private var phase = 0.0
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val white = kotlin.random.Random.nextFloat() * 2f - 1f
                brown = (brown + 0.02f * white) / 1.02f
                phase += 2.0 * PI * 0.09 / sampleRate
                val wave = (sin(phase) * 0.5 + 0.5).toFloat()
                val v = brown * 5f * (0.25f + 0.75f * wave * wave)
                out[i] = toSample(v)
            }
        }
    }

    /** 低沉火焰 + 随机爆裂噼啪 */
    class Campfire(sampleRate: Int) : Synth(sampleRate) {
        private var lp = 0f
        private var crackleLeft = 0
        private var crackleAmp = 0f
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val white = kotlin.random.Random.nextFloat() * 2f - 1f
                lp += 0.045f * (white - lp)
                if (crackleLeft <= 0 && kotlin.random.Random.nextFloat() < 0.00045f) {
                    crackleLeft = (sampleRate * 0.012f).toInt()
                    crackleAmp = 0.5f + kotlin.random.Random.nextFloat() * 0.5f
                }
                var v = lp * 2.2f
                if (crackleLeft > 0) {
                    val env = crackleLeft.toFloat() / (sampleRate * 0.012f)
                    v += (kotlin.random.Random.nextFloat() * 2f - 1f) * crackleAmp * env * env
                    crackleLeft--
                }
                out[i] = toSample(v * 0.8f)
            }
        }
    }

    /** 夏夜：低频虫鸣和弦 + 断续蝉声 */
    class Night(sampleRate: Int) : Synth(sampleRate) {
        private var chirpPhase = 0.0
        private var chirpOn = true
        private var chirpCount = 0
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                chirpPhase += 2.0 * PI * 4200.0 / sampleRate
                if (chirpPhase > 2.0 * PI * 1e9) chirpPhase -= 2.0 * PI * 1e9
                chirpCount++
                // 0.5s 鸣 + 0.35s 停
                if (chirpCount > sampleRate / 2) { chirpOn = !chirpOn; chirpCount = 0 }
                val amp = if (chirpOn) (0.5 + 0.5 * sin(chirpPhase * 24.0)).toFloat() else 0f
                val crickets = sin(chirpPhase).toFloat() * amp * 0.08f
                val drone = sin(chirpPhase * 0.0021).toFloat() * 0.02f
                out[i] = toSample(crickets + drone)
            }
        }
    }

    /** 微风（雪/樱/萤默认）：极轻的过滤噪声 */
    class Breeze(sampleRate: Int) : Synth(sampleRate) {
        private var lp = 0f
        private var phase = 0.0
        override fun fill(out: ShortArray) {
            for (i in out.indices) {
                val white = kotlin.random.Random.nextFloat() * 2f - 1f
                lp += 0.04f * (white - lp)
                phase += 2.0 * PI * 0.05 / sampleRate
                val swell = 0.6f + 0.4f * sin(phase).toFloat()
                out[i] = toSample(lp * 1.1f * swell)
            }
        }
    }
}
