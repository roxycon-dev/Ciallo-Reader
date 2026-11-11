package com.example.download

import androidx.room.*
import kotlinx.coroutines.flow.Flow

class DownloadTypeConverters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus = try {
        DownloadStatus.valueOf(value)
    } catch (e: Exception) {
        DownloadStatus.FAILED
    }
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY updatedAt DESC")
    fun getAllTasksFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks")
    suspend fun getAllTasksSync(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_tasks WHERE id = :id LIMIT 1")
    suspend fun getTaskById(id: String): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN ('PENDING', 'DOWNLOADING')")
    suspend fun getUnfinishedTasksSync(): List<DownloadTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(task: DownloadTaskEntity)

    @Query("UPDATE download_tasks SET status = :status, downloadedBytes = :downloadedBytes, totalBytes = :totalBytes, errorMessage = :errorMessage, progress = CASE WHEN :progress >= 0 THEN :progress WHEN :totalBytes > 0 THEN MIN(1.0, CAST(:downloadedBytes AS REAL) / :totalBytes) ELSE progress END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateProgressAndStatus(
        id: String,
        status: DownloadStatus,
        downloadedBytes: Long,
        totalBytes: Long,
        errorMessage: String?,
        updatedAt: Long = System.currentTimeMillis(),
        progress: Float = -1f
    )

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun deleteTaskById(id: String)
}
