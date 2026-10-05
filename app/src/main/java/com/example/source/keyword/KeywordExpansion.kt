package com.example.source.keyword

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The original request is already running. Metadata failures never cancel it. */
internal class KeywordExpansion(
    private val local: suspend (String) -> List<KeywordConcept>,
    private val online: suspend (String) -> List<KeywordConcept>,
    private val onlineAllowed: () -> Boolean,
    private val needsRefresh: suspend (String) -> Boolean = { false },
    private val onlineWindowMs: Long = 20_000,
    private val onlineEvents: ((String, List<KeywordConcept>) -> Flow<OnlineKeywordResult>)? = null,
    private val onlineDisabledReason: () -> String = { "在线补充未启用，使用本地关键词" },
) {
    fun expand(input: String): Flow<List<String>> = flow {
        observe(input).collect { if (it.added.isNotEmpty()) emit(it.added) }
    }

    fun observe(input: String): Flow<KeywordUpdate> = flow {
        if (!KeywordKeys.isPlain(input)) {
            emit(KeywordUpdate(status = "保留手动搜索表达式，仅使用原词"))
            return@flow
        }
        emit(KeywordUpdate(status = "正在查找本地名称"))
        var localFailed = false
        suspend fun safeLocal(part: String) = try { local(part) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { localFailed = true; emptyList() }
        val whole = safeLocal(input)
        val split = input.trim().split(Regex("\\s+|[，,、]")).filter { it.isNotBlank() }
        val parts = if (whole.isNotEmpty() || split.size !in 2..4) listOf(input) else split
        val concepts = parts.map { if (parts.size == 1) whole else safeLocal(it) }.toMutableList()
        val seen = linkedSetOf(KeywordKeys.query(input))
        val origins = linkedMapOf<String, String>()
        val missing = parts.indices.filter { !KeywordConcepts.complete(concepts[it]) || needsRefresh(parts[it]) }
        val supplement = missing.isNotEmpty() && onlineAllowed()
        var onlineAdded = false
        var persisted = false
        var cached = concepts.flatten().any { it.source.contains("在线缓存") }
        var failed = false
        var ambiguous = false
        var timedOut = false
        val providers = linkedMapOf<String, KeywordProviderState>()

        suspend fun publish(cap: Int, status: String, origin: String, preferred: List<KeywordName> = emptyList()) {
            val batch = mutableListOf<String>()
            val names = KeywordVariants.prioritized(preferred + KeywordVariants.compose(input, parts, concepts))
            names.forEach { name -> origins.putIfAbsent(KeywordKeys.query(name.text), origin) }
            for (name in names) {
                if (seen.size >= cap) break
                val key = KeywordKeys.query(name.text)
                if (seen.add(key)) {
                    batch += name.text.trim()
                }
            }
            emit(KeywordUpdate(batch, status, origins.filterKeys { it in seen }, providers.values.toList()))
        }
        publish(if (supplement) 4 else KeywordVariants.LIMIT,
            if (supplement) "正在在线补充名称" else if (cached) "已使用缓存" else "已使用本地名称",
            if (cached) "本地 / 缓存" else "本地")

        if (supplement) {
            timedOut = withTimeoutOrNull(onlineWindowMs) {
                coroutineScope {
                    // Each part resolves independently; a slow lookup cannot starve the others.
                    val responses = Channel<Pair<Int, OnlineKeywordResult>>(Channel.BUFFERED)
                    val jobs = missing.map { i -> launch {
                        try {
                            val updates = onlineEvents?.invoke(parts[i], concepts[i]) ?: flow {
                                emit(OnlineKeywordResult(online(parts[i])))
                            }
                            updates.collect { responses.send(i to it) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) {
                            responses.send(i to OnlineKeywordResult(emptyList(), listOf(KeywordProviderState("lookup", "failed"))))
                        }
                    } }
                    launch { jobs.forEach { it.join() }; responses.close() }
                    for ((i, result) in responses) {
                        if (!onlineAllowed()) { jobs.forEach { it.cancel() }; break }
                        result.states.forEach { state -> providers[state.provider] = state }
                        failed = providers.values.any { it.outcome == "failed" }
                        ambiguous = providers.values.any { it.outcome == "ambiguous" }
                        cached = cached || (result.cached && result.concepts.isNotEmpty())
                        persisted = persisted || result.persisted
                        val before = seen.size
                        val merged = KeywordConcepts.merge(concepts[i] + result.concepts)
                        // Fresh metadata receives its reserved slots before surplus local aliases.
                        concepts[i] = merged
                        publish(if (concepts.all(KeywordConcepts::complete)) KeywordVariants.LIMIT else 4, "正在在线补充名称", if (result.cached) "缓存" else "在线",
                            if (parts.size == 1) result.concepts.flatMap { it.names } else emptyList())
                        onlineAdded = onlineAdded || (!result.cached && seen.size > before)
                    }
                }
                true
            } == null
        }
        // Reserved slots can become visible only in the final batch when an entity
        // is still incomplete. Count their online origin before choosing the status.
        onlineAdded = onlineAdded || KeywordVariants.prioritized(KeywordVariants.compose(input, parts, concepts))
            .filter { KeywordKeys.query(it.text) !in seen }.take(KeywordVariants.LIMIT - seen.size)
            .any { origins[KeywordKeys.query(it.text)] == "在线" }
        val state = when {
            supplement && !onlineAllowed() -> onlineDisabledReason()
            timedOut -> if (onlineAdded && persisted) "已补充并缓存，部分在线查询超时" else "在线补充超时，使用已有关键词"
            failed -> if (onlineAdded) if (persisted) "已补充并缓存，部分在线查询失败" else "已补充部分名称，部分在线查询失败" else "在线查询失败，使用已有关键词"
            onlineAdded -> if (persisted) "已在线补充并缓存" else "已在线补充"
            ambiguous -> "名称存在歧义，未自动扩展不确定名称"
            cached -> "已使用缓存"
            !onlineAllowed() && missing.isNotEmpty() -> onlineDisabledReason()
            localFailed && seen.size == 1 -> "本地资料暂不可用，未找到扩展名称，本次仅使用原词"
            supplement -> "未找到更多可靠名称，使用已有关键词"
            seen.size == 1 -> "未找到扩展名称，仅使用原词"
            else -> "已使用本地名称"
        }
        publish(KeywordVariants.LIMIT, state, "本地 / 缓存")
    }
}
