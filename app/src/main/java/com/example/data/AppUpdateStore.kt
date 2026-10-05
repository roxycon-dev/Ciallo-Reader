package com.example.data

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class PendingAppUpdate(val release: UpdateRelease, val versionCode: Long, val sha256: String)

/** Fixed private paths: never delete a user-selected export, Downloads, or book files. */
internal class AppUpdateStore(private val context: Context) {
    private val directory get() = File(context.filesDir, "app_updates").apply { mkdirs() }
    val apkFile get() = File(directory, "update.apk")
    private val metadata get() = AtomicFile(File(directory, "pending.json"))
    fun newTransferFile() = File(directory, "${UUID.randomUUID()}.part")

    fun save(file: File, pending: PendingAppUpdate) = synchronized(storageLock) {
        val asset = pending.release.apk ?: error("更新缺少安装包")
        require(officialUpdateAsset(pending.release.tag, asset.name, asset.url))
        check(file.parentFile?.canonicalFile == directory.canonicalFile && file.extension == "part")
        if (apkFile.exists()) check(apkFile.delete()) { "无法替换旧安装包" }
        check(file.renameTo(apkFile)) { "无法保存安装包" }
        val json = JSONObject().put("tag", pending.release.tag).put("releaseUrl", pending.release.releaseUrl)
            .put("notes", pending.release.notes).put("name", asset.name).put("url", asset.url)
            .put("size", asset.size ?: JSONObject.NULL).put("digest", asset.sha256 ?: JSONObject.NULL)
            .put("versionCode", pending.versionCode).put("sha256", pending.sha256)
        val out = metadata.startWrite()
        try {
            out.write(json.toString().toByteArray(Charsets.UTF_8))
            metadata.finishWrite(out)
        } catch (e: Exception) { metadata.failWrite(out); throw e }
    }

    fun load(): PendingAppUpdate? = synchronized(storageLock) { runCatching {
        val bytes = metadata.openRead().use { it.readImportBytes(192 * 1024) }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        val tag = json.getString("tag")
        val name = json.getString("name")
        val url = json.getString("url")
        require(officialUpdateAsset(tag, name, url))
        val releaseUrl = "$UPDATE_REPOSITORY/releases/tag/$tag"
        require(json.getString("releaseUrl") == releaseUrl)
        val hash = json.getString("sha256")
        require(Regex("[0-9a-f]{64}").matches(hash))
        val size = if (json.isNull("size")) null else json.getLong("size")
        require(size == null || size in 1..MAX_UPDATE_BYTES)
        val digest = if (json.isNull("digest")) null else json.getString("digest")
        require(digest == null || Regex("[0-9a-f]{64}").matches(digest))
        val code = json.getLong("versionCode")
        require(code > 0)
        PendingAppUpdate(UpdateRelease(tag, releaseUrl, json.optString("notes").take(24_000), UpdateApkAsset(name, url, size, digest)), code, hash)
    }.getOrNull() }

    @Suppress("DEPRECATION")
    fun installedCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    }

    /** True only after PackageManager confirms the saved target version was installed. */
    fun cleanupInstalled(): Boolean = synchronized(storageLock) {
        val pending = load() ?: return false
        if (installedCode() < pending.versionCode) return false
        clear()
        return true
    }

    fun clear() = synchronized(storageLock) {
        apkFile.delete()
        metadata.delete()
        clearPartialFiles()
    }

    fun clearPartialFiles() = synchronized(storageLock) {
        directory.listFiles()?.filter { it.isFile && it.extension == "part" }?.forEach { it.delete() }
    }

    companion object { private val storageLock = Any() }
}
