package com.example.download

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class DownloadStatus {
    PENDING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey val id: String, // e.g. bookId
    val sourceId: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val downloadUrl: String,
    val format: String,
    val status: DownloadStatus,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(defaultValue = "0") val progress: Float = 0f
)
