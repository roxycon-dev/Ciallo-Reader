package com.example.transfer

import android.security.keystore.KeyInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs against Android's real Keystore and Conscrypt, rather than JVM stand-ins. */
@RunWith(AndroidJUnit4::class)
class TransferTlsDeviceTest {
    @Test fun nearbyRendezvousCarriesDataWithEitherGroupOwnerRoleAndAfterAnIdleProbe() {
        val clientAlias="ciallo_nearby_test_client"
        val ownerAlias="ciallo_nearby_test_owner"
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val executor=Executors.newSingleThreadExecutor()
        try {
            val clientContext=TransferTls.context(clientAlias);val ownerContext=TransferTls.context(ownerAlias)
            for(ownerSends in listOf(true,false)) {
                val payload=ByteArray(CHUNK_SIZE+193){(it%239).toByte()}
                ServerSocket(0).use {server ->
                    val response=executor.submit<String> {
                        assertNull(TransferConnection.acceptNearby({error("Idle probe must not initialize TLS")},server.accept(),
                            offer={NearbyOffer("owner","owner",false)},claim={error("Idle probe must not claim")}))
                        val link=TransferConnection.acceptNearby({ownerContext},server.accept(),
                            offer={NearbyOffer("owner","owner",ownerSends)},claim={true})!!
                        link.socket.use {tls ->
                            assertEquals(!ownerSends,link.remote.sending)
                            val input=DataInputStream(tls.inputStream);val output=DataOutputStream(tls.outputStream)
                            if(ownerSends){output.writeInt(payload.size);output.write(payload);output.flush()}
                            else {val bytes=ByteArray(input.readInt());input.readFully(bytes);assertArrayEquals(payload,bytes)}
                            val code=TransferTls.code(tls,"owner-nonce","client-nonce")
                            output.writeUTF(code);output.flush()
                            code
                        }
                    }
                    val route=TransferRoute("127.0.0.1",server.localPort,localAddress="127.0.0.1",nearby=true)
                    assertNull(TransferConnection.connectNearby({error("Idle probe must not initialize TLS")},route,
                        NearbyOffer("client","client",false),"owner",claim={error("Idle probe must not claim")}))
                    val link=TransferConnection.connectNearby({clientContext},route,NearbyOffer("client","client",!ownerSends),"owner",claim={true})!!
                    link.socket.use {tls ->
                        assertEquals(ownerSends,link.remote.sending)
                        val input=DataInputStream(tls.inputStream);val output=DataOutputStream(tls.outputStream)
                        if(ownerSends){val bytes=ByteArray(input.readInt());input.readFully(bytes);assertArrayEquals(payload,bytes)}
                        else {output.writeInt(payload.size);output.write(payload);output.flush()}
                        val code=input.readUTF()
                        assertEquals(code,TransferTls.code(tls,"client-nonce","owner-nonce"))
                        assertEquals(code,response.get(15,TimeUnit.SECONDS))
                    }
                }
            }
        } finally {executor.shutdownNow();store.deleteEntry(clientAlias);store.deleteEntry(ownerAlias)}
    }
    @Test fun nativeKeystoreAuthorizesTlsSigning() {
        TransferTls.context()
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        val key=store.getKey(TransferTls.ALIAS,null)
        val info=KeyFactory.getInstance(key.algorithm,"AndroidKeyStore").getKeySpec(key,KeyInfo::class.java)
        assertTrue(info.digests.contains(android.security.keystore.KeyProperties.DIGEST_NONE))
        assertTrue(info.encryptionPaddings.contains(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE))
    }

    @Test fun nativeMutualTlsExchangesPayloadAndUsesOnlyTheSelectedIdentity() {
        val clientAlias="ciallo_transfer_test_client"
        val serverAlias="ciallo_transfer_test_server"
        val executor=Executors.newSingleThreadExecutor()
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        try {
            val clientContext=TransferTls.context(clientAlias)
            val serverContext=TransferTls.context(serverAlias)
            val expectedClient=sha256(store.getCertificate(clientAlias).encoded)
            val expectedServer=sha256(store.getCertificate(serverAlias).encoded)
            val payload=ByteArray(CHUNK_SIZE+137){(it%251).toByte()}
            ServerSocket(0).use {server ->
                val response=executor.submit<String> {
                    try {
                    TransferConnection.accept(serverContext,server.accept()).use {tls ->
                        assertEquals(expectedClient,TransferTls.peerFingerprint(tls))
                        val input=DataInputStream(tls.inputStream)
                        val received=ByteArray(input.readInt());input.readFully(received)
                        assertArrayEquals(payload,received)
                        val code=TransferTls.code(tls,"client-nonce","server-nonce")
                        DataOutputStream(tls.outputStream).apply {writeUTF(code);flush()}
                        code
                    }
                    } catch(error:Throwable){android.util.Log.e("CialloTransferTest","Server TLS failed",error);throw error}
                }
                TransferConnection.connect(clientContext,TransferRoute("127.0.0.1",server.localPort)).use {tls ->
                    assertEquals(expectedServer,TransferTls.peerFingerprint(tls))
                    DataOutputStream(tls.outputStream).apply {writeInt(payload.size);write(payload);flush()}
                    val code=DataInputStream(tls.inputStream).readUTF()
                    assertEquals(code,TransferTls.code(tls,"server-nonce","client-nonce"))
                    assertEquals(code,response.get(15,TimeUnit.SECONDS))
                }
            }
        } finally {
            executor.shutdownNow()
            store.deleteEntry(clientAlias);store.deleteEntry(serverAlias)
        }
    }
}
