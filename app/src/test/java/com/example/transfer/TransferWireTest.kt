package com.example.transfer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[33])
class TransferWireTest {
    @Test fun androidTwelveRequestsBothLocationPermissionsAndNewerUsesNearbyOnly() {
        assertEquals(setOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION),TransferDiscovery.permissionsFor(31).toSet())
        assertEquals(setOf(android.Manifest.permission.NEARBY_WIFI_DEVICES),TransferDiscovery.permissionsFor(33).toSet())
    }
    @Test fun loopbackCarriesFullChunkAndTailAndAcknowledgesOnlyVerifiedOffsets() {
        val bytes=ByteArray(CHUNK_SIZE+317){(it%251).toByte()}
        val executor=Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            val received=executor.submit<ByteArray> {
                server.accept().use { socket ->
                    socket.soTimeout=5000
                    val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
                    val result=ByteArrayOutputStream();var offset=0L
                    while(offset<bytes.size) {
                        val header=TransferWire.read(input);val chunk=ByteArray(header.getInt("length"));input.readFully(chunk)
                        TransferWire.verifyChunk(header,offset,bytes.size.toLong(),chunk,chunk.size)
                        result.write(chunk);offset+=chunk.size
                        TransferWire.write(output,JSONObject().put("type","ack").put("offset",offset))
                    };result.toByteArray()
                }
            }
            Socket("127.0.0.1",server.localPort).use { socket ->
                socket.soTimeout=5000
                val input=DataInputStream(socket.getInputStream());val output=DataOutputStream(socket.getOutputStream())
                var offset=0
                while(offset<bytes.size) {
                    val end=minOf(offset+CHUNK_SIZE,bytes.size);val chunk=bytes.copyOfRange(offset,end)
                    TransferWire.write(output,JSONObject().put("type","chunk").put("offset",offset).put("length",chunk.size).put("sha256",sha256(chunk)))
                    output.write(chunk);output.flush()
                    assertEquals(end.toLong(),TransferWire.read(input).getLong("offset"));offset=end
                }
            }
            assertArrayEquals(bytes,received.get(10,java.util.concurrent.TimeUnit.SECONDS))
        }
        executor.shutdownNow()
    }
    @Test fun tamperedOrReorderedChunkCannotBeAcknowledged() {
        val bytes=ByteArray(15){it.toByte()}
        val header=JSONObject().put("type","chunk").put("offset",0).put("length",15).put("sha256",sha256(bytes))
        val damaged=bytes.copyOf().also {it[4]=99}
        assertThrows(IllegalArgumentException::class.java){TransferWire.verifyChunk(header,0,15,damaged,15)}
        assertThrows(IllegalArgumentException::class.java){TransferWire.verifyChunk(header,1,16,bytes,15)}
    }
    @Test fun oversizedAndTruncatedFramesFailBeforePayloadIsAccepted() {
        val oversized=ByteArrayOutputStream().also {DataOutputStream(it).writeInt(Int.MAX_VALUE)}.toByteArray()
        assertThrows(IllegalArgumentException::class.java){TransferWire.read(DataInputStream(ByteArrayInputStream(oversized)))}
        val truncated=ByteArrayOutputStream().also {val out=DataOutputStream(it);out.writeInt(32);out.write(byteArrayOf(1,2))}.toByteArray()
        assertThrows(EOFException::class.java){TransferWire.read(DataInputStream(ByteArrayInputStream(truncated)))}
    }
}
