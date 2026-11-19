package com.example.ui

import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.CustomVoiceService
import com.example.data.ListeningChapter
import com.example.data.NetworkSpeech
import com.example.data.SpeechSource
import com.example.data.TtsManager
import com.example.data.automaticEdgeVoice
import com.example.data.spokenParagraphs
import com.example.ui.comic.ComicReaderTestActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class ReaderAudioContinuationDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(ComicReaderTestActivity::class.java)

    @Test fun customSpeechSeeksInSecondsAndLoadsNextChapterAfterReaderStops() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val power = app.getSystemService(PowerManager::class.java)
        fun deviceCommand(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .use { it.readBytes() }
        }
        val manager = TtsManager(app)
        val initial = manager.source
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val worker = Executors.newSingleThreadExecutor()
        worker.submit {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use {
                    val input = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                    var size = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) size = line.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(size)
                    var received = 0
                    while (received < body.size) {
                        val count = input.read(body, received, body.size - received)
                        if (count < 0) break
                        received += count
                    }
                    val first = String(body).contains("short sample")
                    val wav = silentWave(if (first) 1200 else if (String(body).contains("A long sample")) 10_000 else 24_000)
                    it.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nContent-Length: " + wav.size +
                            "\r\nConnection: close\r\n\r\n").toByteArray())
                        write(wav)
                        flush()
                    }
                }
            }
        }
        val book = "device-audio-continuation"
        fun chapter(key: String, content: String) = ListeningChapter(book, key, "Fixture", key, spokenParagraphs(content))
        try {
            activity.scenario.onActivity {
                manager.configureCustomVoice(CustomVoiceService("http://127.0.0.1:" + server.localPort + "/speech", "", "fixture", "plain"))
                manager.selectSource(SpeechSource.Custom)
                manager.play(chapter("seek", "A long sample.\nAnother detailed sample."), 0, 1f, 1f)
            }
            waitFor(manager) { it.playing }
            activity.scenario.onActivity { manager.skipSeconds(15) }
            waitFor(manager) { it.paragraph == 1 }
            activity.scenario.onActivity {
                manager.play(chapter("first", "A short sample."), 0, 1f, 1f) { previous ->
                    if (previous.chapterKey == "first") chapter("second", "A second chapter.") else null
                }
            }
            activity.scenario.moveToState(Lifecycle.State.CREATED)
            deviceCommand("input keyevent KEYCODE_SLEEP")
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (power.isInteractive && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
            assertFalse("The screen did not turn off", power.isInteractive)
            waitFor(manager) { it.chapter?.chapterKey == "second" && it.playing }
        } finally {
            deviceCommand("input keyevent KEYCODE_WAKEUP")
            deviceCommand("wm dismiss-keyguard")
            activity.scenario.moveToState(Lifecycle.State.RESUMED)
            activity.scenario.onActivity { manager.selectSource(initial); manager.release() }
            server.close()
            worker.shutdownNow()
        }
    }

    @Test fun edgeProducesMp3ForSyntheticSample() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveEdge") == "true")
        val bytes = runBlocking {
            withTimeout(35_000) {
                NetworkSpeech().edge("A small library on a quiet street.", automaticEdgeVoice(Locale.ENGLISH), 1f, 1f)
            }
        }
        assertTrue(bytes.size > 1024)
    }

    private fun waitFor(manager: TtsManager, condition: (com.example.data.ListeningState) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!condition(manager.state.value) && manager.state.value.error == null &&
            SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue(manager.state.value.error ?: "Playback did not reach the expected state", condition(manager.state.value))
    }

    private fun silentWave(millis: Int): ByteArray {
        val length = millis * 16
        val buffer = ByteBuffer.allocate(44 + length).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36 + length).put("WAVEfmt ".toByteArray())
        buffer.putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
        buffer.put("data".toByteArray()).putInt(length)
        return buffer.array()
    }
}
