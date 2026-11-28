package com.example.source.impl

/**
 * 线路发现：爱丽丝书屋把域名藏在发布页里——2026-11 起发布页（alicesw.net / alicesw.click /
 * alicesw.quest / 爱丽丝.als1007.space）用 `jump('base64')` 携带真实线路，旧线路页则是
 * `r('倒写字符串')`，另外页面正文里还有明文 https 地址和 `爱丽丝书屋发布地址.txt`。
 * 这里只做纯文本抽取（base64 解码 + 倒写还原 + 正则），不执行页面脚本。
 */
internal object AliceSwDiscovery {

    private val BASE64_JUMP = Regex("""jump\('([A-Za-z0-9+/=]{8,200})'""")
    private val REVERSED_LITERAL = Regex("""r\('([^']{8,120})'\)""")
    private val HTTPS_HOST = Regex("""https://([a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+)""")

    /** 从发布页/线路页抽候选域名：base64 跳转 + 倒写字符串 + 明文 https 地址。 */
    fun hosts(html: String): List<String> {
        val found = ArrayList<String>()
        for (match in BASE64_JUMP.findAll(html)) {
            val decoded = runCatching {
                String(java.util.Base64.getDecoder().decode(match.groupValues[1]))
            }.getOrNull() ?: continue
            HTTPS_HOST.find(decoded)?.let { found.add(it.groupValues[1]) }
        }
        for (match in REVERSED_LITERAL.findAll(html)) {
            val reversed = match.groupValues[1].reversed()
            HTTPS_HOST.find(reversed)?.let { found.add(it.groupValues[1]) }
        }
        for (match in HTTPS_HOST.findAll(html)) {
            found.add(match.groupValues[1])
        }
        val result = ArrayList<String>()
        for (raw in found) {
            val host = raw.lowercase().removePrefix("www.")
            if (host.startsWith("alicesw") && host.contains('.') && !result.contains(host)) result.add(host)
        }
        return result
    }
}
