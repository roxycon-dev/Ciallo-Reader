package com.example.transfer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Self-signed device certificates are authenticated by an explicit, transcript-bound human comparison. */
internal object TransferTls {
    private const val ALIAS = "ciallo_transfer_identity_v1"
    @Synchronized fun context(): SSLContext {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Ciallo device"))
                    .setCertificateNotAfter(java.util.Date(4102444800000L)).build())
                generateKeyPair()
            }
        }
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,null) }
        val pairingTrust=object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            private fun validate(chain: Array<out X509Certificate>?) {
                require(chain?.size==1) { "无效设备证书" }
                val cert=chain!![0]; cert.checkValidity(); cert.verify(cert.publicKey)
                require(cert.publicKey.encoded.size>=128) { "设备证书强度不足" }
            }
            override fun checkClientTrusted(chain: Array<out X509Certificate>?,authType:String?) = validate(chain)
            override fun checkServerTrusted(chain: Array<out X509Certificate>?,authType:String?) = validate(chain)
        }
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers,arrayOf<TrustManager>(pairingTrust),SecureRandom()) }
    }
    fun configure(socket: SSLSocket) {
        socket.enabledProtocols=socket.supportedProtocols.filter { it in setOf("TLSv1.2","TLSv1.3") }.toTypedArray()
        socket.soTimeout=30_000; socket.tcpNoDelay=true
    }
    fun peerFingerprint(socket: SSLSocket): String = sha256(socket.session.peerCertificates.first().encoded)
    fun code(socket: SSLSocket,localNonce:String,remoteNonce:String): String {
        val certs=listOf(sha256(socket.session.localCertificates.first().encoded),peerFingerprint(socket)).sorted()
        val nonces=listOf(localNonce,remoteNonce).sorted()
        val bytes=java.security.MessageDigest.getInstance("SHA-256").digest((certs+nonces).joinToString("|").toByteArray())
        val number=java.nio.ByteBuffer.wrap(bytes).int.toLong() and 0xffffffffL
        return (number%1_000_000).toString().padStart(6,'0')
    }
}
