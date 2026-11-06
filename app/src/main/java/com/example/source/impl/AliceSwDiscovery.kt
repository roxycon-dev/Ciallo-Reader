package com.example.source.impl

/**
 * 线路发现：爱丽丝书屋只把域名写在发布页和线路页里，且两处都不直接给链接——
 * 线路页把域名倒着写进 `r('semoh.2wsecila.www//:sptth')`，跳转页则塞在带
 * `alicesw` 的明文 URL 里。这里只做纯文本抽取，不执行页面脚本。
 */
internal object AliceSwDiscovery {

    private val REVERSED_LITERAL = Regex("""r\('([^']{8,120})'\)""")
    private val HTTPS_HOST = Regex("""https://([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)""")

    /** 从线路页/跳转页抽候选域名：倒写字符串 + 明文 https 地址，只保留站点自家域名。 */
    fun hosts(html: String): List<String> {
        val found = ArrayList<String>()
        val reversedMatches = REVERSED_LITERAL.findAll(html)
        for (match in reversedMatches) {
            val reversed = match.groupValues[1].reversed()
            val hostMatch = HTTPS_HOST.find(reversed) ?: continue
            found.add(hostMatch.groupValues[1])
        }
        val plainMatches = HTTPS_HOST.findAll(html)
        for (match in plainMatches) {
            found.add(match.groupValues[1])
        }
        val result = ArrayList<String>()
        for (raw in found) {
            val host = raw.lowercase().removePrefix("www.")
            if (host.startsWith("alicesw") && !result.contains(host)) result.add(host)
        }
        return result
    }
}
