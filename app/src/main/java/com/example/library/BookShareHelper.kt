package com.example.library

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.example.data.AppDatabase
import com.example.data.Book
import com.example.download.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 书架长按"分享"：把书籍原文件通过系统分享面板发给微信/QQ 等。
 *
 * 策略（按格式区分）：
 * - content://：原文件字节复制到临时目录，避免第三方提供器不允许二次授权；
 * - file:// 且文件仍在（EPUB / MOBI / PDF / AZW3 / 本地 TXT）：FileProvider 直接包装原文件，零拷贝；
 * - 漫画（filePath 是解压目录）：优先找回下载任务里保留的原始归档（cbz/zip/pdf），
 *   找不到则把解压页重新打成 cbz 放进 share_temp（用完即焚缓存）；
 * - TXT（下载后原文件已被清理）：从数据库章节内容重建 txt 到 share_temp（用完即焚缓存）。
 *
 * 临时文件保留 24 小时；新分享不会删除其他接收应用尚未读取的文件。
 */
object BookShareHelper {

    private const val TAG = "BookShare"
    private const val SHARE_TEMP_DIR = "share_temp"
    /** 分享临时文件保留时间：给微信/QQ 留足读取时间，超时后自动删除，不长期占双份内存。 */
    private const val SHARE_TEMP_RETENTION_MS = 24 * 60 * 60 * 1000L

