package com.example.source.impl

import android.content.Context
import com.example.source.SharedHttpTransport
import com.example.source.executeCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 爱丽丝书屋线路池。
 *
 * 站点同时在跑多条线路（`alicesw4.homes`、`alicesw3.homes`……），墙内可达性随线路
 * 和网络状况变化：同一域名可能这一秒 TLS 握手被重置、下一秒就通，因此不做"域名黑名单"
 * 这种长期判定，只在失败后冷却几分钟再重新探活。
 *
 * 选路顺序：上次成功的线路 → 预置线路 → 线路页动态发现的线路；探活并行进行，
 * 首个返回真实站点页面的线路立即采用，整轮最多等 [PROBE_ROUND_MS]。
 */
class AliceSwEndpoints(
    context: Context,
    private val client: OkHttpClient? = null
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile
    private var current: String? = null

    private val probeClient: OkHttpClient by lazy {
        (client ?: SharedHttpTransport.builder().build()).newBuilder()
            .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    private val discoveryClient: OkHttpClient by lazy {
        (client ?: SharedHttpTransport.builder().build()).newBuilder()
            .callTimeout(DISCOVERY_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(DISCOVERY_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(DISCOVERY_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    }

    /** 已选定的线路（协议 + 主机），没有则返回 null；不发网络请求。 */
    fun currentBase(): String? = current ?: prefs.getString(KEY_DOMAIN, null)?.let { "https://$it" }

    /**
     * 取一条可用线路的 base url（不带结尾斜杠）。全部线路都不可用时返回 null，
     * 由调用方转换成书源错误提示。
     */
    suspend fun baseUrl(forceRefresh: Boolean = false): String? = mutex.withLock {
        val now = System.currentTimeMillis()
        val cached = prefs.getString(KEY_DOMAIN, null)?.takeIf { it.isNotBlank() }
        val cachedAt = prefs.getLong(KEY_AT, 0L)
        if (!forceRefresh && cached != null && now - cachedAt < CACHE_TTL_MS && !cooling(cached, now)) {
            current = "https://$cached"
            return@withLock current
        }
        val quick = buildList {
            cached?.let { add(it) }
            addAll(PRESET_HOSTS)
        }.distinct().filter { !cooling(it, now) }
        pick(quick)?.let { return@withLock remember(it) }

        val discovered = discoverHosts().filter { it !in quick && !cooling(it, now) }
        pick(discovered)?.let { return@withLock remember(it) }
        null
    }

    /** 记录一条线路失败：短期冷却，避免每次请求都先撞一遍死线路。 */
    fun reportFailure(base: String) {
        val host = hostOf(base) ?: return
        failedUntil[host] = System.currentTimeMillis() + FAILURE_COOLDOWN_MS
        if (current?.endsWith(host, ignoreCase = true) == true) current = null
    }

    private fun remember(host: String): String {
        val base = "https://$host"
        current = base
        failedUntil.remove(host)
        prefs.edit().putString(KEY_DOMAIN, host).putLong(KEY_AT, System.currentTimeMillis()).apply()
        return base
    }

    private fun cooling(host: String, now: Long): Boolean = (failedUntil[host] ?: 0L) > now

    private suspend fun pick(candidates: List<String>): String? {
        if (candidates.isEmpty()) return null
        return coroutineScope {
            val hits = Channel<String>(Channel.UNLIMITED)
            val probes = candidates.map { host ->
                async(Dispatchers.IO) { if (reachable(host)) hits.send(host) }
            }
            val winner = withTimeoutOrNull(PROBE_ROUND_MS) { hits.receive() }
            probes.forEach { it.cancel() }
            hits.close()
            winner
        }
    }

    private suspend fun reachable(host: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://$host/")
                .header("User-Agent", AliceSwSource.USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            probeClient.newCall(request).executeCancellable().use { response ->
                response.isSuccessful && isAlicePage(response.peekBody(512 * 1024).string())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    /** 从线路页动态发现域名；发布页/线路页本身不可达时静默返回空列表。 */
    private suspend fun discoverHosts(): List<String> = withContext(Dispatchers.IO) {
        val hosts = mutableListOf<String>()
        for (seed in DISCOVERY_SEEDS) {
            val html = try {
                val request = Request.Builder()
                    .url(seed)
                    .header("User-Agent", AliceSwSource.USER_AGENT)
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                    .get()
                    .build()
                discoveryClient.newCall(request).executeCancellable().use { response ->
                    if (response.isSuccessful) response.peekBody(256 * 1024).string() else ""
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            if (html.isNotBlank()) hosts += AliceSwDiscovery.hosts(html)
        }
        hosts.distinct()
    }

    private fun isAlicePage(html: String): Boolean =
        html.isNotBlank() && (html.contains("alicesw", ignoreCase = true) || html.contains("爱丽丝书屋"))

    private fun hostOf(base: String): String? = runCatching {
        java.net.URI(base.trim()).host
    }.getOrNull()?.lowercase()

    companion object {
        /** 站点自报的线路，按实测可用性排序；全部不可用时才会去线路页找新域名。 */
        val PRESET_HOSTS = listOf(
            "alicesw4.homes",
            "alicesw3.homes",
            "alicesw2.homes",
            "alicesw1.homes",
            "alicesw.com"
        )

        /** 线路页与跳转页：只用来发现域名，不参与选路。 */
        val DISCOVERY_SEEDS = listOf(
            "https://www.alicesw1.quest/",
            "https://xn--vcsx64d.als1007.space/"
        )

        private const val PREFS_NAME = "alicesw_route_cache"
        private const val KEY_DOMAIN = "domain"
        private const val KEY_AT = "timestamp"
        private const val CACHE_TTL_MS = 12 * 60 * 60 * 1000L
        private const val FAILURE_COOLDOWN_MS = 5 * 60 * 1000L
        private const val PROBE_TIMEOUT_S = 4L
        private const val DISCOVERY_TIMEOUT_S = 6L
        private const val PROBE_ROUND_MS = 12_000L

        private val failedUntil = ConcurrentHashMap<String, Long>()
    }
}
