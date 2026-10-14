package com.example.source.js

import okhttp3.OkHttpClient
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Short renewal grace for the publisher's known public reading key; hostname checks stay enabled. */
internal object AimanhuaTls {
    const val PIN = "84f816f39fb638fe06c65f5489ef9be3ed93ab195a56ff1ba014dfe000869729"
    fun route(source: String, url: String) = source == "aimanhua" &&
        runCatching { java.net.URI(url).host == "mh.iqtao.cn" }.getOrDefault(false)
    fun accepts(cert: X509Certificate, now: Long = System.currentTimeMillis()): Boolean {
        val key = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded).joinToString("") { "%02x".format(it) }
        return key == PIN && now >= cert.notBefore.time && now <= cert.notAfter.time + 14L * 86_400_000
    }
    fun configure(builder: OkHttpClient.Builder, rootCertificate: InputStream? = null) {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val system = factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        // Official ISRG Root X2 bridges older Android root stores. Full chain/date checks
        // still apply, so ordinary renewals and new public keys need no app update.
        val publisherCa = rootCertificate?.let { stream ->
            val roots = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("isrg-root-x2", CertificateFactory.getInstance("X.509").generateCertificate(stream))
            }
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(roots) }
                .trustManagers.filterIsInstance<X509TrustManager>().single()
        }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = system.acceptedIssuers + publisherCa?.acceptedIssuers.orEmpty()
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = system.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                try { system.checkServerTrusted(chain, authType) }
                catch (error: CertificateException) {
                    if (publisherCa != null) {
                        try { publisherCa.checkServerTrusted(chain, authType); return }
                        catch (_: CertificateException) { /* Only the known key has a bounded expiry grace. */ }
                    }
                    if (chain.isEmpty() || !accepts(chain[0])) throw error
                }
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        builder.sslSocketFactory(tls.socketFactory, trust)
    }
}