    /** 进程级作用域：分享面板关闭后延时清理临时文件，不依赖弹窗/页面存活。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private data class ShareTarget(
        val uri: Uri,
        val mime: String,
        /** 本次分享新建的临时文件（用完即焚，分享后自动删除）。 */
        val tempFiles: List<File> = emptyList()
    )

    private fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /** 冷启动及分享前清理过期临时文件。 */
    fun cleanupTempShareDir(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, SHARE_TEMP_DIR)
            if (dir.exists()) {
                val cutoff = System.currentTimeMillis() - SHARE_TEMP_RETENTION_MS
                dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { runCatching { it.delete() } }
            }
        }
    }

    /**
     * 分享书籍。返回 null 表示已成功拉起分享面板，否则返回错误文案。
     * 内部在 IO 线程定位/生成文件，在主线程拉起 Intent。
     */
    suspend fun shareBook(context: Context, book: Book): String? = shareBooks(context, listOf(book))

    suspend fun shareBooks(context: Context, books: List<Book>): String? = withContext(Dispatchers.IO) {
        try {
            val intent = prepareShareIntent(context, books)
            withContext(Dispatchers.Main) {
                val chooser = Intent.createChooser(intent, if (books.size == 1) "分享「${books.first().title}」" else "分享 ${books.size} 本原文件")
                if (context !is Activity) {
                    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }

            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: ActivityNotFoundException) {
            "没有找到可分享的应用"
        } catch (e: Exception) {
            Log.w(TAG, "Unable to prepare book share", e)
            e.message ?: "分享失败"
        }
    }

    internal suspend fun prepareShareIntent(context: Context, books: List<Book>): Intent = withContext(Dispatchers.IO) {
        require(books.isNotEmpty()) { "请先选择要分享的书籍" }
        cleanupTempShareDir(context)
        val targets = mutableListOf<ShareTarget>()
        try {
            books.distinctBy { it.id }.forEach { book ->
                currentCoroutineContext().ensureActive()
                try { targets += resolveShareTarget(context, book) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { throw IllegalStateException("《${book.title}》：${e.message ?: "无法读取源文件"}", e) }
            }
            val types = targets.map { it.mime }.distinct()
            val mime = if (types.size == 1) types.first() else {
                val families = types.map { it.substringBefore('/') }.distinct()
                if (families.size == 1) "${families.first()}/*" else "*/*"
            }
            val clip = ClipData.newUri(context.contentResolver, "书籍原文件", targets.first().uri)
            targets.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
            Intent(if (targets.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                type = mime
                if (targets.size == 1) putExtra(Intent.EXTRA_STREAM, targets.first().uri)
                else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(targets.map { it.uri }))
                putExtra(Intent.EXTRA_SUBJECT, books.joinToString("、") { it.title })
                clipData = clip
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.also {
                val temporary = targets.flatMap { it.tempFiles }
                if (temporary.isNotEmpty()) scope.launch {
                    delay(SHARE_TEMP_RETENTION_MS)
                    temporary.forEach { file -> runCatching { file.delete() } }
                }
            }
        } catch (e: Exception) {
            targets.flatMap { it.tempFiles }.forEach { it.delete() }
            throw e
        }
    }

    private fun localFile(raw: String): File = File(if (raw.startsWith("file:")) Uri.parse(raw).path.orEmpty() else raw)

    private suspend fun originalFileTarget(context: Context, file: File, book: Book): ShareTarget {
        require(file.canRead() && file.length() > 0) { "源文件为空或无法读取" }
        val mime = mimeForPath(file.name, book)
        val uri = try { FileProvider.getUriForFile(context, authority(context), file) }
        catch (_: IllegalArgumentException) {
            // Files outside configured provider roots stay private. Copy their original bytes only.
            return copyContentUriToTemp(context, Uri.fromFile(file), book, mime)
        }
        return ShareTarget(uri, mime)
    }

    /** 定位分享目标：返回 (content URI, MIME)。 */
    private suspend fun resolveShareTarget(context: Context, book: Book): ShareTarget {
        val raw = book.filePath

        // 1) SAF 导入的书：content://
        if (raw.startsWith("content://")) {
            val uri = Uri.parse(raw)
            val mime = runCatching { context.contentResolver.getType(uri) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                ?: mimeForPath(displayName(context, uri) ?: raw, book)
            // MOBI / PDF / AZW3 / FB2 / DJVU 等：把源文件字节原样复制到 share_temp
            // 再分享，保证微信/QQ 拿到的就是完整的原始文件（用完即焚缓存）
            return copyContentUriToTemp(context, uri, book, mime)
        }

        val file = localFile(raw)

        // 2) 原文件还在：FileProvider 直接包装，零拷贝
        if (file.isFile && file.exists()) {
            return originalFileTarget(context, file, book)
        }

        // 3) 漫画：filePath 是解压目录（comics_xxx），原归档要么在下载任务里，要么重新打包
        if (file.isDirectory && book.isComic) {
            return resolveComicArchive(context, book, file)
        }

        // 4) 原文件路径失效（被移动/清理）：从下载任务记录找回原始文件
        if (!file.exists()) {
            val taskFile = findCompletedTaskFile(context, book)
            if (taskFile != null) {
                return originalFileTarget(context, taskFile, book)
            }
        }

        // 5) TXT 下载后原文件已被清理：从数据库章节内容重建（用完即焚缓存）
        if (!book.isComic && hasTextChapters(context, book)) {
            return rebuildTxt(context, book)
        }

        throw IllegalStateException("源文件已不存在，无法分享（原文件可能已被移动或删除）")
    }

    /** 漫画：优先用下载任务里保留的原始归档；找不到则把解压页重新打成 CBZ。 */
    private suspend fun resolveComicArchive(context: Context, book: Book, dir: File): ShareTarget {
        val original = File(dir, "original").listFiles()?.singleOrNull { it.isFile }
        if (original != null) return originalFileTarget(context, original, book)
        // 3a) 下载的漫画：DownloadTask 记录里保留着原始 cbz/zip/pdf 文件
        val archive = findCompletedTaskFile(context, book)
        if (archive != null) {
            return originalFileTarget(context, archive, book)
        }

        // 3b) 本地导入的漫画：原归档没保留，把解压页重新打包成 CBZ（用完即焚缓存）
        val pages = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
            .map { ch ->
                require(ch.content.isNotBlank()) { "部分漫画页面缺失，请重新下载后分享" }
                localFile(ch.content)
            }
        if (pages.isEmpty()) {
            throw IllegalStateException("漫画页面文件缺失，无法分享")
        }
        require(pages.all { it.isFile && it.canRead() }) { "部分漫画页面缺失，请重新下载后分享" }

        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.cbz")
        out.parentFile?.mkdirs()
        try { ZipOutputStream(FileOutputStream(out)).use { zip ->
            pages.forEachIndexed { index, page ->
                currentCoroutineContext().ensureActive()
                zip.putNextEntry(ZipEntry(String.format("%04d_%s", index, page.name)))
                page.inputStream().use { copyOriginalBytes(it, zip) }
                zip.closeEntry()
            }
        } } catch (e: Exception) { out.delete(); throw e }
        val uri = FileProvider.getUriForFile(context, authority(context), out)
        return ShareTarget(uri, "application/vnd.comicbook+zip", tempFiles = listOf(out))
    }

    /** Match exact path or source + resource identity; same titles can be different books. */
    private suspend fun findCompletedTaskFile(context: Context, book: Book): File? {
        val tasks = runCatching {
            AppDatabase.getDatabase(context).downloadTaskDao().getAllTasksSync()
        }.getOrDefault(emptyList())
        return tasks.firstOrNull {
            it.status == DownloadStatus.COMPLETED &&
                (localFile(it.filePath) == localFile(book.filePath) ||
                    (!book.sourceId.isNullOrBlank() && !book.comicId.isNullOrBlank() && book.sourceId == it.sourceId && book.comicId == com.example.download.DownloadManager.originalBookId(it.id, it.sourceId))) &&
                it.filePath.isNotBlank() &&
                localFile(it.filePath).isFile
        }?.let { localFile(it.filePath) }
    }

    /** content:// 源文件字节原样复制到 share_temp（用完即焚），保证目标应用拿到完整原始文件。 */
    private suspend fun copyContentUriToTemp(
        context: Context,
        uri: Uri,
        book: Book,
        mime: String
    ): ShareTarget {
        val ext = resolveExtension(context, uri, book, mime)
        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.$ext")
        out.parentFile?.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取源文件，可能已被移动或删除")
        try { input.use { src ->
            FileOutputStream(out).use { dst -> copyOriginalBytes(src, dst) }
        }
            require(out.length() > 0) { "源文件为空，无法分享" }
        } catch (e: Exception) { out.delete(); throw e }
        val name = displayName(context, uri)?.let { "${sanitizeFileName(it.substringBeforeLast('.'))}.$ext" }
            ?: "${sanitizeFileName(book.title)}.$ext"
        val fileUri = FileProvider.getUriForFile(context, authority(context), out, name)
        return ShareTarget(fileUri, mime, tempFiles = listOf(out))
    }

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment
        return runCatching { context.contentResolver.query(uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        }.getOrNull()
    }

    private suspend fun copyOriginalBytes(input: java.io.InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
    }

    private fun resolveExtension(context: Context, uri: Uri, book: Book, mime: String): String {
        // 1) 原文件显示名里的扩展名（最准确）
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) {
                    val name = cursor.getString(idx)
                    val ext = name?.substringAfterLast('.', "")?.lowercase()
                    if (!ext.isNullOrBlank() && ext.length in 1..6 && ext.all(Char::isLetterOrDigit)) return ext
                }
            }
        }
        // 2) URI 路径里的扩展名
        val pathExt = uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()
        if (!pathExt.isNullOrBlank() && pathExt.length in 1..6 && pathExt.all(Char::isLetterOrDigit)) {
            return pathExt
        }
        // 3) MIME 反推
        return when (mime) {
            "application/pdf" -> "pdf"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/epub+zip" -> "epub"
            "application/vnd.comicbook+zip" -> "cbz"
            "application/x-fictionbook+xml" -> "fb2"
            "text/plain" -> "txt"
            else -> book.filePath.substringAfterLast('.', "").lowercase().ifBlank { "file" }
        }
    }

    /** TXT 重建：把数据库章节内容按顺序写回文本文件。 */
    private suspend fun rebuildTxt(context: Context, book: Book): ShareTarget {
        val chapters = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
        if (chapters.isEmpty()) {
            throw IllegalStateException("书籍内容为空，无法分享")
        }
        val out = File(context.cacheDir, "$SHARE_TEMP_DIR/${java.util.UUID.randomUUID()}_${sanitizeFileName(book.title)}.txt")
        out.parentFile?.mkdirs()
        try { out.bufferedWriter(Charsets.UTF_8).use { writer ->
            chapters.forEach { ch ->
                currentCoroutineContext().ensureActive()
                if (ch.title.isNotBlank()) writer.append(ch.title).append("\n\n")
                writer.append(ch.content)
                if (!ch.content.endsWith("\n")) writer.append("\n")
                writer.append("\n")
            }
        } } catch (e: Exception) { out.delete(); throw e }
        val uri = FileProvider.getUriForFile(context, authority(context), out)
        return ShareTarget(uri, "text/plain", tempFiles = listOf(out))
    }

    private suspend fun hasTextChapters(context: Context, book: Book): Boolean {
        val chapters = runCatching {
            AppDatabase.getDatabase(context).bookDao().getChaptersListForBook(book.id)
        }.getOrDefault(emptyList())
        // 漫画章节内容是图片路径；文本书章节内容才是正文
        return chapters.any { it.content.length > 50 }
    }

    private fun mimeForPath(path: String, book: Book): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "epub" -> "application/epub+zip"
            "cbz" -> "application/vnd.comicbook+zip"
            "zip" -> "application/zip"
            "pdf" -> "application/pdf"
            "mobi", "prc" -> "application/x-mobipocket-ebook"
            "azw3", "azw", "kfx" -> "application/octet-stream"
            "fb2" -> "application/x-fictionbook+xml"
            "txt" -> "text/plain"
            "djvu" -> "image/vnd.djvu"
            else -> "application/octet-stream"
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "book" }.take(80)
    }
}
