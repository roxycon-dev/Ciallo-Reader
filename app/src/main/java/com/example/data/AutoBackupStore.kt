package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * 自动备份的落点与状态。
 *
 * 备份写到用户用系统文件选择器授权的目录（SAF），而不是应用私有目录——私有目录在
 * 卸载或清除数据时会被一起删掉，正是"重装后书源和下载的书全没了"的根源。授权用
 * `takePersistableUriPermission` 持久化，重启后仍可写；但卸载会让授权失效，所以重装后
 * 需要重新选一次目录，这一点由恢复提示负责引导。
 *
 * 备份文件就是「保存本地备份」产出的同一种 zip，可直接用现有导入流程恢复。
 */
class AutoBackupStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var lastSuccessAt: Long
        get() = prefs.getLong(KEY_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AT, value).apply()

    var lastResult: String?
        get() = prefs.getString(KEY_LAST_RESULT, null)
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value).apply()

    /** 每个安装只主动提示一次恢复，避免每次冷启动都弹窗。 */
    var restoreOffered: Boolean
        get() = prefs.getBoolean(KEY_RESTORE_OFFERED, false)
        set(value) = prefs.edit().putBoolean(KEY_RESTORE_OFFERED, value).apply()

    fun tree(): Uri? = prefs.getString(KEY_TREE, null)
        ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        ?.takeIf(::hasPermission)

    fun attachTree(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_TREE, uri.toString()).apply()
        true
    }.getOrDefault(false)

    fun detachTree() {
        prefs.edit().remove(KEY_TREE).apply()
    }

    /** 目录显示名（用户选择的文件夹名），取不到时给个中性说明。 */
    fun treeLabel(): String? {
        val uri = tree() ?: return null
        val document = treeDocument(uri) ?: return null
        return runCatching {
            appContext.contentResolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    data class Snapshot(val name: String, val modifiedAt: Long)

    /** 目录里属于本应用的备份，按时间从新到旧。 */
    fun snapshots(): List<Snapshot> {
        val uri = tree() ?: return emptyList()
        val children = childDocuments(uri)
        return children
            .filter { it.name.startsWith(FILE_PREFIX) && it.name.endsWith(FILE_SUFFIX) }
            .map { Snapshot(it.name, it.modifiedAt) }
            .sortedByDescending { it.modifiedAt }
    }

    /** 写一份备份；同名文件先删，避免系统自动改名堆出 "-1"。 */
    fun write(name: String, source: File): Boolean = runCatching {
        val uri = tree() ?: return false
        val document = treeDocument(uri) ?: return false
        deleteChild(uri, name)
        val created = DocumentsContract.createDocument(appContext.contentResolver, document, MIME, name)
            ?: return false
        appContext.contentResolver.openOutputStream(created, "w")?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
        } ?: return false
        true
    }.getOrDefault(false)

    fun readInto(name: String, target: File): Boolean = runCatching {
        val uri = tree() ?: return false
        val child = childDocuments(uri).firstOrNull { it.name == name } ?: return false
        appContext.contentResolver.openInputStream(child.uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: return false
        target.length() > 0L
    }.getOrDefault(false)

    /** 只保留最近 [MAX_KEPT] 份；排在后面的旧备份删掉。 */
    fun prune() {
        val uri = tree() ?: return
        snapshots().drop(MAX_KEPT).forEach { deleteChild(uri, it.name) }
    }

    fun delete(name: String) {
        val uri = tree() ?: return
        deleteChild(uri, name)
    }

    private data class Child(val uri: Uri, val name: String, val modifiedAt: Long)

    private fun childDocuments(tree: Uri): List<Child> {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        }.getOrNull() ?: return emptyList()
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        return runCatching {
            appContext.contentResolver.query(childrenUri, columns, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val name = cursor.getString(1) ?: continue
                        add(Child(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cursor.getLong(2)))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun deleteChild(tree: Uri, name: String) {
        childDocuments(tree).firstOrNull { it.name == name }?.let { child ->
            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, child.uri) }
        }
    }

    private fun treeDocument(tree: Uri): Uri? = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
    }.getOrNull()

    private fun hasPermission(uri: Uri): Boolean =
        appContext.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    companion object {
        const val FILE_PREFIX = "ciallo-auto-"
        const val FILE_SUFFIX = ".zip"
        private const val MIME = "application/zip"
        private const val PREFS_NAME = "auto_backup"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TREE = "tree"
        private const val KEY_LAST_AT = "last_at"
        private const val KEY_LAST_RESULT = "last_result"
        private const val KEY_RESTORE_OFFERED = "restore_offered"

        /** 保留份数：够覆盖一次误操作，又不至于把目录塞满。 */
        const val MAX_KEPT = 3

        /** 自动备份的最小间隔。 */
        const val INTERVAL_MS = 12 * 60 * 60 * 1000L

        fun fileName(now: Long): String {
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date(now))
            return "$FILE_PREFIX$stamp$FILE_SUFFIX"
        }

        fun isDue(enabled: Boolean, hasTree: Boolean, lastSuccessAt: Long, now: Long): Boolean =
            enabled && hasTree && now - lastSuccessAt >= INTERVAL_MS
    }
}
