package com.example.source.zlibrary

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ZLibraryDomainResolver(
    private val context: Context,
    private val credentialStorage: ZLibraryCredentialStorage = ZLibraryCredentialStorage(context)
) {
    private val endpointProvider = ZLibraryEndpointProvider(context, credentialStorage)
    private val healthChecker = EndpointHealthChecker(context)

    suspend fun resolveDomain(forceScan: Boolean = false): String = withContext(Dispatchers.IO) {
        endpointProvider.getEndpoint(forceRefresh = forceScan)
    }

    suspend fun resolveNextDomain(excluded: Set<String>): String? = withContext(Dispatchers.IO) {
        endpointProvider.nextEndpoint(excluded)
    }

    fun saveToCache(domain: String) {
        endpointProvider.saveCache(domain)
    }

    fun invalidateCache() {
        endpointProvider.invalidateCache()
    }

    suspend fun checkDomainHealth(domain: String): DomainHealthResult = withContext(Dispatchers.IO) {
        val health = healthChecker.checkHealth(domain)
        DomainHealthResult(
            domain = domain,
            isAvailable = health.isAvailable,
            rtt = health.rtt,
            isCloudflare = health.isCloudflare,
            error = health.error
        )
    }
}

data class DomainHealthResult(
    val domain: String,
    val isAvailable: Boolean,
    val rtt: Long,
    val isCloudflare: Boolean,
    val error: String? = null
)
