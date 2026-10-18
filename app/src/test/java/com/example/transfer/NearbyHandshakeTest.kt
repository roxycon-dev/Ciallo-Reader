package com.example.transfer

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NearbyHandshakeTest {
    @Test fun eitherGroupRoleCanOwnTheSendTask() {
        for(ownerSends in listOf(true,false)) {
            val owner=NearbyOffer("owner","phone",ownerSends)
            val client=NearbyOffer("client","phone",!ownerSends)
            assertTrue(NearbyHandshake.shouldStart(owner,client));assertTrue(NearbyHandshake.shouldStart(client,owner))
        }
        assertFalse(NearbyHandshake.shouldStart(NearbyOffer("a","a",false),NearbyOffer("b","b",false)))
        assertFalse(NearbyHandshake.shouldStart(NearbyOffer("a","a",true),NearbyOffer("b","b",true)))
        assertFalse(NearbyHandshake.shouldStart(NearbyOffer("a","a",true),NearbyOffer("b","b",false,busy=true)))
    }
    @Test fun metadataRoundTripsAndRejectsUnknownOrOversizedFrames() {
        val offer=NearbyOffer("owner","接收设备",true)
        val bytes=ByteArrayOutputStream().also {NearbyHandshake.write(DataOutputStream(it),offer)}.toByteArray()
        assertEquals(offer,NearbyHandshake.read(DataInputStream(bytes.inputStream())))
        for(bad in listOf(bytes.copyOf(3),bytes.copyOf().also {it[4]=99},bytes.copyOf().also {it[5]=127})) {
            assertTrue(runCatching {NearbyHandshake.read(DataInputStream(bad.inputStream()))}.isFailure)
        }
    }
    @Test fun idleAndBusyProbesNeverAcquireATaskOrInitializeTls() {
        for(busy in listOf(false,true)) {
            val executor=Executors.newSingleThreadExecutor()
            ServerSocket(0).use {server ->
                val result=executor.submit<Boolean> {
                    TransferConnection.acceptNearby({error("No TLS for an idle probe")},server.accept(),
                        offer={NearbyOffer("owner","phone",false,busy)},claim={error("No task claim for an idle probe")})==null
                }
                val client=TransferConnection.connectNearby({error("No TLS for an idle probe")},TransferRoute("127.0.0.1",server.localPort,localAddress="127.0.0.1",nearby=true),
                    NearbyOffer("client","phone",false),"owner",claim={error("No task claim for an idle probe")})
                assertNull(client);assertTrue(result.get(3,TimeUnit.SECONDS));executor.shutdownNow()
            }
        }
    }
    @Test fun nearbyIdentityMismatchClosesBeforeTls() {
        val executor=Executors.newSingleThreadExecutor()
        ServerSocket(0).use {server ->
            val response=executor.submit<Unit> {server.accept().use {raw ->
                NearbyHandshake.read(DataInputStream(raw.inputStream))
                NearbyHandshake.write(DataOutputStream(raw.outputStream),NearbyOffer("another","phone",false))
            }}
            try {
                TransferConnection.connectNearby({error("No TLS")},TransferRoute("127.0.0.1",server.localPort),NearbyOffer("client","phone",true),"selected",claim={error("No claim")})
                fail("Wrong device must be rejected")
            } catch(_:SecurityException){} finally {response.get(3,TimeUnit.SECONDS);executor.shutdownNow()}
        }
    }
}
