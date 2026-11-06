package com.example.source.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 线路发现解析（真实线路页快照）：页面把域名倒着写进 `r('...')`，
 * 站点换域名时只要发布页/线路页还通，就能自动跟上。
 */
class AliceSwDiscoveryTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/alicesw/$name")) { "缺少测试资源 $name" }
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
    fun `unrelated page yields no hosts`() {
        assertTrue(AliceSwDiscovery.hosts("<html><body>Hello</body></html>").isEmpty())
    }
}
