package com.example.source

/**
 * A book source that can browse online comics chapter by chapter.
 */
interface ComicSource : BookSource {
    suspend fun discoverCatalog(resolveDynamic: Boolean = false): SourceResult<com.example.discover.DiscoverCatalog> =
        SourceResult.Success(com.example.discover.DiscoverCatalog())
    suspend fun discover(query: com.example.discover.DiscoverQuery): SourceResult<com.example.discover.DiscoverPage> =
        SourceResult.Success(com.example.discover.DiscoverPage(supported = false, notice = "该书源未提供此维度数据"))
    suspend fun getChapters(bookId: String): SourceResult<List<ComicChapter>>
    suspend fun getChapterImages(chapterId: String): SourceResult<List<String>>

    /** 章节式文字源：返回章节正文纯文本（段落以 \n\n 分隔）。图片源默认不支持。 */
    suspend fun getChapterText(chapterId: String): SourceResult<String> =
        SourceResult.Error(SourceException.ParseError("该书源不支持文字章节"))

    /**
     * 每个图片 URL 对应的额外请求头（Referer/Cookie/签名等）。
     * 默认无；JS 源通过 onImageLoad 提供。
     */
    suspend fun getChapterImageHeaders(
        chapterId: String,
        urls: List<String>
    ): Map<String, Map<String, String>> = emptyMap()

    /**
     * 懒加载解析：把源返回的“图片页 URL”解析成真实图片 URL（e-hentai 等），
     * 阅读器/下载器在真正加载某一页时按需调用并缓存。默认不支持。
     */
    suspend fun resolveChapterImage(url: String): String? = null

    /** 懒加载解析后，真实图片 URL 需要的请求头。 */
    suspend fun getResolvedHeaders(url: String): Map<String, String> = emptyMap()

    /**
     * 封面图请求头（Referer/Cookie 等）。JS 源通过 onThumbnailLoad 提供。
     */
    suspend fun getCoverHeaders(url: String): Map<String, String> = emptyMap()
}
