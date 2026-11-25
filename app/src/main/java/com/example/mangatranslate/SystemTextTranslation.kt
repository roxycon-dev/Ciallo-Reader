package com.example.mangatranslate

import android.content.Context
import android.icu.util.ULocale
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.view.translation.TranslationCapability
import android.view.translation.TranslationContext
import android.view.translation.TranslationManager
import android.view.translation.TranslationRequest
import android.view.translation.TranslationRequestValue
import android.view.translation.TranslationResponse
import android.view.translation.TranslationResponseValue
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Use an OEM translation service only when the language pair is installed and ready. */
internal class SystemTextTranslation(private val context: Context) {
    private val capabilityLock = Mutex()
    private var snapshot: Set<TranslationCapability> = emptySet()
    private var checkedAt = -60_000L

    suspend fun translate(texts: List<String>, sourceLanguage: String): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || texts.isEmpty()) return null
        return try {
            withTimeoutOrNull(1_800L) { translateOnDevice(texts, sourceLanguage) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun translateOnDevice(texts: List<String>, sourceLanguage: String): List<String>? {
        val manager = context.getSystemService(TranslationManager::class.java) ?: return null
        val format = android.view.translation.TranslationSpec.DATA_FORMAT_TEXT
        val capabilities = capabilityLock.withLock {
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt >= 60_000L) {
                snapshot = runInterruptible(Dispatchers.IO) {
                    manager.getOnDeviceTranslationCapabilities(format, format)
                }
                checkedAt = now
            }
            snapshot
        }
        val pair = capabilities.firstOrNull {
            it.state == TranslationCapability.STATE_ON_DEVICE &&
                it.sourceSpec.locale.language == sourceLanguage &&
                it.targetSpec.locale.language == "zh" &&
                ULocale.addLikelySubtags(it.targetSpec.locale).script == "Hans"
        } ?: return null
        val requestContext = TranslationContext.Builder(pair.sourceSpec, pair.targetSpec)
            .setTranslationFlags(pair.supportedTranslationFlags and TranslationContext.FLAG_LOW_LATENCY)
            .build()
        val executor = context.mainExecutor
        val translator = suspendCancellableCoroutine<android.view.translation.Translator?> { continuation ->
            manager.createOnDeviceTranslator(requestContext, executor) { service ->
                continuation.resume(service) { _, resource, _ -> resource?.destroy() }
            }
        } ?: return null
        try {
            val request = TranslationRequest.Builder()
                .setTranslationRequestValues(texts.map(TranslationRequestValue::forText))
                .build()
            return suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                translator.translate(request, signal, executor) callback@{ response ->
                    if (!response.isFinalResponse || !continuation.isActive) return@callback
                    val values = response.translationResponseValues
                    val result = if (response.translationStatus == TranslationResponse.TRANSLATION_STATUS_SUCCESS) {
                        texts.indices.map { index ->
                            values[index]?.takeIf { it.statusCode == TranslationResponseValue.STATUS_SUCCESS }
                                ?.text?.toString()?.trim().orEmpty()
                        }.takeIf { it.any(String::isNotBlank) }
                    } else null
                    continuation.resume(result)
                }
            }
        } finally {
            translator.destroy()
        }
    }
}
