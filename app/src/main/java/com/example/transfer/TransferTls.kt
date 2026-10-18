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
import javax.net.ssl.X509KeyManager

/** Self-signed device certificates are authenticated by an explicit, transcript-bound human comparison. */
internal object TransferTls {
    // Earlier identities omitted raw RSA operations used by Android's TLS provider.
    internal const val ALIAS = "ciallo_transfer_identity_v3"
    internal val identityDigests = arrayOf(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256,KeyProperties.DIGEST_SHA384,KeyProperties.DIGEST_SHA512)
    @Synchronized fun context(alias:String=ALIAS): SSLContext {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if(!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048).setDigests(*identityDigests)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE,KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setCertificateSubject(javax.security.auth.x500.X500Principal("CN=Ciallo device"))
                    .setCertificateNotAfter(java.util.Date(4102444800000L)).build())
                generateKeyPair()
            }
        }
        val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store,null) }
        // AndroidKeyStore contains other app keys (and v1). Never let TLS choose one of those aliases.
        val delegate=keys.keyManagers.filterIsInstance<X509KeyManager>().first()
        val identity=object:X509KeyManager {
            override fun getClientAliases(type:String?,issuers:Array<out java.security.Principal>?)=
                delegate.getClientAliases(type,issuers)?.filter {it==alias}?.toTypedArray()
            override fun getServerAliases(type:String?,issuers:Array<out java.security.Principal>?)=
                delegate.getServerAliases(type,issuers)?.filter {it==alias}?.toTypedArray()
            override fun chooseClientAlias(types:Array<out String>?,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?)=
                types?.firstNotNullOfOrNull {getClientAliases(it,issuers)?.firstOrNull()}
            override fun chooseServerAlias(type:String?,issuers:Array<out java.security.Principal>?,socket:java.net.Socket?)=
                getServerAliases(type,issuers)?.firstOrNull()
            override fun getCertificateChain(requested:String?)=if(requested==alias)delegate.getCertificateChain(alias)else null
            override fun getPrivateKey(requested:String?)=if(requested==alias)delegate.getPrivateKey(alias)else null
        }
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
        return SSLContext.getInstance("TLS").apply { init(arrayOf(identity),arrayOf<TrustManager>(pairingTrust),SecureRandom()) }
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
