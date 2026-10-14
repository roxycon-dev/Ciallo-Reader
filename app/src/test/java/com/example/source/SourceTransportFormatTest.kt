package com.example.source

import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

class SourceTransportFormatTest {
    @Test fun `mixed web accept header must not reject normal HTML directories`() {
        assertFalse(SourceResponse.expectsJson(mapOf("Accept" to "text/html,application/json;q=0.9,*/*;q=0.8")))
        assertTrue(SourceResponse.expectsJson(mapOf("accept" to "application/json, text/plain, */*")))
        val html = "<html><title>爱漫画目录</title></html>"
        assertNull(SourceResponse.problem(html, SourceResponse.expectsJson(mapOf("Accept" to "text/html,application/json;q=0.9"))))
        assertNotNull(SourceResponse.problem(html, true))
    }
    @Test fun `script advertised Brotli and zstd cannot leak compressed bytes into parsers`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val answer = FutureTask {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
                    val packed = ByteArrayOutputStream().also { buffer ->
                        GZIPOutputStream(buffer).use { it.write("{\"title\":\"测试\"}".toByteArray()) }
                    }.toByteArray()
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
                            "Content-Encoding: gzip\r\nContent-Length: ${packed.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        write(packed); flush()
                    }
                    headers
                }
            }
            val worker = Thread(answer, "source-compression-fixture").apply { isDaemon = true; start() }
            try {
                val request = Request.Builder().url("http://127.0.0.1:${server.localPort}/data")
                    .header("Accept-Encoding", "gzip, deflate, br, zstd").build()
                SharedHttpTransport.builder().callTimeout(5, TimeUnit.SECONDS).build().newCall(request).execute().use {
                    assertEquals("{\"title\":\"测试\"}", it.readSourceText())
                    assertNull(it.header("Content-Encoding"))
                }
                assertTrue(answer.get(5, TimeUnit.SECONDS).any { it.equals("Accept-Encoding: gzip", true) })
            } finally { worker.interrupt() }
        }
    }
}
