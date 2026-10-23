package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.regex.Pattern

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
    // 迁移事务用的 DB；生产传 null 走 getDatabase 单例（与 bookDao 同源），测试注入 in-memory
    private val database: AppDatabase? = null
) {
    private val progressWriteMutex = Mutex()

    // 无阅读管线的格式（PDF 不在此列：走 ComicParser 逐页渲染）
    private val unsupportedBinaryExtensions = listOf(
        ".kfx", ".djvu",
        ".doc", ".rtf", ".chm"
    )

    val allBooks: Flow<List<Book>> = bookDao.getAllBooks().distinctUntilChanged()
    val allCategories: Flow<List<CategoryEntity>> = bookDao.getAllCategories().distinctUntilChanged()
    val allReadingRecords: Flow<List<ReadingRecord>> = bookDao.getAllReadingRecordsFlow().distinctUntilChanged()
    val allReadingSessions: Flow<List<ReadingSession>> = bookDao.getAllReadingSessionsFlow().distinctUntilChanged()

    private fun detectCharset(context: Context, uri: Uri): java.nio.charset.Charset {
        // 采样更大范围（256KB），并做严格解码校验 + BOM 识别，
        // 避免“文件前半段是 ASCII 后半段是 GBK”时被误判成 UTF-8 导致乱码。
        val buffer = ByteArray(256 * 1024)
        var bytesRead = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                bytesRead = input.read(buffer)
            }
        } catch (e: Exception) {
            android.util.Log.e("BookImport", "Error reading bytes for encoding detection", e)
        }

        if (bytesRead <= 0) return java.nio.charset.StandardCharsets.UTF_8

        val data = if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer

        // BOM 优先
        if (data.size >= 3 && data[0] == 0xEF.toByte() && data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()) {
            return java.nio.charset.StandardCharsets.UTF_8
        }
        if (data.size >= 2 &&
            ((data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) ||
                (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()))
        ) {
            return java.nio.charset.Charset.forName(
                if (data[0] == 0xFF.toByte()) "UTF-16LE" else "UTF-16BE"
            )
        }

        return CharsetSniffer.detect(data, bytesRead < buffer.size)
    }

    private fun hasChapterTitles(context: Context, uri: Uri, charset: java.nio.charset.Charset): Boolean {
        val chapterPattern = Pattern.compile("^第[0-9一二三四五六七八九十百千万]+[章回卷节\\s].*")
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, charset)).use { reader ->
                    var line: String?
                    var lineCount = 0
                    while (reader.readLine().also { line = it } != null) {
                        lineCount++
                        val cleanLine = line?.trim() ?: ""
                        if (cleanLine.isNotEmpty() && chapterPattern.matcher(cleanLine).matches()) {
                            android.util.Log.d("BookImport", "Found chapter pattern at line $lineCount: $cleanLine")
                            return@hasChapterTitles true
                        }
                        if (lineCount > 20000) break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BookImport", "Error scanning for chapter titles", e)
        }
        return false
    }

    /* ───────── 老书内嵌图片懒迁移 ─────────
     * 旧版本导入的本地书不提取内嵌图片（EPUB/FB2/DOCX/MOBI 的 <img> 直接被丢弃）。
     * 打开书时懒迁移：先廉价嗅探源文件格式（且 EPUB/DOCX 需真的带图），再用对应解析器
     * 以 targetBookId 重解析；重解析 + 章节替换包在 Room 事务里，失败整体回滚保留旧章节，
     * 且解析后必须真的产出 [IMG: 占位符才提交 —— 纯文字书不会因为重解析被改变分章。
     * 进度只保留章索引（正文变了页级偏移必然重排）；封面/书名/分类等身份字段原样保留。
     * 每本书只尝试一次（含失败），标记存 SharedPreferences，避免每次打开都卡一次重解析。 */

    suspend fun migrateInlineImagesIfNeeded(book: Book): Book? = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.lock()
        try {
        if (book.isComic || !book.sourceId.isNullOrBlank()) return@withContext null
        if(book.scrollOffset>0 || bookDao.getBookmarksForBook(book.id).first().isNotEmpty() || bookDao.getHighlightsForBook(book.id).first().isNotEmpty()) return@withContext null
        // 标记 key 带 v2：旧版"任何失败都永久标记"的 book_migration/img_checked 全部作废，
        // 老书（含被旧标记锁死的）重新获得迁移机会
        val prefs = context.getSharedPreferences("book_migration", Context.MODE_PRIVATE)
        val checked = (prefs.getStringSet("img_checked_v3", emptySet()) ?: emptySet()).toMutableSet()
        if (checked.contains(book.id.toString())) return@withContext null

        fun markChecked() {
            checked.add(book.id.toString())
            prefs.edit().putStringSet("img_checked_v3", checked).apply()
        }

        val uri = resolveLocalBookUri(book.filePath)
        if (uri == null) {
            markChecked() // 非本地路径（网络书源等）：永久跳过
            return@withContext null
        }
        // content:// 的会话授权重启即失效：趁现在可读，先把源文件复制进私有目录，
        // 重解析用副本、书也改指副本 —— 此后不再依赖会话授权
        var effectiveUri = uri
        var filePathOverride: String? = null
        if (uri.scheme == "content") {
            val copy = copySourceIntoPrivateStorage(uri, book.title)
            if (copy == null) {
                // 这次都读不了（授权已失效）：不打标记，下次会话内打开书架可再试
                return@withContext null
            }
            effectiveUri = copy
            filePathOverride = copy.toString()
        }
        val kind = sniffImageCapableFormat(effectiveUri)
        if (kind == null) {
            // 确无图片资源：永久标记（纯文字书不值得每次打开都嗅探）
            markChecked()
            return@withContext null
        }

        runCatching {
            val restored = (database ?: AppDatabase.getDatabase(context)).withTransaction {
                val oldMetadata = bookDao.getChaptersMetadataList(book.id)
                bookDao.deleteChaptersForBook(book.id)
                val result = when (kind) {
                    "epub" -> EpubParser.importEpub(context, effectiveUri, book.title, bookDao, targetBookId = book.id)
                    "fb2" -> Fb2Parser.importFb2(context, effectiveUri, book.title, bookDao, targetBookId = book.id)
                    "docx" -> DocxParser.importDocx(context, effectiveUri, book.title, bookDao, targetBookId = book.id)
                    else -> MobiParser.importMobi(context, effectiveUri, book.title, bookDao, targetBookId = book.id)
                }.getOrNull() ?: throw IllegalStateException("重解析失败")
                // 硬门槛：必须真的提取到内嵌图片，否则回滚（纯文字书不值得改变分章）
                if (bookDao.searchChapters(book.id, "[IMG:").isEmpty()) {
                    throw IllegalStateException("未提取到内嵌图片")
                }
                val newMetadata = bookDao.getChaptersMetadataList(book.id)
                require(oldMetadata.map { it.title } == newMetadata.map { it.title }) {
                    "章节结构发生变化，保留原有阅读位置"
                }
                val restored = book.copy(
                totalChapters = result.totalChapters,
                currentChapterIndex = book.currentChapterIndex
                    .coerceIn(0, (result.totalChapters - 1).coerceAtLeast(0)),
                filePath = filePathOverride ?: book.filePath
            )
                bookDao.updateBook(restored)
                restored
            }
            // 零副本迁移成功：清理旧方案的解压图片副本（EPUB 内图已改为 epzip 引用）
            if (kind == "epub") {
                runCatching { File(context.filesDir, "epub_images/${book.id}").deleteRecursively() }
            }
            android.util.Log.i(
                "BookImport",
                "[MigrateImages] book ${book.id} '${book.title}' -> ${restored.totalChapters} chapters with inline images"
            )
            markChecked()
            restored
        }.onFailure {
            if (it is kotlinx.coroutines.CancellationException) throw it
            // 只有"解析成功但纯文字书"才永久标记；IO/解析异常不打标（下次打开重试，开销毫秒级）
            if (it.message in setOf("未提取到内嵌图片", "章节结构发生变化，保留原有阅读位置")) markChecked()
            android.util.Log.w("BookImport", "[MigrateImages] migration skipped/failed for book ${book.id}", it)
        }.getOrNull()
        } finally { ContentMutationGate.mutex.unlock() }
    }

    private fun resolveLocalBookUri(filePath: String): Uri? {
        if (filePath.isBlank()) return null
        return when {
            filePath.startsWith("content://") -> Uri.parse(filePath)
            filePath.startsWith("file://") -> Uri.parse(filePath)
            filePath.startsWith("/") -> Uri.fromFile(File(filePath))
            else -> null // 网络书源下载的章节不是本地原文件，不迁移
        }
    }

    /** 嗅探本地书格式；EPUB/DOCX 必须真的带图片资源才算候选。返回 epub/fb2/docx/mobi 或 null。 */
    private fun sniffImageCapableFormat(uri: Uri): String? = runCatching {
        fun open(): java.io.InputStream? =
            if (uri.scheme == "content") context.contentResolver.openInputStream(uri)
            else File(uri.path!!).inputStream()

        val head = open()?.use { s ->
            val buf = ByteArray(68)
            var off = 0
            while (off < buf.size) {
                val n = s.read(buf, off, buf.size - off)
                if (n <= 0) break
                off += n
            }
            buf.copyOf(off)
        } ?: return null

        if (head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            // ZIP 容器：EPUB（mimetype/.opf）或 DOCX（word/document.xml）
            var isEpub = false
            var isDocx = false
            var hasImage = false
            var hasMedia = false
            open()?.use { s ->
                java.util.zip.ZipInputStream(s).use { zip ->
                    val budget = ArchiveBudget()
                    var e = zip.nextEntry
                    while (e != null) {
                        ArchiveBudget.destination(context.cacheDir, e.name)
                        val name = e.name.lowercase()
                        val mimeBytes = if (name == "mimetype") java.io.ByteArrayOutputStream() else null
                        budget.copyEntry(zip, mimeBytes)
                        when {
                            name == "mimetype" -> if (mimeBytes!!.toString("UTF-8").contains("epub")) isEpub = true
                            name.endsWith(".opf") -> isEpub = true
                            name == "word/document.xml" -> isDocx = true
                            name.startsWith("word/media/") && !e.isDirectory -> hasMedia = true
                            looksLikeImageEntry(name) -> hasImage = true
                        }
                        e = zip.nextEntry
                    }
                }
            }
            when {
                isEpub && hasImage -> "epub"
                isDocx && hasMedia -> "docx"
                else -> null
            }
        } else if (head.size >= 68 && String(head, 60, 8, Charsets.US_ASCII) == "BOOKMOBI") {
            // MOBI 是否真有图交给重解析后的 [IMG: 硬门槛判断
            "mobi"
        } else {
            // FB2：<binary> 通常在文件尾部，看到 <FictionBook 即为候选
            val text = open()?.use { s ->
                val buf = ByteArray(262144)
                val n = s.read(buf)
                String(buf, 0, maxOf(n, 0), Charsets.UTF_8).lowercase()
            } ?: ""
            if (text.contains("<fictionbook")) "fb2" else null
        }
    }.getOrNull()

    private fun looksLikeImageEntry(name: String): Boolean {
        val n = name.substringAfterLast('/')
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
            n.endsWith(".gif") || n.endsWith(".webp")
    }

    /**
     * 把 content:// 源文件复制进应用私有目录并返回 file:// URI。
     * GetContent() 的读授权只在当前会话有效，重启后源文件永远读不了 ——
     * 导入与迁移一律改用私有副本。复制失败返回 null（调用方回退原 URI）。
     */
    private fun copySourceIntoPrivateStorage(uri: Uri, displayName: String): Uri? {
        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "book" }
        val dest = File(dir, "${java.util.UUID.randomUUID()}_$safeName")
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: error("无法读取源文件")
            input.use { ins -> dest.outputStream().use { out ->
                ArchiveBudget(256L * 1024 * 1024, 256L * 1024 * 1024, 1).copyEntry(ins, out)
            } }
            require(dest.length() > 0) { "文件为空" }
            Uri.fromFile(dest)
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    companion object { private val importMutex = ContentMutationGate.mutex }

    suspend fun importBookFromUri(
        uri: Uri, fileName: String, sourceId: String? = null, resourceId: String? = null
    ): Result<Book> = withContext(Dispatchers.IO) {
        importMutex.lock()
        var privateCopy: Uri? = null
        try {
            val db = database ?: AppDatabase.getDatabase(context)
            // Check before copying content URIs; all DB writes succeed together or roll back.
            val existing = if (sourceId != null && resourceId != null) {
                bookDao.getBookBySourceResource(sourceId, resourceId) ?: bookDao.getBookByFilePath(uri.toString())
            } else bookDao.getBookByFilePath(uri.toString())
            if (existing != null) {
                val linked = if (sourceId != null && resourceId != null) existing.copy(sourceId = sourceId, comicId = resourceId) else existing
                if (linked != existing) db.withTransaction { bookDao.updateBook(linked) }
                return@withContext Result.success(linked)
            }
            privateCopy = if (uri.scheme == "content") {
                copySourceIntoPrivateStorage(uri, fileName) ?: error("无法保存源文件，请检查存储空间和读取授权")
            } else null
            val effectiveUri = privateCopy ?: uri
            val imported = db.withTransaction {
                val parsed = importBookUnchecked(effectiveUri, fileName).getOrThrow()
                if (sourceId != null && resourceId != null) {
                    parsed.copy(sourceId = sourceId, comicId = resourceId).also { bookDao.updateBook(it) }
                } else parsed
            }
            // Comics read their extracted pages, so the input copy has no remaining owner.
            if (imported.isComic) privateCopy?.path?.let { File(it).delete() }
            Result.success(imported)
        } catch (e: Exception) {
            // Cancellation may be delivered after SQLite committed. Never remove a committed book's source.
            privateCopy?.let { copy ->
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (runCatching { bookDao.getBookCountByFilePath(copy.toString()) }.getOrNull() == 0) {
                        copy.path?.let { File(it).delete() }
                    }
                }
            }
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally { importMutex.unlock() }
    }

    /** Opt-in whole-book refresh. Parse first and commit replacement with annotations atomically. */
    suspend fun importDownloadedNovel(uri: Uri, snapshot: com.example.source.SearchBook, replace: Boolean): Result<Book> = withContext(Dispatchers.IO) {
        require(com.example.source.WholeBookNovelSources.contains(snapshot.sourceId)) { "该书源不使用小说更新流程" }
        require(uri.scheme == "file")
        importMutex.lock()
        try {
            val db = database ?: AppDatabase.getDatabase(context)
            val book = db.withTransaction {
                val old = bookDao.getBookBySourceResource(snapshot.sourceId, snapshot.id)
                if (old != null && (!replace || old.filePath == uri.toString())) return@withTransaction old
                val parsed = importBookUnchecked(uri, "${snapshot.title}.${snapshot.format}").getOrThrow()
                require(!parsed.isComic) { "下载内容不是文字小说" }
                val linked = parsed.copy(sourceId = snapshot.sourceId, comicId = snapshot.id,
                    author = snapshot.author.ifBlank { parsed.author }, coverUri = parsed.coverUri ?: snapshot.cover)
                if (old == null) {
                    bookDao.updateBook(linked)
                    return@withTransaction linked
                }
                val before = bookDao.getChaptersMetadataList(old.id)
                val after = bookDao.getChaptersListForBook(parsed.id)
                require(after.isNotEmpty()) { "新版没有可读正文，保留旧书" }
                val oldTitles = before.map { ChapterMerger.cleanSplitTitle(it.title) }.toSet()
                val newTitles = after.map { ChapterMerger.cleanSplitTitle(it.title) }.toSet()
                require(newTitles.containsAll(oldTitles)) { "新版缺少原有章节，已保留旧书；请等待源站补齐下载包" }
                fun mapped(index: Int): Int {
                    val title = before.getOrNull(index)?.title ?: return index.coerceIn(0, after.lastIndex)
                    val occurrence = before.take(index).count { it.title == title }
                    val exact = after.withIndex().filter { it.value.title == title }
                    return exact.getOrNull(occurrence)?.index ?: after.indexOfFirst {
                        ChapterMerger.cleanSplitTitle(it.title) == ChapterMerger.cleanSplitTitle(title)
                    }.coerceAtLeast(0)
                }
                val bookmarks = bookDao.getBookmarksForBook(old.id).first()
                val highlights = bookDao.getHighlightsForBook(old.id).first()
                ContentMutationGate.invalidatePendingWrites()
                bookDao.deleteChaptersForBook(old.id)
                bookDao.insertChapters(after.map { it.copy(id = 0, bookId = old.id) })
                bookDao.deleteChaptersForBook(parsed.id)
                bookDao.deleteBook(parsed)
                bookDao.deleteBookmarksForBook(old.id)
                bookmarks.forEach { bookDao.insertBookmark(it.copy(chapterIndex = mapped(it.chapterIndex))) }
                highlights.forEach { bookDao.insertHighlight(it.copy(chapterIndex = mapped(it.chapterIndex))) }
                old.copy(filePath = linked.filePath, author = linked.author, coverUri = linked.coverUri,
                    totalChapters = linked.totalChapters, currentChapterIndex = mapped(old.currentChapterIndex),
                    isFinished = if (newTitles.size > oldTitles.size) false else old.isFinished).also { bookDao.updateBook(it) }
            }
            Result.success(book)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(e)
        } finally { importMutex.unlock() }
    }

    private suspend fun importBookUnchecked(
        uri: Uri,
        fileName: String
    ): Result<Book> = withContext(Dispatchers.IO) {
        try {
            android.util.Log.d("BookImport", "[BookRepository] Starting streaming import: $fileName, uri: $uri")
            // 同一路径已入库：直接返回已有书籍，避免重复下载后书架出现重复书
            bookDao.getBookByFilePath(uri.toString())?.let { existing ->
                android.util.Log.d("BookImport", "[BookRepository] Same file already imported, skip duplicate: ${existing.title}")
                // 重新选同一本老书时源文件此刻可读：顺手补内嵌图片迁移（幂等，失败无感）
                runCatching { migrateInlineImagesIfNeeded(existing) }
                return@withContext Result.success(existing)
            }
            val effectiveUri = uri
            if (EpubParser.isEpubFile(fileName)) {
                return@withContext EpubParser.importEpub(context, effectiveUri, fileName, bookDao)
            }
            // PDF 与 CBZ/ZIP 一样走 ComicParser 逐页位图渲染（翻页模式阅读，位图渲染不会乱码）
            if (ComicParser.isComicFile(fileName)) {
                return@withContext ComicParser.importComic(context, effectiveUri, fileName, bookDao)
            }
            // MOBI / AZW3 / AZW：支持解析正文章节并直接阅读
            if (MobiParser.isMobiFile(fileName)) {
                return@withContext MobiParser.importMobi(context, effectiveUri, fileName, bookDao)
            }
            // FB2 / DOCX：XML 电子书与 Word 文档正文提取
            if (Fb2Parser.isFb2File(fileName)) {
                return@withContext Fb2Parser.importFb2(context, effectiveUri, fileName, bookDao)
            }
            if (DocxParser.isDocxFile(fileName)) {
                return@withContext DocxParser.importDocx(context, effectiveUri, fileName, bookDao)
            }
            if (unsupportedBinaryExtensions.any { fileName.lowercase().endsWith(it) }) {
                return@withContext importUnsupportedFormat(effectiveUri, fileName)
            }

            val charset = detectCharset(context, effectiveUri)
            val hasChapters = hasChapterTitles(context, effectiveUri, charset)
            val initialBook = Book(title = fileName.substringBeforeLast('.'), filePath = effectiveUri.toString())
            val bookId = bookDao.insertBook(initialBook).toInt()
            val chapterPattern = Pattern.compile("^第[0-9一二三四五六七八九十百千万]+[章回卷节\\s].*")
            val batch = mutableListOf<Chapter>()
            var chapterCount = 0
            var currentTitle = if (hasChapters) "前言" else "第 1 部分"
            val content = StringBuilder()
            suspend fun emit(title: String, text: String) {
                splitChapterText(text).forEachIndexed { index, part ->
                    batch.add(Chapter(bookId = bookId, chapterOrder = chapterCount++,
                        title = if (index == 0) title else "$title (续${index + 1})", content = part))
                    if (batch.size >= 50) { bookDao.insertChapters(batch); batch.clear() }
                }
            }
            val input = context.contentResolver.openInputStream(effectiveUri) ?: error("无法打开文件")
            BufferedReader(InputStreamReader(input, charset)).use { reader ->
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    val cleanLine = line.trim().removePrefix("\uFEFF")
                    if (hasChapters && cleanLine.isNotEmpty() && chapterPattern.matcher(cleanLine).matches()) {
                        if (content.isNotEmpty()) emit(currentTitle, content.toString())
                        currentTitle = cleanLine
                        content.setLength(0)
                    } else {
                        if (content.isNotEmpty()) content.append('\n')
                        content.append(line.removePrefix("\uFEFF"))
                        // One enormous line must also be split; never store it in a single Room row.
                        val limit = if (hasChapters) MAX_CHAPTER_LENGTH else 5000
                        if (content.length >= limit) {
                            emit(currentTitle, content.toString())
                            content.setLength(0)
                            currentTitle = if (hasChapters) ChapterMerger.cleanSplitTitle(currentTitle) + " (续)"
                                else "第 ${chapterCount + 1} 部分"
                        }
                    }
                }
            }
            if (content.isNotEmpty()) emit(currentTitle, content.toString())
            if (batch.isNotEmpty()) bookDao.insertChapters(batch)
            require(chapterCount > 0) { "文件中没有可阅读的正文" }
            val book = initialBook.copy(id = bookId, totalChapters = chapterCount)
            bookDao.updateBook(book)
            Result.success(book)

        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("BookImport", "[BookRepository] Streaming import failed", t)
            Result.failure(Exception(t.localizedMessage ?: "文件流式导入出现错误"))
        }
    }

    /** KFX/DJVU 等无阅读管线的格式：只登记书架，不按文本解析（避免大文件被读成超大垃圾章节导致打开卡顿）。 */
    private suspend fun importUnsupportedFormat(uri: Uri, fileName: String): Result<Book> =
        withContext(Dispatchers.IO) {
            try {
                val cleanTitle = fileName.substringBeforeLast('.').ifBlank { fileName }
                val book = Book(
                    title = cleanTitle,
                    filePath = uri.toString(),
                    totalChapters = 1
                )
                val bookId = bookDao.insertBook(book).toInt()
                bookDao.insertChapters(
                    listOf(
                        Chapter(
                            bookId = bookId,
                            chapterOrder = 0,
                            title = UNSUPPORTED_CHAPTER_TITLE,
                            content = ""
                        )
                    )
                )
                Result.success(book.copy(id = bookId, totalChapters = 1))
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                android.util.Log.e("BookImport", "[BookRepository] Unsupported format import failed", t)
                Result.failure(Exception(t.localizedMessage ?: "导入失败"))
            }
        }

    /**
     * 存量数据修复：把已入库的超大章节（如之前下载的单章大书）拆成小章节，
     * 与本地导入书一致，打开阅读器不再卡顿/闪退。
     */
    suspend fun splitOversizedChaptersInLibrary() = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.lock()
        try {
        try {
            val books = bookDao.getBooksWithOversizedChapters(MAX_CHAPTER_LENGTH)
            for (book in books) {
                val chapters = bookDao.getChaptersListForBook(book.id)
                if (chapters.isEmpty() || chapters.none { it.content.length > MAX_CHAPTER_LENGTH }) {
                    continue
                }

                val bookmarksBefore = bookDao.getBookmarksForBook(book.id).first()
                val charOffsets = PreferencesManager(context).pageTurnMode != 4
                if(!charOffsets && (book.scrollOffset>0 || bookmarksBefore.any { it.scrollOffset>0 })) continue
                val starts = HashMap<Int, Int>()
                val replacement = mutableListOf<Chapter>()
                var order = 0
                for (ch in chapters) {
                    starts[ch.chapterOrder] = order
                    if (ch.content.length <= MAX_CHAPTER_LENGTH) {
                        replacement.add(ch.copy(chapterOrder = order++))
                    } else {
                        val parts = ch.content.let { splitChapterText(it) }
                        parts.forEachIndexed { index, part ->
                            replacement.add(
                                Chapter(
                                    id = if (index == 0) ch.id else 0,
                                    bookId = ch.bookId,
                                    chapterOrder = order++,
                                    title = if (index == 0) ch.title else "${ch.title} (续${index + 1})",
                                    content = part
                                )
                            )
                        }
                    }
                }

                (database ?: AppDatabase.getDatabase(context)).withTransaction {
                    val fresh = bookDao.getBookById(book.id) ?: return@withTransaction
                    val bookmarks = bookDao.getBookmarksForBook(book.id).first()
                    val highlights = bookDao.getHighlightsForBook(book.id).first()
                    bookDao.deleteChaptersForBook(book.id)
                    bookDao.insertChapters(replacement)
                    bookDao.deleteBookmarksForBook(book.id)
                    bookmarks.forEach { bm ->
                        val old=chapters.firstOrNull { it.chapterOrder==bm.chapterIndex }
                        val candidates=replacement.filter { it.chapterOrder>=(starts[bm.chapterIndex] ?: -1) && it.chapterOrder<(starts[bm.chapterIndex+1] ?: Int.MAX_VALUE) }
                        var offset=if(charOffsets) bm.scrollOffset else 0
                        var target=candidates.firstOrNull()
                        if(charOffsets) for(part in candidates) { target=part; if(offset<part.content.length) break; offset-=part.content.length }
                        bookDao.insertBookmark(bm.copy(chapterIndex=target?.chapterOrder ?: bm.chapterIndex,scrollOffset=if(charOffsets) offset.coerceAtLeast(0) else bm.scrollOffset))
                    }
                    highlights.forEach { h ->
                        val lower=starts[h.chapterIndex] ?: h.chapterIndex
                        val upper=starts[h.chapterIndex+1] ?: Int.MAX_VALUE
                        val anchored=replacement.filter { it.chapterOrder in lower until upper && it.content.contains(h.selectedText) }
                        bookDao.insertHighlight(h.copy(chapterIndex=anchored.singleOrNull()?.chapterOrder ?: lower))
                    }
                    var remaining=if(charOffsets) fresh.scrollOffset else 0
                    val begin=starts[fresh.currentChapterIndex] ?: fresh.currentChapterIndex
                    val end=starts[fresh.currentChapterIndex+1] ?: Int.MAX_VALUE
                    var target=begin
                    if(charOffsets) for(part in replacement.filter { it.chapterOrder in begin until end }) {
                        target=part.chapterOrder; if(remaining<part.content.length) break; remaining-=part.content.length
                    }
                    bookDao.updateBook(fresh.copy(totalChapters=replacement.size,currentChapterIndex=target,
                        scrollOffset=if(charOffsets) remaining.coerceAtLeast(0) else fresh.scrollOffset))
                }
                android.util.Log.i("BookImport", "Split oversized chapters for '${book.title}': ${chapters.size} -> ${replacement.size}")
            }
            // ????????+??WAL ??????????????? checkpoint ?????
            // ?????????????
            runCatching {
                AppDatabase.getDatabase(context).openHelper.writableDatabase
                    .execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.util.Log.e("BookImport", "splitOversizedChaptersInLibrary failed", t)
        }
        } finally { ContentMutationGate.mutex.unlock() }
    }

    suspend fun getChaptersMetadataList(bookId: Int): List<Chapter> = withContext(Dispatchers.IO) {
        bookDao.getChaptersMetadataList(bookId)
    }

    suspend fun getChaptersByOrders(bookId: Int, orders: List<Int>): List<Chapter> = withContext(Dispatchers.IO) {
        bookDao.getChaptersByOrders(bookId, orders)
    }

    suspend fun updateBookProgress(bookId: Int, chapterIndex: Int, scrollOffset: Int, isFinished: Boolean, expectedEpoch: Long = ContentMutationGate.epoch) {
        ContentMutationGate.mutex.lock()
        try {
        if(expectedEpoch!=ContentMutationGate.epoch) return
        progressWriteMutex.lock()
        try {
            val book = bookDao.getBookById(bookId) ?: return
            if (book.currentChapterIndex == chapterIndex && book.scrollOffset == scrollOffset && (book.isFinished || !isFinished)) return
            bookDao.updateProgress(bookId,chapterIndex.coerceAtLeast(0),scrollOffset.coerceAtLeast(0),isFinished,System.currentTimeMillis())
        } finally {
            progressWriteMutex.unlock()
        }
        } finally { ContentMutationGate.mutex.unlock() }
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        com.example.download.DownloadManager.withControlLock {
        com.example.library.ComicDownloadManager.withControlLock {
        val tasks=(database ?: AppDatabase.getDatabase(context)).downloadTaskDao().getAllTasksSync().filter { task ->
            task.filePath.removePrefix("file://")==book.filePath.removePrefix("file://") ||
                (book.sourceId==task.sourceId && book.comicId==com.example.download.DownloadManager.originalBookId(task.id,task.sourceId))
        }
        for(task in tasks) {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork("download_${task.id}").result.get()
            com.example.download.DownloadWorker.withTaskLock(task.id) { }
        }
        val path=resolveLocalBookUri(book.filePath)?.path?.let { File(it).canonicalPath }
        if(path!=null) com.example.library.ComicDownloadManager.removeForDirectoryLocked(context,path)
        ContentMutationGate.mutex.lock()
        try {
        val db=database ?: AppDatabase.getDatabase(context)
        var godCovers=emptyList<String>()
        db.withTransaction {
            val local="local_${book.id}"
            godCovers=db.godMomentDao().forBookSync(local).mapNotNull { it.coverPath }
            db.godMomentDao().deleteForBook(local)
            bookDao.nullifyBookIdInReadingRecords(book.id)
            bookDao.nullifyBookIdInReadingSessions(book.id)
            bookDao.deleteBookmarksForBook(book.id)
            bookDao.deleteHighlightsForBook(book.id)
            bookDao.deleteChaptersForBook(book.id)
            tasks.forEach { db.downloadTaskDao().deleteTaskById(it.id) }
            bookDao.deleteBook(book)
        }
        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
            godCovers.forEach { com.example.god.GodCoverEngine.deleteQuietly(it) }
            val secrets=EncryptedSecretStore(context)
            tasks.forEach { secrets.remove("download:${it.id}") }
            deleteBookFiles(book,tasks)
        }
        } finally { ContentMutationGate.mutex.unlock() }
        }
        }
    }

    /**
     * 删除前预估这些书实际占用的磁盘字节（书体、封面、匹配的下载任务文件）。
     * 漫画的 filePath 是整章目录，需递归统计；同一路径只计一次。
     */
    suspend fun booksDiskBytes(books: List<Book>): Long = withContext(Dispatchers.IO) {
        val taskDao = AppDatabase.getDatabase(context).downloadTaskDao()
        val downloadsDir = File(context.filesDir, "downloads")
        val countedPaths = HashSet<String>()
        var total = 0L
        fun addFile(f: File) {
            val key = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
            if (!countedPaths.add(key)) return
            total += runCatching {
                if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
            }.getOrDefault(0L)
        }
        runCatching {
            val tasks = taskDao.getAllTasksSync()
            for (book in books) {
                addFile(File(book.filePath.removePrefix("file://")))
                book.coverUri?.let { addFile(File(it.removePrefix("file://"))) }
                val safeTitle = com.example.download.DownloadManager.sanitizeFileName(book.title)
                val matching = tasks.filter { task ->
                    task.filePath.removePrefix("file://") == book.filePath.removePrefix("file://") ||
                        (book.sourceId == task.sourceId && book.comicId == com.example.download.DownloadManager.originalBookId(task.id, task.sourceId))
                }
                matching.forEach { task ->
                    addFile(File(task.filePath))
                    addFile(File(downloadsDir, "${File(task.filePath).nameWithoutExtension}.tmp"))
                    addFile(File(downloadsDir, "${File(task.filePath).nameWithoutExtension}.resume"))
                }

            }
        }
        total
    }

    internal suspend fun reconcileLegacyReadingTotals(prefs: PreferencesManager) {
        if(prefs.readingTotalsReconciled) return
        val oldTotal=prefs.totalReadTimeSeconds.coerceAtLeast(0)
        (database ?: AppDatabase.getDatabase(context)).withTransaction {
            for((date, seconds) in prefs.legacyDailyTotals()) {
                val recorded=bookDao.recordedSecondsForDate(date)
                if(seconds>recorded) bookDao.insertReadingRecord(ReadingRecord(bookId=null,
                    bookTitle="历史阅读记录", dateStr=date, durationSeconds=seconds-recorded))
            }
        }
        // Older versions sometimes kept only an all-time counter: retain it without inventing a date.
        prefs.legacyUnattributedSeconds=maxOf(prefs.legacyUnattributedSeconds,oldTotal-bookDao.totalRecordedSeconds())
        prefs.readingTotalsReconciled=true
    }

    internal suspend fun addReadingTime(bookId:Int?, title:String, slices:List<ReadingTimeSlice>, expectedEpoch:Long=ContentMutationGate.epoch, prefs:PreferencesManager?=null):Boolean {
        ContentMutationGate.mutex.lock()
        try {
        if(expectedEpoch!=ContentMutationGate.epoch) return false
        if(prefs!=null) reconcileLegacyReadingTotals(prefs)
        (database ?: AppDatabase.getDatabase(context)).withTransaction {
            for(part in slices) {
                if(part.seconds<=0) continue
                val existing=if(bookId!=null) bookDao.getReadingRecordForBookAndDate(bookId,part.date)
                    else bookDao.getReadingRecordForTitleAndDate(title,part.date)
                bookDao.insertReadingRecord(existing?.copy(durationSeconds=existing.durationSeconds+part.seconds)
                    ?: ReadingRecord(bookId=bookId,bookTitle=title,dateStr=part.date,durationSeconds=part.seconds))
            }
        }
        return true
        } finally { ContentMutationGate.mutex.unlock() }
    }

    suspend fun cleanupOrphanFiles() = withContext(Dispatchers.IO) {
        ContentMutationGate.mutex.lock()
        try {
        val books=bookDao.getAllBooksSync()
        val tasks=(database ?: AppDatabase.getDatabase(context)).downloadTaskDao().getAllTasksSync()
        val cutoff=System.currentTimeMillis()-7L*24*60*60*1000
        val root=context.filesDir.canonicalFile
        fun path(raw:String):String = runCatching {
            val uri=Uri.parse(raw)
            File(if(uri.scheme=="file") uri.path ?: raw else raw).canonicalPath
        }.getOrDefault(raw)
        val owned=books.map { path(it.filePath) }.toSet()
        com.example.library.ComicDownloadManager.initialize(context)
        val comicDirs=com.example.library.ComicDownloadManager.tasks.value.keys.map {
            com.example.library.ComicDownloadManager.directory(context,it).canonicalPath
        }.toSet()
        File(root,"imports").listFiles().orEmpty().filter { it.isFile && it.lastModified()<cutoff && it.canonicalPath !in owned }
            .forEach { it.delete() }
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("comics_") && it.lastModified()<cutoff && it.canonicalPath !in owned && it.canonicalPath !in comicDirs }
            .forEach { if(it.canonicalFile.parentFile==root) it.deleteRecursively() }
        val downloads=File(root,"downloads")
        val taskBases=tasks.map { File(it.filePath).nameWithoutExtension }.toSet()
        downloads.listFiles().orEmpty().filter { it.isFile && it.lastModified()<cutoff && (it.extension=="tmp" || it.extension=="resume") && it.nameWithoutExtension !in taskBases }
            .forEach { it.delete() }
        } finally { ContentMutationGate.mutex.unlock() }
    }

    suspend fun addReadingSession(session: ReadingSession) {
        (database ?: AppDatabase.getDatabase(context)).withTransaction {
            ReadingTimeSlices.split(session.startTimeMs, session.endTimeMs, session.durationSeconds).forEach { part ->
                if(part.seconds > 0) bookDao.insertReadingSession(session.copy(id=0,dateStr=part.date,
                    startTimeMs=part.start,endTimeMs=part.end,durationSeconds=part.seconds,startHour=part.hour))
            }
        }
    }

    suspend fun getReadingSessionsForDate(dateStr: String): List<ReadingSession> {
        return bookDao.getReadingSessionsForDate(dateStr)
    }

    /** 删除书籍时彻底清理磁盘文件（书本体、封面、下载任务与残留缓存），避免“删了但内存还在涨”。 */
    private suspend fun deleteBookFiles(book: Book,tasksForDeletion:List<com.example.download.DownloadTaskEntity>) {
        fun ownedFile(raw:String):File? {
            val file=resolveLocalBookUri(raw)?.path?.let { File(it).canonicalFile } ?: return null
            return file.takeIf { it.canonicalPath.startsWith(context.filesDir.canonicalPath+File.separator) }
        }
        // 0) EPUB 内嵌图片目录（[IMG:...] 占位符引用的本地图片）
        runCatching {
            File(context.filesDir, "epub_images/${book.id}").deleteRecursively()
        }
        // 1) 书本体：TXT/EPUB 文件或漫画目录
        runCatching {
            val f = ownedFile(book.filePath) ?: return@runCatching
            if (f.exists()) {
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
        // 2) 封面文件（漫画/EPUB 封面缓存）
        book.coverUri?.let {
            runCatching {
                val cf = ownedFile(it) ?: return@runCatching
                if (cf.exists()) cf.delete()
            }
        }
        // 3) 对应的下载任务记录 + downloads 目录文件
        runCatching {
            val taskDao = AppDatabase.getDatabase(context).downloadTaskDao()
            val downloadsDir = File(context.filesDir, "downloads")
            val safeTitle = com.example.download.DownloadManager.sanitizeFileName(book.title)
            tasksForDeletion.forEach { task ->
                // 同步清掉内存里的下载状态，否则书库搜索卡会一直显示「已存入书架」
                com.example.download.DownloadProgressBroadcaster.removeState(task.id)
                runCatching { File(task.filePath).delete() }
                File(downloadsDir, "${File(task.filePath).nameWithoutExtension}.resume").delete()
                runCatching {
                    File(
                        downloadsDir,
                        "${File(task.filePath).nameWithoutExtension}.tmp"
                    ).delete()
                }
            }

        }
    }

    suspend fun deleteReadingRecord(id: Int) {
        bookDao.deleteReadingRecord(id)
    }

    fun getChaptersForBook(bookId: Int) = bookDao.getChaptersForBook(bookId)

    fun getBookmarksForBook(bookId: Int) = bookDao.getBookmarksForBook(bookId)

    suspend fun addBookmark(bookmark: Bookmark) = bookDao.insertBookmark(bookmark)

    suspend fun deleteBookmark(id: Int) = bookDao.deleteBookmark(id)

    fun getHighlightsForBook(bookId: Int) = bookDao.getHighlightsForBook(bookId)

    suspend fun addHighlight(highlight: Highlight) = bookDao.insertHighlight(highlight)

    suspend fun deleteHighlight(id: Int) = bookDao.deleteHighlight(id)

    suspend fun addCategory(name: String) {
        // 重名防护：同名分类不再重复插入（旧表 name 无唯一索引）
        val existing = bookDao.getCategoryByName(name)
        if (existing == null) bookDao.insertCategory(CategoryEntity(name = name))
    }

    /** 幂等种子：确保"默认"分类始终存在（迁移兜底 + 新装库） */
    suspend fun ensureDefaultCategory() {
        if (bookDao.getCategoryByName(DEFAULT_CATEGORY) == null) {
            bookDao.insertCategory(CategoryEntity(name = DEFAULT_CATEGORY))
        }
    }

    /**
     * 第七轮第 6.1/6.2 条：删除分类。
     * - "默认"分类不可删除（书架必须始终存在至少一个分类）；
     * - 删除用户分类时，其书籍迁回"默认"（不再产生只在聚合视图可见的孤儿书）。
     * @return true = 已删除；false = 被拒绝（默认分类）
     */
    suspend fun deleteCategory(category: com.example.data.CategoryEntity): Boolean {
        if (category.name == DEFAULT_CATEGORY) return false
        (database ?: AppDatabase.getDatabase(context)).withTransaction {
            bookDao.migrateBooksCategory(category.name, DEFAULT_CATEGORY)
            bookDao.deleteCategory(category)
        }
        return true
    }

    /** 第七轮第 6.3 条：设置分类的密码保护标记 */
    suspend fun setCategoryProtected(categoryId: Int, isProtected: Boolean) {
        bookDao.setCategoryProtected(categoryId, isProtected)
    }

    suspend fun checkAndSeedDefaultBooks() = withContext(Dispatchers.IO) {
        // 1. Remove legacy test / placeholder books
        try {
            val allBooks = bookDao.getAllBooksSync()
            val oldTestPaths = setOf("sample_test_novel", "sample_comic", "sample_epub3.epub")
            for (book in allBooks) {
                if (book.filePath in oldTestPaths ||
                    book.title.contains("5页测试小说") ||
                    book.title.contains("示例漫画") ||
                    book.title.contains("示例 EPUB")
                ) {
                    bookDao.deleteChaptersForBook(book.id)
                    bookDao.nullifyBookIdInReadingRecords(book.id)
                    bookDao.deleteBook(book)
                }
            }
            val comicDir = java.io.File(context.filesDir, "sample_comic")
            if (comicDir.exists()) comicDir.deleteRecursively()
            val epubFile = java.io.File(context.cacheDir, "sample_epub3.epub")
            if (epubFile.exists()) epubFile.delete()
        } catch (e: Exception) {
            android.util.Log.e("BookRepository", "Error cleaning old test books", e)
        }

        // 2. Retire the built-in 《Ciallo Reader使用指南》.
        //    It is no longer seeded, so new installs start with an empty shelf; installs that
        //    already have it drop it on next launch with the same cleanup the legacy
        //    placeholders above get, leaving no orphan chapters or reading records behind.
        try {
            val retiredGuide = bookDao.getBookByFilePath("ciallo_guide_novel")
            if (retiredGuide != null) {
                bookDao.deleteChaptersForBook(retiredGuide.id)
                bookDao.nullifyBookIdInReadingRecords(retiredGuide.id)
                bookDao.deleteBook(retiredGuide)
            }
        } catch (e: Exception) {
            android.util.Log.e("BookRepository", "Error removing the built-in guide", e)
        }
    }
}
