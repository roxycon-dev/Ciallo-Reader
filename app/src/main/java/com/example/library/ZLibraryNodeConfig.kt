package com.example.library

/**
 * 当前使用的 Z-Library 节点（可在诊断页一键切换）。
 * 书库隐藏会话实时读取该值，切换后立即生效。
 *
 * 初始值为空串 =「尚未恢复用户选择」：App 启动时 MainActivity 调
 * [ZLibraryNodeManager.restoreSelection] 从 SharedPreferences 恢复。
 * 修复（v1.0.1 整合适配）：原初始值 "z-library.sk" 是已死域名，且在恢复前
 * 会遮蔽缓存/自定义端点的优先级（ZLibraryEndpointProvider.getEndpoint 第 0 步）。
 */
object ZLibraryNodeConfig {
    @Volatile
    var domain: String = ""

    private val directNodes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun prefersDirect(host: String): Boolean = host in directNodes
    fun rememberRoute(host: String, direct: Boolean) {
        if (direct) directNodes.add(host) else directNodes.remove(host)
    }
}
