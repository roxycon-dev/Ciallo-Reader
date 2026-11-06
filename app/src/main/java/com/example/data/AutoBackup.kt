package com.example.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自动备份与恢复。
 *
 * 目标很具体：签名变化或换机导致必须卸载重装时，用户不必再手工导出再导入。
 * 备份写到 [AutoBackupStore] 选的公共目录，内容是「保存本地备份」的同一种 zip
 * （书、书源、收藏、阅读记录、设置、资源都在里面），恢复直接复用现有导入流程。
 *
 * 只在书库非空时备份：空书架生成的备份会覆盖掉上一份可用备份。
 */
class AutoBackup(
    context: Context,
    private val backup: BackupManager,
    private val store: AutoBackupStore = AutoBackupStore(context)
) {

    private val appContext = context.applicationContext

    data class Outcome(val ok: Boolean, val message: String)

    val enabled: Boolean get() = store.enabled
    val lastSuccessAt: Long get() = store.lastSuccessAt
    val lastResult: String? get() = store.lastResult
    fun treeLabel(): String? = store.treeLabel()
    fun hasFolder(): Boolean = store.tree() != null

    fun enable(value: Boolean) {
        store.enabled = value
    }

    fun attachFolder(uri: android.net.Uri): Boolean = store.attachTree(uri)

    fun latest(): AutoBackupStore.Snapshot? = store.snapshots().firstOrNull()

    /** 开机/回前台时的自动备份入口：满足间隔且有目录才真正写盘。 */
    suspend fun runIfDue(): Outcome? {
        val due = AutoBackupStore.isDue(
            enabled = store.enabled,
            hasTree = store.tree() != null,
            lastSuccessAt = store.lastSuccessAt,
            now = System.currentTimeMillis()
        )
        return if (due) backupNow() else null
    }

    suspend fun backupNow(): Outcome = withContext(Dispatchers.IO) {
        if (store.tree() == null) return@withContext Outcome(false, "还没有选择备份目录")
        val books = runCatching { AppDatabase.getDatabase(appContext).bookDao().getBooksCount() }.getOrDefault(0)
        if (books <= 0) return@withContext Outcome(false, "书库是空的，暂不备份")
        val name = AutoBackupStore.fileName(System.currentTimeMillis())
        val temp = File(appContext.filesDir, "auto_backup_${System.nanoTime()}.zip")
        try {
            backup.exportArchive(temp)
            if (temp.length() <= 0L) return@withContext Outcome(false, "备份内容为空，已保留上一份")
            if (!store.write(name, temp)) {
                store.lastResult = "写入备份目录失败"
                return@withContext Outcome(false, "写入备份目录失败，请重新选择目录")
            }
            store.lastSuccessAt = System.currentTimeMillis()
            store.lastResult = null
            store.prune()
            Outcome(true, "已备份 $books 本书")
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            store.lastResult = e.message ?: "备份失败"
            Outcome(false, store.lastResult ?: "备份失败")
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 用指定备份覆盖恢复；调用方负责之后的书源重载与界面刷新。 */
    suspend fun restore(name: String): com.example.transfer.ImportResult = withContext(Dispatchers.IO) {
        val temp = File(appContext.filesDir, "auto_restore_${System.nanoTime()}.zip")
        try {
            require(store.readInto(name, temp)) { "无法读取备份文件：$name" }
            backup.importArchive(temp, com.example.transfer.ImportMode.REPLACE)
        } finally {
            runCatching { temp.delete() }
        }
    }

    /** 首启时判断要不要提示恢复：书架为空 + 目录里有备份。 */
    suspend fun shouldOfferRestore(): Boolean {
        if (store.restoreOffered) return false
        if (store.tree() == null) return false
        val books = runCatching { AppDatabase.getDatabase(appContext).bookDao().getBooksCount() }.getOrDefault(1)
        if (books > 0) return false
        return store.snapshots().isNotEmpty()
    }

    fun markRestoreOffered() {
        store.restoreOffered = true
    }
}
