package com.example.data

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal enum class SpeechSource { System, Edge, Custom }

internal data class CustomVoiceService(val url: String, val token: String, val model: String, val voice: String) {
    fun valid(): Boolean {
        val endpoint = url.toHttpUrlOrNull() ?: return false
        return (endpoint.isHttps || token.isBlank() || endpoint.host in setOf("localhost", "127.0.0.1", "::1")) &&
            endpoint.username.isEmpty() && endpoint.password.isEmpty() && endpoint.fragment == null &&
            model.isNotBlank() && voice.isNotBlank()
    }
}

internal data class EdgeVoice(val name: String, val locale: String, val label: String)

internal class NetworkSpeech {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS).followRedirects(false).build()
    private val endpoint = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud"
    @Volatile private var clockOffset = 0L
    private val voiceName = Regex("^[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,40}){2,6}Neural$")
    private val timestamp get() = java.text.SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(System.currentTimeMillis() + clockOffset))

    suspend fun voices(): List<EdgeVoice> {
        val request = common("$endpoint/voices/list?trustedclienttoken=" + EdgeVoiceProof.CLIENT).build()
        val json = org.json.JSONArray(requestBytes(request, 2 * 1024 * 1024).toString(Charsets.UTF_8))
        return buildList {
            repeat(minOf(json.length(), 3000)) {
                val item = json.getJSONObject(it)
                val name = item.optString("ShortName")
                val locale = item.optString("Locale")
                if (voiceName.matches(name) && name.startsWith("$locale-", true)) {
                    // FriendlyName 形如 "Microsoft Xiaoxiao Online (Natural) - Chinese (Mainland)"，
                    // 太长会在窄面板里换行被截断；这里只留短名，列表再拼 " · locale"。
                    // 只取拉丁短名：任何非拉丁字符都回退到 ShortName 的拉丁部分，
                    // 避免个别音色的本地化名字在列表里变成方框/乱码。
                    val friendly = item.optString("FriendlyName", name)
                    val candidate = friendly.removePrefix("Microsoft ")
                        .substringBefore(" Online").trim()
                    val latin = candidate.filter { it.code < 0x0250 || it in "·- " }.trim()
                    val short = latin.ifBlank {
                        name.removeSuffix("Neural").substringAfter(locale + "-", name).trim()
                    }.ifBlank { name }
                    add(EdgeVoice(name, locale, short))
                }
            }
        }.sortedBy { it.locale }
    }

    suspend fun edge(text: String, voice: EdgeVoice, rate: Float, pitch: Float): ByteArray {
        require(voiceName.matches(voice.name) && voice.locale.matches(Regex("[a-zA-Z0-9-]{2,40}")))
        return try {
            edgeRequest(text, voice, rate, pitch)
        } catch (_: EdgeClockChanged) {
            edgeRequest(text, voice, rate, pitch)
        }
    }

    private class EdgeClockChanged : IOException()

    private suspend fun edgeRequest(text: String, voice: EdgeVoice, rate: Float, pitch: Float): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val id = UUID.randomUUID().toString().replace("-", "")
            val proof = EdgeVoiceProof.value(System.currentTimeMillis() + clockOffset)
            val url = "$endpoint/edge/v1?TrustedClientToken=" + EdgeVoiceProof.CLIENT +
                "&ConnectionId=$id&Sec-MS-GEC=$proof&Sec-MS-GEC-Version=" + EdgeVoiceProof.VERSION
            val request = common(url).header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("Cookie", "muid=" + id.uppercase(Locale.ROOT) + ";").build()
            val output = ByteArrayOutputStream()
            val socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val ssml = "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
                        "xml:lang=\"" + voice.locale + "\"><voice name=\"" + voice.name +
                        "\"><prosody pitch=\"" + signed((pitch - 1) * 100) + "Hz\" rate=\"" +
                        signed((rate - 1) * 100) + "%\" volume=\"+0%\">" + xml(text) + "</prosody></voice></speak>"
                    val now = timestamp
                    webSocket.send("X-Timestamp:$now\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                        """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}""")
                    webSocket.send("X-RequestId:$id\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:" +
                        now + "Z\r\nPath:ssml\r\n\r\n" + ssml)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (bytes.size < 2) return
                    val head = ((bytes[0].toInt() and 255) shl 8) or (bytes[1].toInt() and 255)
                    if (head !in 1..(bytes.size - 2)) return
                    if (!bytes.substring(2, 2 + head).utf8().lineSequence().any { it.equals("Path:audio", true) }) return
                    if (output.size() + bytes.size - head - 2 > MAX_CLIP) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Speech output exceeds limit"))
                        webSocket.cancel()
                        return
                    }
                    output.write(bytes.substring(2 + head).toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (!text.lineSequence().any { it.equals("Path:turn.end", true) } || !continuation.isActive) return
                    if (output.size() >= 100) continuation.resume(output.toByteArray())
                    else continuation.resumeWithException(IOException("Empty speech output"))
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Incomplete speech output"))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!continuation.isActive) return
                    val skew = response?.headers?.getDate("Date")?.time?.minus(System.currentTimeMillis())
                    if (response?.code == 403 && skew != null && kotlin.math.abs(skew - clockOffset) > 1_000) {
                        clockOffset = skew
                        continuation.resumeWithException(EdgeClockChanged())
                    } else continuation.resumeWithException(IOException("Edge speech connection failed", t))
                }
            })
            continuation.invokeOnCancellation { socket.cancel() }
        }

    suspend fun custom(text: String, config: CustomVoiceService, rate: Float): ByteArray {
        require(config.valid())
        val body = JSONObject().put("model", config.model).put("voice", config.voice)
            .put("input", text).put("speed", rate.toDouble()).put("response_format", "mp3")
        val request = Request.Builder().url(config.url)
            .header("Accept", "audio/mpeg, audio/*, application/octet-stream")
            .post(body.toString().toRequestBody("application/json".toMediaType())).apply {
                if (config.token.isNotBlank()) header("Authorization", "Bearer " + config.token)
            }.build()
        return requestBytes(request, MAX_CLIP).also { require(it.isNotEmpty()) }
    }

    private fun common(url: String) = Request.Builder().url(url)
        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0")
        .header("Accept-Language", "en-US,en;q=0.9")

    private suspend fun requestBytes(request: Request, limit: Int): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { result ->
                        val bytes = runCatching {
                            if (!result.isSuccessful) throw IOException("Speech HTTP " + result.code)
                            result.headers.getDate("Date")?.time?.let { now ->
                                if (request.url.host == "speech.platform.bing.com") clockOffset = now - System.currentTimeMillis()
                            }
                            requireNotNull(result.body).byteStream().use { it.readImportBytes(limit) }
                        }
                        if (!continuation.isActive) return
                        bytes.fold(continuation::resume, continuation::resumeWithException)
                    }
                }
            })
        }

    private fun signed(value: Float) = (if (value >= 0) "+" else "") + value.toInt()

    private fun xml(text: String) = text.filter { it >= ' ' || it == '\n' || it == '\t' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private companion object {
        const val MAX_CLIP = 8 * 1024 * 1024
    }
}

internal fun automaticEdgeVoice(locale: Locale): EdgeVoice {
    val (name, tag) = when (locale.language) {
        "zh" -> "zh-CN-XiaoxiaoNeural" to "zh-CN"
        "ja" -> "ja-JP-NanamiNeural" to "ja-JP"
        "ko" -> "ko-KR-SunHiNeural" to "ko-KR"
        else -> "en-US-EmmaMultilingualNeural" to "en-US"
    }
    return EdgeVoice(name, tag, name)
}
