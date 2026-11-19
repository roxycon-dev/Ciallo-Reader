package com.example.source.zlibrary

import android.content.Context
import com.example.library.ZLibraryNodeConfig
import com.example.library.ZLibraryNodeManager
import com.example.source.zlibrary.network.ZLibraryDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.example.source.executeCancellable
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ZLibraryEndpointProvider(
    private val context: Context,
    private val credentialStorage: ZLibraryCredentialStorage = ZLibraryCredentialStorage(context),
    private val healthChecker: EndpointHealthChecker = EndpointHealthChecker(context),
    private val remoteProvider: RemoteEndpointProvider = RemoteEndpointProvider(context)
) {
    companion object {
        private const val PREFS_NAME = "zlib_domain_cache"
        private const val KEY_CACHE_DOMAIN = "cached_domain"
        private const val KEY_CACHE_TIMESTAMP = "cached_timestamp"
        private const val KEY_CUSTOM_ENDPOINT = "custom_endpoint"
        private const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L // 24 hours

        // 节点容灾体检：选中/缓存节点定期快速探测，死了自动换活节点（2026-09-04）
        private const val PROBE_TTL_MS = 60 * 1000L // 探测结果 60s 内复用，常规搜索不重复探测
        private const val PROBE_TIMEOUT_S = 4L      // 单次探测 4s 超时，不拖慢主链路

        // Candidates are probed at runtime; old availability reports are not trusted.
        val PRESET_DOMAINS = ZLibraryNodeManager.INITIAL_SCRAPED_NODES
        private val probeSlots = Semaphore(3)
        private val failedUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val FALLBACK_DOMAINS = PRESET_DOMAINS
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Reuse a short-lived successful direct probe; a challenge page is not healthy.
    private val quickCheckClient = com.example.source.SharedHttpTransport.builder()
        .dns(ZLibraryDns.INSTANCE)
        .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    // 系统代理探测客户端：很多用户经代理才能访问 zlib，直连失败不代表节点死亡。
    private val proxyCheckClient: okhttp3.OkHttpClient by lazy {
        val builder = com.example.source.SharedHttpTransport.builder()
            .dns(ZLibraryDns.INSTANCE)
            .callTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .connectTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            .writeTimeout(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
        com.example.source.zlibrary.network.SystemProxyResolver.resolve(context)?.let { builder.proxy(it) }
        builder.build()
    }

    @Volatile
    private var lastProbe: Triple<String, Boolean, Long>? = null

    private suspend fun isNodeReachable(domain: String): Boolean {
        if (domain.isBlank()) return false
        val now = System.currentTimeMillis()
        lastProbe?.let { (d, ok, at) ->
            if (d == domain && now - at < PROBE_TTL_MS) return ok
        }
        if ((failedUntil[domain] ?: 0L) > now) return false
        val request = Request.Builder().url("https://$domain/").get().build()
        suspend fun probe(client: okhttp3.OkHttpClient, direct: Boolean): Boolean = try {
            val ok = client.newCall(request).executeCancellable().use {
                it.isSuccessful && isZLibraryPage(it.peekBody(256 * 1024).string())
            }
            if (ok) ZLibraryNodeConfig.rememberRoute(domain, direct)
            ok
        } catch (e: Exception) { currentCoroutineContext().ensureActive(); false }
        // 先直连，失败再走系统代理：不能因为 NO_PROXY 直连不通就误判节点死亡。
        val directClient = quickCheckClient.newBuilder().proxy(java.net.Proxy.NO_PROXY).build()
        val ok = probe(directClient, true) || probe(proxyCheckClient, false)
        lastProbe = Triple(domain, ok, now)
        return ok
    }

    /** 换节点前的二次确认：全量健康检查（代理 + 直连双路），任一通过即认为节点仍活。
     *  快速体检可能因瞬时抖动/挑战页误报，全量检查带 DiamWall PoW 解算与搜索探针，更可靠。 */
    private suspend fun confirmAlive(domain: String): Boolean {
        val proxyHealth = runCatching { healthChecker.checkHealth(domain) }.getOrNull()
        if (proxyHealth != null && proxyHealth.isAvailable && proxyHealth.isRealZlib) return true
        val directHealth = runCatching { EndpointHealthChecker(context, direct = true).checkHealth(domain) }.getOrNull()
        return directHealth != null && directHealth.isAvailable && directHealth.isRealZlib
    }

    /**
     * 扫描候选池选活节点：远程门户动态发现（官方披露域名，自动跟上站点归拢/
     * 迁移）∪ 节点管理候选（官网/备用入口/用户自加）∪ 预置域名。并行健康检查
     * ——最多 3 个并发，首个搜索可用节点立即返回，整轮最多 25 秒；优先取“搜索可用”的节点（可达但搜索故障的只作
     * 下载兜底，否则搜到的全是主页推荐书）。全挂返回 null。
     */
    private suspend fun scanForLiveNode(excluded: Set<String> = emptySet()): String? {
        val local = (ZLibraryNodeManager.getScrapedNodes(context) +
            ZLibraryNodeManager.getCustomNodes(context) + PRESET_DOMAINS).distinct()
        val directChecker = EndpointHealthChecker(context, direct = true)
        suspend fun scan(candidates: List<String>, direct: Boolean, budget: Long): String? {
            val eligible = candidates.distinct().filter {
                ZLibraryNodeManager.isAutomaticCandidate(it) && it !in excluded &&
                    (failedUntil[it] ?: 0L) <= System.currentTimeMillis()
            }.take(32)
            return selectHealthyNode(eligible, budget) { candidate ->
                probeSlots.withPermit {
                    val health = (if (direct) directChecker else healthChecker).checkHealth(candidate)
                    if (health.isAvailable && health.isRealZlib && health.searchAvailable) {
                        ZLibraryNodeConfig.rememberRoute(candidate, direct)
                        true
                    } else false
                }
            }
        }
        // Exhaust a bounded direct phase before using the system proxy. A VPN cannot
        // be detected reliably; NO_PROXY means no explicit HTTP proxy is used.
        scan(local, direct = true, budget = 12_000)?.let { return it }
        val remote = try {
            withTimeoutOrNull(4_000) { remoteProvider.fetchLatestEndpoints() }
                .orEmpty().sortedByDescending { it.priority }.map { it.url }
        } catch (e: Exception) { currentCoroutineContext().ensureActive(); emptyList() }
        val fresh = remote.map { it.removePrefix("https://").removePrefix("http://").trimEnd('/') }
            .filter { it !in local }
        scan(fresh, direct = true, budget = 8_000)?.let { return it }
        return scan(local + fresh, direct = false, budget = 16_000)
    }

    suspend fun nextEndpoint(excluded: Set<String>): String? = withContext(Dispatchers.IO) {
        excluded.forEach { failedUntil[it] = System.currentTimeMillis() + 5 * 60_000 }
        scanForLiveNode(excluded)?.also { adoptLiveNode(it) }
    }

    /** Persist the working mirror so restarting does not return to the failed one. */
    private fun adoptLiveNode(domain: String) {
        failedUntil.remove(domain)
        saveCache(domain)
        ZLibraryNodeManager.selectNode(context, domain)
        lastProbe = Triple(domain, true, System.currentTimeMillis())
    }

    /** 选中/自定义节点体检失败后的自动容灾：扫描换活节点并固化。
     *  返回 null = 全池不可达（保持原节点，交给 WebView Chromium 指纹/系统代理兜底）。 */
    private suspend fun failoverFrom(deadDomain: String): String? {
        val winner = scanForLiveNode(setOf(deadDomain)) ?: return null
        if (winner == deadDomain) {
            // 快速体检误报（HEAD 被挡但全量健康检查通过）：预热缓存避免反复重扫
            lastProbe = Triple(deadDomain, true, System.currentTimeMillis())
            return null
        }
        adoptLiveNode(winner)
        return winner
    }

    suspend fun getEndpoint(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        // 0. 用户选择优先。容灾只在节点确认死亡时临时换活节点，且不覆盖用户固化选择；
        //    用户固化的节点恢复后自动切回。快速体检直连+代理双路，换节点前再做全量
        //    二次确认，最大限度避免“选了能用的节点却被换到不能用的”。
        val selectedNode = runCatching { ZLibraryNodeConfig.domain }
            .getOrDefault("")
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removeSuffix("/")
        val pinnedNode = runCatching { ZLibraryNodeManager.getPinnedNode(context) }
            .getOrNull()
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removeSuffix("/")
        if (!selectedNode.isNullOrBlank() && !forceRefresh) {
            // 运行时停在容灾临时节点上：复探用户固化节点，活了立即切回用户的选择。
            if (!pinnedNode.isNullOrBlank() && pinnedNode != selectedNode && isNodeReachable(pinnedNode)) {
                ZLibraryNodeConfig.domain = pinnedNode
                saveCache(pinnedNode)
                lastProbe = Triple(pinnedNode, true, System.currentTimeMillis())
                return@withContext pinnedNode
            }
            if (isNodeReachable(selectedNode)) {
                return@withContext selectedNode
            }
            // 快速体检失败 ≠ 节点死亡：全量健康检查二次确认，仍活就继续用，绝不轻易换走。
            if (confirmAlive(selectedNode)) {
                lastProbe = Triple(selectedNode, true, System.currentTimeMillis())
                return@withContext selectedNode
            }
            failoverFrom(selectedNode)?.let { return@withContext it }
            return@withContext selectedNode
        }

        // 1. User custom config（同样体检：死了不再原地卡死，落回扫描流程自动换）
        val customDomain = prefs.getString(KEY_CUSTOM_ENDPOINT, null)?.trim()
        if (!customDomain.isNullOrBlank() && !forceRefresh) {
            val cleanCustom = customDomain.removePrefix("https://").removePrefix("http://").removeSuffix("/")
            if (isNodeReachable(cleanCustom)) return@withContext cleanCustom
        }

        // 2. History success cache（24h 内验证过的节点直接复用，省一次扫描往返；
        //    体检失败说明缓存节点已死/被墙，作废重扫，绝不把死节点再交出去）
        if (!forceRefresh) {
            val cached = prefs.getString(KEY_CACHE_DOMAIN, null)
            val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
            if (!cached.isNullOrBlank() && (System.currentTimeMillis() - timestamp < CACHE_DURATION_MS)) {
                if (isNodeReachable(cached)) return@withContext cached
                invalidateCache()
            }
        }

        // 3. 扫描候选池选活节点并固化（forceScan 强制重扫、缓存过期、容灾均走这里）。
        //    注意：远程发现的高优先级域名不再直接采信（旧逻辑曾把被墙的远程冠军
        //    原样返回，forceScan 也救不回来）——一律过健康检查才算数。
        scanForLiveNode()?.let {
            adoptLiveNode(it)
            return@withContext it
        }

        // 4. Fallback to first preset domain if all fail
        PRESET_DOMAINS.first()
    }

    fun saveCache(domain: String) {
        prefs.edit()
            .putString(KEY_CACHE_DOMAIN, domain)
            .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
            .commit()
    }

    fun setCustomEndpoint(domain: String?) {
        val cleanDomain = domain?.trim()?.removePrefix("https://")?.removePrefix("http://")?.removeSuffix("/")
        prefs.edit()
            .putString(KEY_CUSTOM_ENDPOINT, cleanDomain)
            .commit()
        if (!cleanDomain.isNullOrBlank()) {
            credentialStorage.saveCredentials(
                userId = credentialStorage.getUserId(),
                userKey = credentialStorage.getUserKey(),
                domain = cleanDomain,
                cookies = credentialStorage.getCookies()
            )
        }
    }

    fun getCustomEndpoint(): String? = prefs.getString(KEY_CUSTOM_ENDPOINT, null)

    fun invalidateCache() {
        prefs.edit().remove(KEY_CACHE_DOMAIN).remove(KEY_CACHE_TIMESTAMP).commit()
    }

    suspend fun diagnoseEndpoint(): Map<String, Any> = withContext(Dispatchers.IO) {
        val endpoint = getEndpoint(forceRefresh = true)
        val health = healthChecker.checkHealth(endpoint)
        mapOf(
            "endpoint" to endpoint,
            "dnsOk" to health.dnsOk,
            "tlsOk" to health.tlsOk,
            "httpCode" to health.httpCode,
            "isAvailable" to health.isAvailable,
            "isCloudflare" to health.isCloudflare,
            "rtt" to health.rtt,
            "error" to (health.error ?: "None")
        )
    }

    suspend fun diagnoseAllEndpoints(): List<EndpointHealthResult> = withContext(Dispatchers.IO) {
        val domains = (listOfNotNull(getCustomEndpoint()) + PRESET_DOMAINS).distinct()
        domains.map { domain ->
            healthChecker.checkHealth(domain)
        }
    }
}
