package com.example.data

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.ContextCompat
import java.util.Locale
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TtsManager(context: Context) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = app.getSystemService(AudioManager::class.java)
    private val storage = app.getSharedPreferences("reader_listening", Context.MODE_PRIVATE)
    private val serviceIntent get() = Intent(app, TtsPlaybackService::class.java)
    private val _state = MutableStateFlow(ListeningState())
    val state = _state.asStateFlow()
    private var tts: TextToSpeech? = null
    private var initializing = false
    private var released = false
    private var foreground = false
    private var session = 0L
    private var utterance = 0L
    private var chunkStart = 0
    private var chunkEnd = 0
    private var resumeOffset = 0
    private var resumeTime = 0
    private var seekFromEnd = false
    private var media: MediaPlayer? = null
    private var mediaReady = false
    private var nextMedia: PreparedClip? = null
    private var linkedPlayer: MediaPlayer? = null
    private var upcomingClip: SpeechClip? = null

    private class PreparedClip(val clip: SpeechClip, val player: MediaPlayer, var ready: Boolean = false)
    private var buildJob: Job? = null
    private var pendingAudio: SpeechBuild? = null
    private var wantedClip: SpeechClip? = null

    private data class SpeechBuild(val id: String, val clip: SpeechClip, val file: File?)
    private val network = NetworkSpeech()
    private val audioCache = LinkedHashMap<SpeechClip, File>(24, .75f, true)
    private val audioFolder by lazy { File(app.cacheDir, "reader-speech").apply { mkdirs() } }

    private data class SpeechClip(
        val text: String, val locale: Locale, val source: SpeechSource, val voice: String,
        val pace: Float, val pitch: Float,
    )
    internal var source: SpeechSource
        get() = runCatching { SpeechSource.valueOf(storage.getString("source", "System").orEmpty()) }
            .getOrDefault(SpeechSource.System)
        private set(value) { storage.edit().putString("source", value.name).apply() }
    internal var edgeVoice: String
        get() = storage.getString("edgeVoice", "").orEmpty()
        private set(value) { storage.edit().putString("edgeVoice", value).apply() }
    internal var customService: CustomVoiceService
        get() = CustomVoiceService(storage.getString("customUrl", "").orEmpty(),
            storage.getString("customToken", "").orEmpty(), storage.getString("customModel", "tts-1").orEmpty(),
            storage.getString("customVoice", "alloy").orEmpty())
        private set(value) {
            storage.edit().putString("customUrl", value.url).putString("customToken", value.token)
                .putString("customModel", value.model).putString("customVoice", value.voice).apply()
        }
    private var edgeVoices = emptyList<EdgeVoice>()

    private var gap: Runnable? = null
    private var timerTask: Runnable? = null
    private var rate = 1f
    private var pitch = 1f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var nextChapter: (suspend (ListeningChapter) -> ListeningChapter?)? = null
    private var nextChapterJob: Job? = null
    private var prefetchedChapter: Deferred<Result<ListeningChapter?>>? = null
    private var waitingForChapter = false
    private val chapterTimeout = Runnable { finishBook() }

    var voiceName: String
        get() = storage.getString("voice", "").orEmpty()
        private set(value) { storage.edit().putString("voice", value).apply() }
    var followText: Boolean
        get() = storage.getBoolean("follow", true)
        set(value) { storage.edit().putBoolean("follow", value).apply() }
    var gapMillis: Int
        get() = storage.getInt("gap", 0)
        set(value) {
            val interval = value.coerceIn(0, 2000)
            storage.edit().putInt("gap", interval).apply()
            gap?.let { scheduled ->
                handler.removeCallbacks(scheduled)
                if (interval == 0) scheduled.run()
                else handler.postDelayed(scheduled, interval.toLong())
            }
            linkNextPlayer()
        }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0) handler.post { pause() }
    }
    private val focusRequest by lazy {
        if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes).setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, handler).build() else null
    }

    fun prepare() {
        if (source != SpeechSource.System || _state.value.engineReady || initializing || released) return
        initializing = true
        update { copy(error = null) }
        voiceInventory = null
        tts?.shutdown()
        try {
            tts = TextToSpeech(app) { status -> handler.post { initialized(status) } }
        } catch (failure: Exception) {
            initializing = false
            fail("朗读引擎初始化失败")
            StartupDiagnostics.record(app, "tts-initialization", failure)
        }
    }

    private fun initialized(status: Int) {
        initializing = false
        if (released) return
        val engine = tts
        if (status != TextToSpeech.SUCCESS || engine == null) {
            if (source == SpeechSource.System) fail("朗读引擎初始化失败")
            return
        }
        try {
            engine.setAudioAttributes(attributes)
            installListener(engine)
        } catch (failure: Exception) {
            if (source == SpeechSource.System) fail("朗读引擎初始化失败")
            StartupDiagnostics.record(app, "tts-initialization", failure)
            return
        }
        update { copy(engineReady = true) }
        startWhenReady()
    }

    private fun installListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                handler.post {
                    val build = pendingAudio?.takeIf { it.id == id } ?: return@post
                    completeBuild(build, build.file)
                }
            }

            @Deprecated("Android TTS callback")
            override fun onError(id: String?) {
                handler.post {
                    val build = pendingAudio?.takeIf { it.id == id } ?: return@post
                    failBuild(build, "朗读失败，请检查系统语音引擎")
                }
            }
        })
    }

    fun savedParagraph(chapter: ListeningChapter): Int? =
        storage.getInt(cursorKey(chapter), -1).takeIf { it in chapter.paragraphs.indices }

    fun play(chapter: ListeningChapter, from: Int?, speed: Float, voicePitch: Float,
        continuation: (suspend (ListeningChapter) -> ListeningChapter?)? = null
    ) {
        if (chapter.paragraphs.isEmpty() || released) return
        val existing = _state.value.chapter
        val sameChapter = existing?.bookKey == chapter.bookKey && existing.chapterKey == chapter.chapterKey
        val canReuseForeground = foreground && _state.value.playing
        if (!sameChapter || continuation != null) nextChapter = continuation
        cancelSpeech()
        waitingForChapter = false
        rate = speed.validSpeechValue()
        pitch = voicePitch.validSpeechValue()
        if (!sameChapter || from != null || _state.value.finished) {
            resumeOffset = 0
            resumeTime = 0
            seekFromEnd = false
        }
        val paragraph = (from ?: if (sameChapter && !_state.value.finished) _state.value.paragraph else savedParagraph(chapter) ?: 0)
            .coerceIn(chapter.paragraphs.indices)
        update { copy(chapter = chapter, paragraph = paragraph, playing = canReuseForeground,
            preparing = !canReuseForeground, error = null, finished = false) }
        rememberCursor()
        if (canReuseForeground) {
            speak()
            return
        }
        prepare()
        if (!_state.value.preparing) return
        session = ++nextSession
        TtsPlaybackService.manager = this
        try {
            ContextCompat.startForegroundService(app, serviceIntent.putExtra("session", session))
        } catch (_: Exception) {
            fail("无法启动后台朗读服务")
        }
    }

    internal fun serviceStarted(id: Long): Boolean {
        if (!ownsService(id)) return false
        foreground = true
        startWhenReady()
        return ownsService(id)
    }

    private fun startWhenReady() {
        if (!foreground || (source == SpeechSource.System && !_state.value.engineReady) || !_state.value.preparing) return
        if (!requestFocus()) {
            fail("音频暂不可用，请重试")
            return
        }
        update { copy(playing = true, preparing = false) }
        speak()
    }

    internal fun ownsService(id: Long): Boolean =
        id == session && (_state.value.playing || _state.value.preparing)

    internal fun serviceEnded(id: Long) {
        if (ownsService(id)) pause(stopService = false)
    }

    private fun currentParagraph() = _state.value.chapter?.paragraphs?.getOrNull(_state.value.paragraph)

    private fun clipFor(text: String, start: Int, end: Int): SpeechClip {
        val selectedSource = source
        val preferred = when (selectedSource) {
            SpeechSource.System -> voiceName
            SpeechSource.Custom -> customService.voice
            SpeechSource.Edge -> edgeVoice
        }
        val content = text.substring(start, end)
        return SpeechClip(content, speechLocale(content), selectedSource, preferred, rate, pitch)
    }

    private fun speak() {
        val paragraph = currentParagraph()?.text ?: return
        chunkStart = resumeOffset.coerceIn(0, paragraph.lastIndex)
        chunkEnd = speechChunkEnd(paragraph, chunkStart, clipLimit)
        val clip = clipFor(paragraph, chunkStart, chunkEnd)
        wantedClip = clip
        upcomingClip = null
        if (pendingAudio?.clip != clip) cancelBuild()
        if (nextMedia?.clip != clip) closeNextMedia()
        val existing = audioCache[clip]
        if (existing?.isFile == true) playClip(clip, existing)
        else {
            closeMedia()
            update { copy(buffering = "正在准备语音") }
            if (pendingAudio == null) buildClip(clip)
        }
    }

    private fun prefetchNextClip() {
        val snapshot = _state.value
        val current = currentParagraph()?.text ?: return
        val sameParagraph = chunkEnd < current.length
        val following = snapshot.chapter?.paragraphs?.getOrNull(snapshot.paragraph + 1)?.text
        val text = if (sameParagraph) current else following
        if (text == null) {
            upcomingClip = null
            prefetchChapter()
            return
        }
        prefetchClip(text, if (sameParagraph) chunkEnd else 0)
    }

    private fun prefetchClip(text: String, start: Int) {
        val next = clipFor(text, start, speechChunkEnd(text, start, clipLimit))
        upcomingClip = next
        val cached = audioCache[next]
        if (cached?.isFile == true) prepareNextPlayer(next, cached)
        else if (pendingAudio == null) buildClip(next)
    }

    private fun prefetchChapter() {
        val chapter = _state.value.chapter ?: return
        val loader = nextChapter ?: return
        if (_state.value.timer == -1 || prefetchedChapter != null) return
        prefetchedChapter = scope.async {
            val result = loadNextChapter(chapter, loader)
            val first = result.getOrNull()?.paragraphs?.firstOrNull()?.text
            if (first != null && _state.value.chapter === chapter && _state.value.playing &&
                _state.value.timer != -1 && !waitingForChapter && mediaReady) {
                prefetchClip(first, 0)
            }
            result
        }
    }

    private suspend fun loadNextChapter(
        chapter: ListeningChapter,
        loader: suspend (ListeningChapter) -> ListeningChapter?,
    ): Result<ListeningChapter?> = try {
        Result.success(withTimeout(30_000L) { loader(chapter) })
    } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
        Result.failure(timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    private fun buildClip(clip: SpeechClip) {
        val id = (++utterance).toString()
        if (clip.source == SpeechSource.System) {
            val engine = tts
            if (engine == null || !_state.value.engineReady) {
                prepare()
                return
            }
            try {
                val voice = clip.voice.takeIf(String::isNotEmpty)?.let { name ->
                    availableVoices().firstOrNull { it.name == name }
                }
                if (voice != null) engine.voice = voice
                else if (engine.setLanguage(clip.locale) < 0) {
                    if (wantedClip == clip) fail("请先安装系统朗读语音")
                    return
                }
                engine.setSpeechRate(clip.pace)
                engine.setPitch(clip.pitch)
                val file = File.createTempFile("clip-", ".wav", audioFolder)
                val build = SpeechBuild(id, clip, file)
                pendingAudio = build
                if (engine.synthesizeToFile(clip.text, null, file, id) != TextToSpeech.SUCCESS) {
                    failBuild(build, "朗读请求失败")
                }
            } catch (failure: Exception) {
                pendingAudio?.file?.delete()
                pendingAudio = null
                if (wantedClip == clip) fail("朗读失败，请检查系统语音引擎")
                StartupDiagnostics.record(app, "tts-speak", failure)
            }
        } else {
            val custom = customService
            val voice = edgeVoices.firstOrNull { it.name == clip.voice }
                ?: clip.voice.takeIf(String::isNotBlank)?.let { EdgeVoice(it, it.substringBeforeLast('-'), it) }
                ?: automaticEdgeVoice(clip.locale)
            val build = SpeechBuild(id, clip, null)
            pendingAudio = build
            buildJob = scope.launch {
                var file: File? = null
                try {
                    val bytes = withTimeout(32_000L) {
                        if (clip.source == SpeechSource.Edge) network.edge(clip.text, voice, clip.pace, clip.pitch)
                        else network.custom(clip.text, custom, clip.pace)
                    }
                    val output = File.createTempFile("clip-", ".mp3", audioFolder)
                    file = output
                    withContext(Dispatchers.IO) { output.writeBytes(bytes) }
                    if (pendingAudio === build) {
                        file = null
                        completeBuild(build, output)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    failBuild(build, "在线语音连接超时")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    failBuild(build, if (clip.source == SpeechSource.Edge) "Edge 语音连接失败" else "自定义语音请求失败")
                } finally {
                    file?.delete()
                }
            }
        }
    }

    private fun completeBuild(build: SpeechBuild, file: File?) {
        if (pendingAudio !== build) return
        if (file == null || file.length() <= 0L) {
            file?.delete()
            failBuild(build, "朗读请求失败")
            return
        }
        pendingAudio = null
        buildJob = null
        cacheClip(build.clip, file)
        if (wantedClip == build.clip) playClip(build.clip, file)
        else if (upcomingClip == build.clip) prepareNextPlayer(build.clip, file)
    }

    private fun failBuild(build: SpeechBuild, message: String) {
        if (pendingAudio !== build) return
        pendingAudio = null
        buildJob = null
        build.file?.delete()
        if (wantedClip == build.clip) fail(message)
    }

    private val clipLimit: Int get() = minOf(TextToSpeech.getMaxSpeechInputLength(), 480)

    private fun cacheClip(key: SpeechClip, file: File) {
        audioCache.put(key, file)?.takeUnless { it == file }?.delete()
        while (audioCache.size > 24 || audioCache.values.sumOf { it.length() } > 32L * 1024 * 1024) {
            val first = audioCache.entries.first()
            if (first.value == file) break
            audioCache.remove(first.key)?.delete()
        }
    }

    private fun makePlayer(clip: SpeechClip, file: File): PreparedClip? {
        val player = MediaPlayer()
        val prepared = PreparedClip(clip, player)
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(file.path)
            player.setOnPreparedListener {
                prepared.ready = true
                when (it) {
                    media -> startPreparedPlayer(it)
                    nextMedia?.player -> linkNextPlayer()
                }
            }
            player.setOnCompletionListener { finished ->
                if (media !== finished || !_state.value.playing) return@setOnCompletionListener
                wantedClip = null
                if (chunkEnd < (currentParagraph()?.text?.length ?: 0)) {
                    resumeOffset = chunkEnd
                    resumeTime = 0
                    speak()
                } else advance()
            }
            player.setOnErrorListener { failed, _, _ ->
                if (media === failed) fail("无法播放语音音频")
                else if (nextMedia?.player === failed) {
                    closeNextMedia()
                    audioCache.remove(clip)?.delete()
                }
                true
            }
            player.prepareAsync()
            return prepared
        } catch (_: Exception) {
            player.release()
            return null
        }
    }

    private fun playClip(clip: SpeechClip, file: File) {
        val matched = nextMedia?.takeIf { it.clip == clip }
        val autoStarted = matched != null && linkedPlayer === matched.player
        if (matched != null) nextMedia = null
        closeMedia()
        val prepared = matched ?: makePlayer(clip, file)
        if (prepared == null) {
            audioCache.remove(clip)?.delete()
            fail("无法播放语音音频")
            return
        }
        media = prepared.player
        if (autoStarted) {
            mediaReady = true
            update { copy(buffering = null) }
            prefetchNextClip()
        } else if (prepared.ready) {
            startPreparedPlayer(prepared.player)
        } else {
            update { copy(buffering = "正在准备语音") }
        }
    }

    private fun startPreparedPlayer(player: MediaPlayer) {
        mediaReady = true
        val position = if (seekFromEnd) player.duration + resumeTime else resumeTime
        seekFromEnd = false
        seekWithinChapter(position, playOnSeek = true)
    }

    private fun prepareNextPlayer(clip: SpeechClip, file: File) {
        if (nextMedia?.clip == clip) return
        closeNextMedia()
        nextMedia = makePlayer(clip, file)
    }

    private fun linkNextPlayer() {
        val current = media ?: return
        if (!mediaReady) return
        val snapshot = _state.value
        val withinParagraph = chunkEnd < (currentParagraph()?.text?.length ?: 0)
        val withinChapter = snapshot.paragraph < (snapshot.chapter?.paragraphs?.lastIndex ?: -1)
        val next = nextMedia?.takeIf { it.ready && (withinParagraph || (withinChapter && gapMillis == 0)) }?.player
        if (linkedPlayer === next) return
        runCatching { current.setNextMediaPlayer(next) }.onSuccess { linkedPlayer = next }
    }

    private fun closeNextMedia() {
        val next = nextMedia ?: return
        nextMedia = null
        if (linkedPlayer === next.player) {
            runCatching { media?.setNextMediaPlayer(null) }
            linkedPlayer = null
        }
        next.player.release()
    }

    fun skipSeconds(seconds: Int) {
        if (_state.value.chapter == null || _state.value.finished || waitingForChapter) return
        val player = media
        if (linkedPlayer != null) {
            runCatching { player?.setNextMediaPlayer(null) }
            linkedPlayer = null
        }
        val millis = seconds.coerceIn(-60, 60) * 1000
        if (player == null || !mediaReady) {
            resumeTime = (resumeTime + millis).coerceIn(-120_000, 120_000)
        } else {
            seekWithinChapter(player.currentPosition + millis, playOnSeek = false)
            linkNextPlayer()
        }
    }

    private fun seekWithinChapter(position: Int, playOnSeek: Boolean) {
        val player = media ?: return
        val chapter = _state.value.chapter ?: return
        val paragraph = _state.value.paragraph
        val length = currentParagraph()?.text?.length ?: 0
        val duration = player.duration.coerceAtLeast(1)
        val hasNext = chunkEnd < length || paragraph < chapter.paragraphs.lastIndex
        val hasPrevious = chunkStart > 0 || paragraph > 0
        if (position >= duration && hasNext) {
            val sameParagraph = chunkEnd < length
            moveClip(if (sameParagraph) paragraph else paragraph + 1,
                if (sameParagraph) chunkEnd else 0, position - duration, false)
        } else if (position < 0 && hasPrevious) {
            val target = if (chunkStart == 0) paragraph - 1 else paragraph
            val text = chapter.paragraphs[target].text
            val limit = if (target == paragraph) chunkStart else text.length
            var start = 0
            var end = speechChunkEnd(text, start, clipLimit)
            while (end < limit) {
                start = end
                end = speechChunkEnd(text, start, clipLimit)
            }
            moveClip(target, start, position, true)
        } else {
            val safe = position.coerceIn(0, duration - 1)
            resumeTime = safe
            if (safe > 0 || !playOnSeek) {
                if (Build.VERSION.SDK_INT >= 26) player.seekTo(safe.toLong(), MediaPlayer.SEEK_CLOSEST)
                else @Suppress("DEPRECATION") player.seekTo(safe)
            }
            if (playOnSeek && _state.value.playing) {
                player.start()
                update { copy(buffering = null) }
                prefetchNextClip()
            }
        }
    }

    private fun moveClip(paragraph: Int, offset: Int, position: Int, fromEnd: Boolean) {
        closeMedia()
        resumeOffset = offset
        resumeTime = position
        seekFromEnd = fromEnd
        wantedClip = null
        if (_state.value.paragraph != paragraph) {
            update { copy(paragraph = paragraph) }
            rememberCursor()
        }
        speak()
    }

    private fun closeMedia() {
        val player = media
        media = null
        mediaReady = false
        linkedPlayer = null
        player?.release()
    }

    internal fun selectSource(value: SpeechSource) {
        if (source == value) return
        pause()
        source = value
        resumeTime = 0
        seekFromEnd = false
        update { copy(error = null) }
        prepare()
    }

    internal fun selectEdgeVoice(value: String) {
        edgeVoice = value
        restartSpeech()
    }

    internal suspend fun availableEdgeVoices(): List<EdgeVoice> {
        if (edgeVoices.isEmpty()) edgeVoices = network.voices()
        return edgeVoices
    }

    internal fun configureCustomVoice(value: CustomVoiceService) {
        require(value.valid())
        pause()
        customService = value
        audioCache.values.forEach(File::delete)
        audioCache.clear()
        update { copy(error = null) }
    }

    private var voiceInventory: List<Voice>? = null
    fun availableVoices(refresh: Boolean = false): List<Voice> {
        if (refresh) voiceInventory = null
        if (!_state.value.engineReady) return emptyList()
        return voiceInventory ?: runCatching {
            tts?.voices.orEmpty().sortedWith(compareBy({ it.locale.displayName }, { it.isNetworkConnectionRequired }, { it.name }))
        }.getOrDefault(emptyList()).also { voiceInventory = it }
    }

    fun selectVoice(name: String) {
        voiceName = name
        restartSpeech()
    }

    fun setSpeech(speed: Float, voicePitch: Float) {
        rate = speed.validSpeechValue()
        pitch = voicePitch.validSpeechValue()
        restartSpeech()
    }

    private fun restartSpeech() {
        if (!_state.value.playing || waitingForChapter) return
        resumeTime = media?.takeIf { mediaReady }?.currentPosition ?: resumeTime
        cancelSpeech()
        speak()
    }

    private fun advance() {
        val snapshot = _state.value
        val chapter = snapshot.chapter ?: return
        resumeOffset = 0
        resumeTime = 0
        seekFromEnd = false
        if (snapshot.paragraph == chapter.paragraphs.lastIndex) {
            closeMedia()
            storage.edit().remove(cursorKey(chapter)).apply()
            update { copy(finished = true) }
            if (snapshot.timer == -1) {
                setTimer(0)
                pause()
            } else {
                waitingForChapter = true
                val loader = nextChapter
                if (loader == null) {
                    handler.postDelayed(chapterTimeout, 15_000L)
                    update { copy(completion = completion + 1) }
                } else {
                    update { copy(buffering = "正在加载下一章") }
                    val preload = prefetchedChapter
                    nextChapterJob = scope.launch {
                        val result = preload?.await()?.takeIf { it.isSuccess } ?: loadNextChapter(chapter, loader)
                        if (!awaitingChapter(chapter)) return@launch
                        prefetchedChapter = null
                        nextChapterJob = null
                        if (result.isFailure) {
                            fail("下一章加载失败")
                        } else {
                            val next = result.getOrNull()
                            if (next == null || next.paragraphs.isEmpty()) finishBook()
                            else {
                                waitingForChapter = false
                                update { copy(chapter = next, paragraph = 0, finished = false) }
                                rememberCursor()
                                speak()
                            }
                        }
                    }
                }
            }
            return
        }
        update { copy(paragraph = paragraph + 1) }
        rememberCursor()
        if (gapMillis == 0) speak()
        else {
            closeMedia()
            gap = Runnable { gap = null; if (_state.value.playing) speak() }
                .also { handler.postDelayed(it, gapMillis.toLong()) }
        }
    }

    internal fun awaitingChapter(chapter: ListeningChapter) =
        waitingForChapter && _state.value.chapter === chapter

    internal fun continueWith(chapter: ListeningChapter, speed: Float, pitch: Float) {
        if (!waitingForChapter || _state.value.chapter?.bookKey != chapter.bookKey) return
        if (chapter.paragraphs.isEmpty()) finishBook() else play(chapter, 0, speed, pitch)
    }

    fun finishBook() {
        if (!waitingForChapter) return
        waitingForChapter = false
        setTimer(0)
        pause()
    }

    fun moveParagraph(delta: Int) = seek(_state.value.paragraph + delta)

    fun seek(paragraph: Int) {
        val snapshot = _state.value
        val target = snapshot.chapter?.paragraphs?.indices ?: return
        if (paragraph !in target) return
        cancelSpeech()
        waitingForChapter = false
        resumeOffset = 0
        resumeTime = 0
        seekFromEnd = false
        update { copy(paragraph = paragraph, error = null, finished = false) }
        rememberCursor()
        if (snapshot.playing) speak()
    }

    // 0 = off, -1 = end of chapter, positive = minutes.
    fun setTimer(minutes: Int) {
        timerTask?.let(handler::removeCallbacks)
        timerTask = null
        update { copy(timer = minutes) }
        if (minutes == -1) {
            if (waitingForChapter) {
                finishBook()
                return
            }
            prefetchedChapter?.cancel()
            prefetchedChapter = null
        } else if (mediaReady && chunkEnd >= (currentParagraph()?.text?.length ?: Int.MAX_VALUE) &&
            _state.value.paragraph == _state.value.chapter?.paragraphs?.lastIndex) {
            prefetchChapter()
        }
        if (minutes > 0) timerTask = Runnable {
            setTimer(0)
            pause()
        }.also { handler.postDelayed(it, minutes * 60_000L) }
    }

    fun pause() = pause(stopService = true)

    private fun pause(stopService: Boolean) {
        waitingForChapter = false
        foreground = false
        if (mediaReady) resumeTime = media?.currentPosition ?: resumeTime
        update { copy(playing = false, preparing = false, buffering = null) }
        cancelSpeech()
        abandonFocus()
        if (stopService) app.stopService(serviceIntent)
    }

    fun stop() {
        pause()
        setTimer(0)
        nextChapter = null
        resumeOffset = 0
        resumeTime = 0
        seekFromEnd = false
        update { copy(chapter = null, paragraph = 0, error = null, finished = false) }
    }

    fun release() {
        released = true
        stop()
        scope.cancel()
        audioCache.values.forEach(File::delete)
        audioCache.clear()
        tts?.shutdown()
        tts = null
        if (TtsPlaybackService.manager === this) TtsPlaybackService.manager = null
    }

    private fun fail(message: String) {
        pause()
        update { copy(error = message) }
    }

    private fun cancelSpeech() {
        utterance++
        closeNextMedia()
        closeMedia()
        wantedClip = null
        upcomingClip = null
        cancelBuild()
        handler.removeCallbacks(chapterTimeout)
        nextChapterJob?.cancel()
        nextChapterJob = null
        prefetchedChapter?.cancel()
        prefetchedChapter = null
        gap?.let(handler::removeCallbacks)
        gap = null
    }

    private fun cancelBuild() {
        val build = pendingAudio
        pendingAudio = null
        buildJob?.cancel()
        buildJob = null
        if (build?.file != null) {
            tts?.stop()
            build.file.delete()
        }
    }

    private fun rememberCursor() {
        val snapshot = _state.value
        snapshot.chapter?.let { storage.edit().putInt(cursorKey(it), snapshot.paragraph).apply() }
    }

    private fun cursorKey(chapter: ListeningChapter) = "cursor:${chapter.bookKey}:${chapter.chapterKey}"

    private inline fun update(transform: ListeningState.() -> ListeningState) {
        _state.value = transform(_state.value)
    }

    @Suppress("DEPRECATION")
    private fun requestFocus(): Boolean =
        (if (Build.VERSION.SDK_INT >= 26) audio.requestAudioFocus(requireNotNull(focusRequest))
        else audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    @Suppress("DEPRECATION")
    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) audio.abandonAudioFocusRequest(requireNotNull(focusRequest))
        else audio.abandonAudioFocus(focusListener)
    }

    private fun Float.validSpeechValue() = if (isFinite()) coerceIn(.25f, 4f) else 1f

    private companion object {
        var nextSession = 0L
    }
}

internal fun speechChunkEnd(text: String, start: Int, limit: Int): Int {
    val hardEnd = (start + limit.coerceAtLeast(2)).coerceAtMost(text.length)
    if (hardEnd == text.length) return hardEnd
    val lowerBound = start + (hardEnd - start) / 2
    for (i in hardEnd - 1 downTo lowerBound) {
        if (text[i] in "。！？!?；;，,、. ") return i + 1
    }
    return if (text[hardEnd - 1].isHighSurrogate() && text[hardEnd].isLowSurrogate()) hardEnd - 1 else hardEnd
}

private fun speechLocale(text: String): Locale {
    var han = false
    for (char in text) when (char) {
        in '\u3040'..'\u30ff' -> return Locale.JAPANESE
        in '\uac00'..'\ud7af' -> return Locale.KOREAN
        in '\u3400'..'\u9fff' -> han = true
    }
    return if (han) Locale.CHINESE else if (text.any { it in 'a'..'z' || it in 'A'..'Z' }) Locale.ENGLISH
        else Locale.getDefault()
}
