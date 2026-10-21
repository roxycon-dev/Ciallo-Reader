package com.example.discover

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.source.*
import com.example.source.impl.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in release audit. Never makes network calls during ordinary device test runs. */
@RunWith(AndroidJUnit4::class)
class NovelDiscoveryLiveDeviceTest {
    @Test fun everyPublishedNovelSourceUsesRealDiscoverContracts() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDiscovery") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val allSources: List<BookSource> = listOf(PublicNovelSite.QUANBEN, PublicNovelSite.IHUABEN, PublicNovelSite.SFACG, PublicNovelSite.FANQIE)
            .map { PublicNovelSource(context, it) } + listOf(IxdzsSource(context), XbookcnSource(context), Wenku8LibrarySource(context), AutoNovelSource(context))
        val selected = InstrumentationRegistry.getArguments().getString("discoverySources")?.split(',')
        val sources = allSources.filter { selected == null || it.id in selected }
        val slots = Semaphore(3)
        val results = sources.map { source -> async(Dispatchers.IO) { slots.withPermit {
            val report = JSONObject().put("source", source.id)
            val failures = mutableListOf<String>()
            val start = System.currentTimeMillis()
            val catalog = source.discoverCatalog()
            if (catalog is SourceResult.Success) {
                report.put("categories", catalog.data.categories.size)
                report.put("labels", JSONArray(catalog.data.categories.map { it.label }))
                report.put("unmapped", JSONArray(catalog.data.categories.filter { "native_other" in it.standardIds }.map { it.label }))
                if (catalog.data.categories.isEmpty()) failures += "empty catalogue"
            } else failures += "catalogue: ${(catalog as SourceResult.Error).exception.message}"
            val feeds = JSONArray()
            for (kind in listOf("recommend", "updates", "ranking")) {
                val q = DiscoverQuery(if (kind == "ranking") DiscoverDimension.RANKING else DiscoverDimension.UPDATES,
                    if (kind == "ranking") "热门" else "最新", feedId = if (kind == "recommend") "native" else null)
                val row = JSONObject().put("kind", kind)
                try {
                    when (val response = withTimeout(100_000) { source.discover(q) }) {
                        is SourceResult.Error -> { failures += "$kind: ${response.exception.message} (${response.exception.cause})"; row.put("error", response.exception.toString() + " cause=" + response.exception.cause) }
                        is SourceResult.Success -> {
                            val p = response.data
                            row.put("supported", p.supported).put("count", p.entries.size).put("more", p.hasMore).put("notice", p.notice)
                            row.put("origins", JSONArray(p.entries.mapNotNull { it.origin }.distinct()))
                            row.put("sampleIds", JSONArray(p.entries.take(3).map { it.book.id }))
                            if (source.id == "quanben" && kind != "recommend") {
                                if (p.supported || p.notice.isNullOrBlank()) failures += "$kind must explicitly report unavailable"
                            } else if (source.id == "auto_novel" && kind != "ranking") {
                                // The guest list endpoint answers 401; the source must say so instead of erroring.
                                if (p.supported || p.notice.isNullOrBlank()) failures += "$kind must explicitly report unavailable"
                                else if (!p.notice.orEmpty().contains("401")) failures += "$kind notice must explain the HTTP 401"
                            } else if (!p.supported || p.entries.isEmpty()) failures += "$kind returned no works"
                            if (p.entries.any { it.book.sourceId != source.id || it.book.title.any { c -> c.code in 0xe000..0xf8ff } }) failures += "$kind invalid identity/text"
                        }
                    }
                } catch (e: Exception) { failures += "$kind: ${e.message}"; row.put("error", e.message) }
                feeds.put(row)
            }
            report.put("feeds", feeds).put("elapsedMs", System.currentTimeMillis() - start).put("failures", JSONArray(failures))
            android.util.Log.i("NovelDiscoverAudit", report.toString())
            report to failures
        } } }.awaitAll()
        val dir = File(context.getExternalFilesDir(null), "paper-1.4.18").apply { mkdirs() }
        File(dir, "live-novel-discovery.json").writeText(JSONObject().put("at", System.currentTimeMillis())
            .put("sources", JSONArray(results.map { it.first })).toString(2))
        assertEquals(results.joinToString("\n") { "${it.first.getString("source")}: ${it.second}" }, emptyList<String>(), results.flatMap { it.second })
    }
    @Test fun probeNoveliaTransport() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("probeDiscovery") == "true")
        val report = JSONArray()
        for (protocols in listOf(listOf(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1), listOf(okhttp3.Protocol.HTTP_1_1))) {
            val events = mutableListOf<String>()
            val c = okhttp3.OkHttpClient.Builder().protocols(protocols)
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                .callTimeout(12, java.util.concurrent.TimeUnit.SECONDS).eventListener(object : okhttp3.EventListener() {
                    override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) { events += "dns=$inetAddressList" }
                    override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { events += "connect=$inetSocketAddress" }
                    override fun secureConnectStart(call: okhttp3.Call) { events += "tls start" }
                    override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) { events += "tls=${handshake?.tlsVersion}" }
                }).build()
            val row = JSONObject().put("protocols", protocols.toString())
            try {
                c.newCall(okhttp3.Request.Builder().url("https://n.novelia.cc/api/novel?page=0&pageSize=1&sort=0")
                    .header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").build()).execute().use { r ->
                    row.put("code", r.code).put("protocol", r.protocol.toString()).put("type", r.header("Content-Type"))
                }
            } catch (e: Exception) { row.put("error", e.toString()) }
            finally { c.dispatcher.executorService.shutdown(); c.connectionPool.evictAll() }
            row.put("events", JSONArray(events)); report.put(row)
        }
        val dir = File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "paper-1.4.18").apply { mkdirs() }
        File(dir, "novelia-network-probe.json").writeText(report.toString(2))
        android.util.Log.i("NovelDiscoverAudit", report.toString())
    }

}
