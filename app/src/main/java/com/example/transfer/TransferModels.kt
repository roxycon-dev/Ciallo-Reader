package com.example.transfer

import androidx.room.Entity
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

enum class TransferCategory(val label: String) {
    BOOKS("书籍与离线原文件"), FAVORITES("收藏与分类"), ANNOTATIONS("书签、标注与神回"),
    HISTORY("阅读进度与记录"), SETTINGS("阅读设置与预设"), RESOURCES("自定义背景与字体"), SOURCES("书源")
}
enum class ImportMode { MERGE, REPLACE }
data class ExportSelection(
    val categories: Set<TransferCategory> = TransferCategory.entries.toSet(),
    internal val protectedAuthorized: Boolean = false,
)
data class ArchivePreview(
    val packageId: String, val version: Int, val counts: Map<String, Int>, val expandedBytes: Long,
    val categories: Set<TransferCategory>, val warnings: List<String>, val containsProtected: Boolean = false,
    val protectedFavorites: Boolean = false,
)
data class ImportResult(val added: Int, val merged: Int, val skipped: Int, val warnings: List<String> = emptyList())
data class TransferEstimate(val books:Int,val favorites:Int,val approximateBytes:Long,val warnings:List<String>)
data class TransferProgress(val phase: TransferPhase, val completedBytes: Long = 0, val totalBytes: Long = 0,
    val bytesPerSecond: Long = 0, val message: String = "") {
    val fraction: Float get() = if (totalBytes > 0) (completedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f) else 0f
    val remainingSeconds: Long? get() = if (bytesPerSecond > 0) ((totalBytes - completedBytes).coerceAtLeast(0) / bytesPerSecond) else null
}
enum class TransferPhase(val label: String) {
    IDLE("准备互传"), PREPARING("正在准备数据"), DISCOVERING("正在发现设备"), CONNECTING("正在连接"),
    AWAITING_CONFIRMATION("等待确认"), TRANSFERRING("正在传输"), WAITING_TO_RESUME("等待继续传输"),
    VERIFYING("正在校验"), RECEIVED("数据已收到"), IMPORTING("正在导入"), COMPLETED("已完成"),
    CANCELLED("已取消"), FAILED("传输未完成")
}
enum class TransferError { PERMISSION, WIFI_DISABLED, UNSUPPORTED, BUSY, CONNECTION, REJECTED, SPACE, CORRUPT, VERSION, IMPORT, UNKNOWN }
data class TransferPeer(val id: String, val name: String, val host: String? = null, val port: Int = 0,
    val p2pAddress: String? = null, val protocol: Int = 1) {
    val routeLabel: String get() = if (host != null) "同 Wi-Fi · 加密连接" else "附近直连 · 无需网络"
}
data class TransferSessionState(
    val phase: TransferPhase = TransferPhase.IDLE, val peer: TransferPeer? = null,
    val progress: TransferProgress = TransferProgress(TransferPhase.IDLE), val verificationCode: String? = null,
    val incoming: Boolean = false, val preview: ArchivePreview? = null, val result: ImportResult? = null,
    val error: TransferError? = null, val message: String = "", val peerReceived: Boolean = false,
    val peerImported: Boolean = false,
) {
    val busy: Boolean get() = phase in setOf(TransferPhase.PREPARING, TransferPhase.CONNECTING,
        TransferPhase.AWAITING_CONFIRMATION, TransferPhase.TRANSFERRING, TransferPhase.VERIFYING, TransferPhase.IMPORTING)
}

@Entity(tableName = "transfer_identities", primaryKeys = ["localKey"])
data class TransferIdentityEntity(val localKey: String, val stableId: String)
@Entity(tableName = "transfer_receipts", primaryKeys = ["packageId"])
data class TransferReceiptEntity(val packageId: String, val importedAt: Long)

internal const val TRANSFER_MIME = "application/vnd.ciallo.backup"
internal const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024
internal const val TRANSFER_RETENTION = 24L * 60 * 60 * 1000
internal const val CHUNK_SIZE = 1024 * 1024
internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
internal fun fileHash(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input -> val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    return digest.digest().hex()
}
internal fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
