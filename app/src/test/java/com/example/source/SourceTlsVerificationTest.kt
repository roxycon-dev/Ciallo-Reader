package com.example.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.source.impl.JsonBookSource
import com.example.source.js.JsMessageHandler
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.security.KeyStore
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SourceTlsVerificationTest {
    private enum class Bridge { JSON, JAVASCRIPT }

    @Test fun `legacy insecure source settings cannot trust a self signed server`() {
        for (bridge in Bridge.entries) for (legacyFlag in listOf(false, true)) {
            TlsPeer().use { peer ->
                val client = client(bridge, legacyFlag, peer.url())
                val failure = assertThrows("$bridge insecureTls=$legacyFlag", IOException::class.java) {
                    client.newCall(Request.Builder().url(peer.url()).build()).execute().close()
                }
                assertTrue("Failure must come from TLS certificate verification: $failure", failure is javax.net.ssl.SSLHandshakeException)
                assertEquals("Untrusted TLS must not receive an HTTP request", 0, peer.requests())
            }
        }
    }

    @Test fun `legacy insecure source settings cannot bypass hostname verification`() {
        for (bridge in Bridge.entries) for (legacyFlag in listOf(false, true)) {
            TlsPeer().use { peer ->
                // Trust this test certificate's issuer, but keep the production hostname verifier.
                val client = client(bridge, legacyFlag, peer.url("wrong-host.test"), trustTestCertificate = peer)
                val failure = assertThrows("$bridge insecureTls=$legacyFlag", IOException::class.java) {
                    client.newCall(Request.Builder().url(peer.url("wrong-host.test")).build()).execute().close()
                }
                assertTrue("Failure must come from hostname verification: $failure", failure is javax.net.ssl.SSLPeerUnverifiedException)
                assertEquals("A valid chain is insufficient for the wrong host", 0, peer.requests())
            }
        }
    }

    @Test fun `verified peers still work with old source flags and authenticated requests`() {
        for (bridge in Bridge.entries) for (legacyFlag in listOf(false, true)) {
            TlsPeer().use { peer ->
                val client = client(bridge, legacyFlag, peer.url(), trustTestCertificate = peer)
                val request = Request.Builder().url(peer.url()).header("Authorization", "Bearer test-fixture").build()
                client.newCall(request).execute().use { response ->
                    assertEquals(200, response.code)
                    assertEquals("tls-ok", response.body?.string())
                }
                assertEquals(1, peer.requests())
            }
        }
    }

    @Test fun `HTTP sources cannot transmit Cookie or Authorization headers`() {
        for (bridge in Bridge.entries) for (legacyFlag in listOf(false, true)) {
            for (header in listOf("Authorization", "Cookie")) PlainPeer().use { peer ->
                val client = client(bridge, legacyFlag, peer.url())
                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url(peer.url()).header(header, "test-fixture").build()).execute().close()
                }
                assertEquals("Credentials must not reach an HTTP peer", 0, peer.requests())
            }
        }
    }

    @Test fun `redirects from verified HTTPS cannot forward cookies over HTTP`() {
        for (bridge in Bridge.entries) for (legacyFlag in listOf(false, true)) PlainPeer().use { plaintext ->
            TlsPeer(redirectTo = plaintext.url()).use { peer ->
                val client = client(bridge, legacyFlag, peer.url(), trustTestCertificate = peer)
                assertThrows(IOException::class.java) {
                    client.newCall(Request.Builder().url(peer.url()).header("Cookie", "session=test-fixture").build()).execute().close()
                }
                assertEquals("The first HTTPS request must use a valid TLS connection", 1, peer.requests())
                assertEquals("The redirect must not leak the cookie", 0, plaintext.requests())
            }
        }
    }

    @Test fun `anonymous HTTP sources remain usable with a legacy flag`() {
        for (bridge in Bridge.entries) PlainPeer().use { peer ->
            client(bridge, true, peer.url()).newCall(Request.Builder().url(peer.url()).build()).execute().use {
                assertEquals(200, it.code)
                assertEquals("http-ok", it.body?.string())
            }
            assertEquals(1, peer.requests())
        }
    }

    @Test fun `script messages reject HTTP credentials before selecting a native transport`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (legacyFlag in listOf(false, true)) for (header in listOf("Authorization", "Cookie")) {
            val result = JsMessageHandler(context, "tls_test", insecureTls = legacyFlag).handle(mapOf(
                "method" to "http", "url" to "http://127.0.0.1:1/not-contacted",
                "headers" to mapOf(header to "test-fixture")))
            assertTrue(JSONObject(result as String).optString("error").contains("非 HTTPS"))
        }
    }

    private fun client(bridge: Bridge, legacyFlag: Boolean, url: String,
                       trustTestCertificate: TlsPeer? = null): OkHttpClient {
        val transport = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
        trustTestCertificate?.let { transport.sslSocketFactory(it.clientContext.socketFactory, it.trustManager) }
        val instance: Any
        val field: String
        if (bridge == Bridge.JSON) {
            instance = JsonBookSource(SourceConfig(id = "tls-test", name = "TLS test", baseUrl = url,
                search = SearchRule(url), insecureTls = legacyFlag), transport.build())
            field = "requestClient"
        } else {
            val context = ApplicationProvider.getApplicationContext<Context>()
            instance = JsMessageHandler(context, "tls_test", insecureTls = legacyFlag)
            field = "client"
        }
        // Exercise the configured production TLS/hostname policy without exposing a test-only API.
        val configured = instance.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(instance) as OkHttpClient
        if (bridge == Bridge.JSON) return configured
        return configured.newBuilder()
            .proxy(Proxy.NO_PROXY)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            })
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .apply { trustTestCertificate?.let { sslSocketFactory(it.clientContext.socketFactory, it.trustManager) } }
            .build()
    }

    private class PlainPeer : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5_000 }
        private val reply = FutureTask {
            var requests = 0
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.inputStream.bufferedReader()
                    if (reader.readLine() != null) {
                        requests++
                        while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                        socket.outputStream.write(("HTTP/1.1 200 OK\r\nContent-Length: 7\r\n" +
                            "Connection: close\r\n\r\nhttp-ok").toByteArray(Charsets.US_ASCII))
                        socket.outputStream.flush()
                    }
                }
            } catch (_: IOException) { /* Blocked requests close their connected socket. */ }
            requests
        }
        private val worker = Thread(reply, "source-http-fixture").apply { isDaemon = true; start() }
        fun url() = "http://127.0.0.1:${server.localPort}/data"
        fun requests(): Int = reply.get(6, TimeUnit.SECONDS)
        override fun close() { server.close(); worker.interrupt() }
    }

    /** Public, test-only certificate/key; never added to Android production trust stores. */
    private class TlsPeer(private val redirectTo: String? = null) : Closeable {
        private val keyStore = KeyStore.getInstance("PKCS12").apply {
            SourceTlsVerificationTest::class.java.getResourceAsStream("/tls/localhost-test-only.p12").use {
                requireNotNull(it) { "Missing test TLS fixture" }
                load(it, "changeit".toCharArray())
            }
        }
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val clientContext = SSLContext.getInstance("TLS", "SunJSSE").apply { init(null, arrayOf(trustManager), null) }
        private val serverContext = SSLContext.getInstance("TLS", "SunJSSE").apply {
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, "changeit".toCharArray())
            }
            init(keys.keyManagers, null, null)
        }
        private val server = (serverContext.serverSocketFactory.createServerSocket(
            0, 1, InetAddress.getByName("127.0.0.1")) as SSLServerSocket).apply { soTimeout = 5_000 }
        private val reply = FutureTask {
            var requests = 0
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    socket.startHandshake()
                    val reader = socket.inputStream.bufferedReader()
                    if (reader.readLine() != null) {
                        requests++
                        while (!reader.readLine().isNullOrEmpty()) { /* drain request headers */ }
                        val response = if (redirectTo == null) {
                            "HTTP/1.1 200 OK\r\nContent-Length: 6\r\nConnection: close\r\n\r\ntls-ok"
                        } else {
                            "HTTP/1.1 302 Found\r\nLocation: $redirectTo\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        }
                        socket.outputStream.write(response.toByteArray(Charsets.US_ASCII))
                        socket.outputStream.flush()
                    }
                }
            } catch (_: IOException) { /* Rejected TLS/hostnames close the peer without an HTTP request. */ }
            requests
        }
        private val worker = Thread(reply, "source-tls-fixture").apply { isDaemon = true; start() }
        fun url(host: String = "127.0.0.1") = "https://$host:${server.localPort}/data"
        fun requests(): Int = reply.get(6, TimeUnit.SECONDS)
        override fun close() { server.close(); worker.interrupt() }
    }
}
