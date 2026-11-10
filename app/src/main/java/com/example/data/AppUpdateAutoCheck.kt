package com.example.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The mirror only carries the official APK bytes over HTTPS. It supplies no
 * version metadata, and it is offered only when GitHub published a verifiable
 * SHA-256 for the exact same-repository asset URL. No credentials are added.
 */
internal const val UPDATE_MIRROR_PREFIX = "https://gh-proxy.com/"
internal const val UPDATE_MIRROR_HOST = "gh-proxy.com"

/** Exact mirror URL for an official asset; null when it cannot be verified end to end. */
internal fun mirrorDownloadUrl(tag: String, asset: UpdateApkAsset): String? {
    if (asset.sha256?.matches(Regex("[0-9a-f]{64}")) != true) return null
    if (!officialUpdateAsset(tag, asset.name, asset.url)) return null
    return UPDATE_MIRROR_PREFIX + asset.url
}

/** Browser downloads always use the official same-repository URL, never the mirror. */
internal fun officialDownloadUrl(tag: String, asset: UpdateApkAsset): String? =
    if (officialUpdateAsset(tag, asset.name, asset.url)) asset.url else null

/**
 * Shares one in-flight GitHub request between the silent startup check and the
 * manual Settings check. The manual result is never replaced by the background
 * check; failures are not cached so an explicit retry still reaches the network.
 */
internal object UpdateCheckCoordination {
    private val lock = Any()
    private val flights = mutableMapOf<String, CompletableDeferred<UpdateCheckResult>>()
    private var completedAt: Long? = null

    fun completedRecently(windowMs: Long = 90_000): Boolean = synchronized(lock) {
        val at = completedAt ?: return false
        System.nanoTime() - at <= windowMs * 1_000_000
    }

    suspend fun run(key: String, block: suspend () -> UpdateCheckResult): UpdateCheckResult {
        val mine: CompletableDeferred<UpdateCheckResult>?
        val wait: CompletableDeferred<UpdateCheckResult>?
        synchronized(lock) {
            val current = flights[key]
            if (current != null) {
                wait = current
                mine = null
            } else {
                val fresh = CompletableDeferred<UpdateCheckResult>()
                flights[key] = fresh
                wait = null
                mine = fresh
            }
        }
        if (wait != null) return wait.await()
        try {
            val result = block()
            if (result.release != null) synchronized(lock) { completedAt = System.nanoTime() }
            mine?.complete(result)
            return result
        } catch (failure: Throwable) {
            mine?.completeExceptionally(failure)
            throw failure
        } finally {
            synchronized(lock) { if (flights[key] === mine) flights.remove(key) }
        }
    }
}

/**
 * Kazumi-like startup behaviour: after onboarding, once per process, wait a
 * little and silently ask GitHub for the latest stable release. Only a genuinely
 * newer release opens the dialog; every failure stays silent and an in-progress
 * download/install is never covered or replaced.
 */
internal object AppUpdateAutoCheck {
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun maybeCheckOnStartup(
        context: Context,
        manager: AppUpdateManager,
        versionName: String,
    ) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch {
            val current = manager.state.value
            if (current.visible || current.transferring || current.hasPackage) return@launch
            // A manual check that just finished already offered any release.
            if (UpdateCheckCoordination.completedRecently()) return@launch
            try {
                val result = GithubUpdateChecker(app).check(versionName)
                result.release?.let { manager.offerAuto(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Silent by design: startup updates must not interrupt reading.
            }
        }
    }
}
