package com.example.source.anilist

/**
 * AniList 本地多语言标题数据库的实体与 DAO 约定（第七轮第 7 条）。
 *
 * 设计要点（对应规范第 2/3/13 点）：
 * - 只保存"用于标题匹配"的最小必要数据：Media ID + 标题类型 + 原文 + 两个归一化形态；
 *   不存封面、简介、角色、评分等无关数据；
 * - 每部作品的每个标题一行（romaji/english/native/synonyms 展开为多行），
 *   查询 = SQL + 索引命中，整个数据集从不整体加载进内存常驻；
 * - (mediaId, titleType, rawTitle) 唯一索引 + IGNORE 冲突策略：同步可安全重放。
 */
data class AniListTitleRow(
    val mediaId: Int,
    val titleType: String,      // ROMAJI / ENGLISH / NATIVE / SYNONYM
    val rawTitle: String,
    val normalizedTitle: String,
    val compactTitle: String,
)

/** 标题类型常量 */
object AniListTitleType {
    const val ROMAJI = "ROMAJI"
    const val ENGLISH = "ENGLISH"
    const val NATIVE = "NATIVE"
    const val SYNONYM = "SYNONYM"
}

/** 一页同步结果（GraphQL 响应的最小映射） */
data class AniListSyncPage(
    val media: List<AniListMediaTitles>,
    val hasMore: Boolean,
)

/** 单部作品的全部标题（缺失字段直接跳过——规范第 12 点） */
data class AniListMediaTitles(
    val mediaId: Int,
    val romaji: String?,
    val english: String?,
    val native: String?,
    val synonyms: List<String>,
) {
    /** 展开为行（空值字段跳过；归一化在入库前完成） */
    fun toRows(): List<AniListTitleRow> {
        val rows = ArrayList<AniListTitleRow>(4 + synonyms.size)
        fun add(type: String, raw: String?) {
            if (raw.isNullOrBlank()) return
            rows.add(
                AniListTitleRow(
                    mediaId = mediaId,
                    titleType = type,
                    rawTitle = raw.trim(),
                    normalizedTitle = TitleNormalizer.normalize(raw),
                    compactTitle = TitleNormalizer.compact(raw),
                )
            )
        }
        add(AniListTitleType.ROMAJI, romaji)
        add(AniListTitleType.ENGLISH, english)
        add(AniListTitleType.NATIVE, native)
        synonyms.forEach { add(AniListTitleType.SYNONYM, it) }
        // 归一化后为空的行没有匹配价值
        return rows.filter { it.normalizedTitle.isNotEmpty() }
    }
}

/**
 * 搜索变体构建（纯函数，可单测——对应规范第 7 点的搜索流程）：
 * 原始关键词始终保留在首位；本地标题库命中的其它语言标题追加其后；
 * 去重（归一化级）+ 数量上限（防一次搜索扇出过大）。
 */
object SearchVariantBuilder {

    // 第十一轮第 6 条：5 → 6——原始词 + 原名/英文/罗马音 + 1 个同义标题，
    // 保证主作品三种官方书写都发给各书源（原先 5 个在命中多部作品时会被衍生作挤占）
    const val MAX_VARIANTS = 6

    /**
     * @param originalKeyword 用户原始输入
     * @param matchedRawTitles 本地库命中的作品原始标题（任意语言）
     * @return 去重后的搜索变体列表（首位 = 原始输入）
     */
    fun build(originalKeyword: String, matchedRawTitles: List<String>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>(MAX_VARIANTS)
        fun offer(candidate: String, original: Boolean = false) {
            if (out.size >= MAX_VARIANTS) return
            val trimmed = candidate.trim()
            if (trimmed.isEmpty()) return
            if (!original && !TitleNormalizer.usableVariant(trimmed)) return
            val key = com.example.source.keyword.KeywordKeys.query(trimmed)
            if (key.isEmpty() || !seen.add(key)) return
            out.add(trimmed)
        }
        offer(originalKeyword, original = true)
        // 原始关键词若自身过长截断保护（书源 URL 长度）
        matchedRawTitles.forEach { offer(it) }
        return out
    }
}
