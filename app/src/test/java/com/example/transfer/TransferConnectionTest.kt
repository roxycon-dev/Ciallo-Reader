package com.example.transfer

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class TransferConnectionTest {
    @Test fun silentPeerTimesOutDuringHandshakeAndClosesBothSocketLayers() {
        val executor=Executors.newSingleThreadExecutor()
        val tracked=mutableListOf<Socket>()
        ServerSocket(0).use {server ->
            val accepted=executor.submit<Socket> {server.accept()}
            val stages=mutableListOf<String>()
            val start=System.nanoTime()
            try {
                TransferConnection.connect(SSLContext.getDefault(),TransferRoute("127.0.0.1",server.localPort),
                    bind={assertFalse(it is SSLSocket);assertFalse(it.isConnected)},
                    track={tracked.add(it)},stage={stages.add(it)},connectTimeout=500,handshakeTimeout=200)
                fail("A silent peer must not leave the task connecting")
            } catch(expected:IOException) {
                assertTrue(expected.message.orEmpty().contains("加密握手失败"))
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<5_000)
                assertEquals(listOf("正在连接设备","正在建立加密连接"),stages)
                assertTrue(tracked.all {it.isClosed})
            } finally {accepted.get(2,TimeUnit.SECONDS).close();executor.shutdownNow()}
        }
    }

    @Test fun closingTrackedSocketCancelsAnInProgressHandshake() {
        val executor=Executors.newFixedThreadPool(2)
        val ready=java.util.concurrent.CountDownLatch(1)
        val tracked=java.util.concurrent.atomic.AtomicReference<Socket>()
        ServerSocket(0).use {server ->
            val accepted=executor.submit<Socket> {server.accept()}
            val attempt=executor.submit<Boolean> {
                try {
                    TransferConnection.connect(SSLContext.getDefault(),TransferRoute("127.0.0.1",server.localPort),
                        track={tracked.set(it);if(it is SSLSocket)ready.countDown()})
                    false
                } catch(_:IOException){true}
            }
            try {
                assertTrue(ready.await(3,TimeUnit.SECONDS))
                tracked.get().close()
                assertTrue(attempt.get(3,TimeUnit.SECONDS))
            } finally {accepted.get(2,TimeUnit.SECONDS).close();executor.shutdownNow()}
        }
    }

    @Test fun discoveryKeepsDirectPortSeparateAndUsesTheNetworksAddressSet() {
        val nearby=TransferPeer("a","phone",port=39001,p2pAddress="aa",directPort=39001)
        val lan=TransferPeer("a","phone","fe80::2%wlan0",39002,routes=listOf(
            TransferRoute("fe80::2%wlan0",39002,7),TransferRoute("192.168.1.8",39002,7)))
        val merged=mergePeer(nearby,lan)
        assertEquals(39001,merged.directPort)
        assertEquals("aa",merged.p2pAddress)
        assertEquals(listOf("192.168.1.8","fe80::2%wlan0"),connectionRoutes(merged).map {it.host})
        assertEquals(7L,connectionRoutes(merged).first().networkHandle)
        val updated=mergePeer(merged,lan.copy(host="192.168.1.9",routes=listOf(TransferRoute("192.168.1.9",39002,7))))
        assertEquals(listOf("192.168.1.9"),connectionRoutes(updated).map {it.host})
        assertEquals(39001,mergePeer(updated,nearby).directPort)
        assertEquals("192.168.1.9",mergePeer(updated,nearby).host)
    }
}
