package com.example.source.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路发现解析：旧线路页把域名倒写进 `r('...')`，2026-11 起的发布页把线路塞进
 * `jump('base64')`，另附明文发布 txt。站点换域名时只要发布页还通就能自动跟上。
 */
class AliceSwDiscoveryTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw-net/$name")) { "缺少测试资源 $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `line page yields the rotating mirror hosts`() {
        val hosts = AliceSwDiscovery.hosts(resource("linepage.html"))
        assertTrue("应解析出线路主机：$hosts", hosts.containsAll(
            listOf("alicesw2.homes", "alicesw3.homes", "alicesw4.homes")
        ))
        assertTrue(hosts.all { it.startsWith("alicesw") })
        assertEquals(hosts.size, hosts.distinct().size)
    }

    @Test
    fun `publish page yields permanent domains from base64 jump`() {
        val hosts = AliceSwDiscovery.hosts(resource("publish_net.html"))
        assertTrue("应解码出永久地址：$hosts", hosts.contains("alicesw.com"))
        assertTrue("应从 ld+json 抽出发布页自身域名：$hosts", hosts.contains("alicesw.click"))
        assertTrue(hosts.all { it.startsWith("alicesw") })
        assertEquals(hosts.size, hosts.distinct().size)
    }

    @Test
    fun `publish txt yields mainland lines`() {
        val hosts = AliceSwDiscovery.hosts(resource("publish_txt.txt"))
        assertTrue("应从发布 txt 解析出官方线路：$hosts",
            hosts.containsAll(listOf("alicesw.com", "alicesw.quest", "alicesw1.homes")))
    }

    @Test
    fun `unrelated page yields no hosts`() {
        assertTrue(AliceSwDiscovery.hosts("<html><body>Hello</body></html>").isEmpty())
    }
}
