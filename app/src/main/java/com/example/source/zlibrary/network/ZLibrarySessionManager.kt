package com.example.source.zlibrary.network

import android.util.Log
import com.example.source.zlibrary.ZLibraryCredentialStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ZLibrarySessionManager(
    val httpClient: ZLibraryHttpClient,
    val credentialStorage: ZLibraryCredentialStorage
) {
    private var isSessionInitialized = false
    private var activeDomain: String = "z-library.sk"

    suspend fun ensureSessionInitialized(domain: String, forceRefresh: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (isSessionInitialized && activeDomain == domain && !forceRefresh) {
            return@withContext true
        }

        activeDomain = domain
        isSessionInitialized = false
        return@withContext try {
            val homepageUrl = "https://$domain/"
            Log.d("ZLibSession", "Initializing session at $homepageUrl")
            // 预热限时 4.5s（搜索修复）：首页 GET 只是提前解 DiamWall PoW 攒 Cookie，
            // 失败/超时不阻塞搜索——搜索请求里 DiamWall 拦截器会内联再解。注意必须用
            // OkHttp callTimeout：外层协程超时取消不了阻塞中的 socket read（实测 12s
            // read timeout 照样吃掉聚合搜索 20s 预算，表现为"卡在搜索"）。
            val response = httpClient.get(homepageUrl, callTimeoutMs = 4500L)
            response.close()
            if (response.isSuccessful || response.code == 517 || response.code == 307) {
                isSessionInitialized = true
                Log.d("ZLibSession", "Session initialized successfully for $domain")
                true
            } else {
                Log.w("ZLibSession", "Session init got HTTP ${response.code} for $domain")
                false
            }
        } catch (e: Exception) {
            Log.w("ZLibSession", "Session init unavailable for $domain: ${e.message} (PoW will be solved inline during search)")
            false
        }
    }

    fun isInitialized(): Boolean = isSessionInitialized

    fun getActiveDomain(): String = activeDomain

    fun invalidateSession() {
        isSessionInitialized = false
    }
}
