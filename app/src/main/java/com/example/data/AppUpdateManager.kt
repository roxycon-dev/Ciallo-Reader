package com.example.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

internal enum class AppUpdateStage { AVAILABLE, DOWNLOADING, VERIFYING, READY, NEED_PERMISSION, INSTALLING, FAILED }

internal data class AppUpdateState(
    val release: UpdateRelease? = null,
    val stage: AppUpdateStage = AppUpdateStage.AVAILABLE,
    val visible: Boolean = false,
    val downloaded: Long = 0,
    val total: Long? = null,
    val message: String? = null,
    val autoInstall: Boolean = false,
) {
    val transferring get() = stage == AppUpdateStage.DOWNLOADING || stage == AppUpdateStage.VERIFYING
    val hasPackage get() = stage in setOf(AppUpdateStage.READY, AppUpdateStage.NEED_PERMISSION, AppUpdateStage.INSTALLING)
}

/** Application lifetime, so changing tabs or recreating Activity does not cancel a download. */
internal class AppUpdateManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val downloader: AppUpdateDownload = AppUpdateDownload(context),
    private val verify: (File, UpdateRelease) -> Long = UpdateApkVerifier(context)::verify,
) {
    private val store = AppUpdateStore(context)
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    val apkFile get() = store.apkFile
    private var transfer: Job? = null
    private val initialized = scope.async {
        val pending = withContext(Dispatchers.IO) {
            store.clearPartialFiles()
            if (store.cleanupInstalled()) return@withContext null
            val saved = store.load()
            if (saved == null) { store.clear(); return@withContext null }
            try {
                check(store.apkFile.exists() && updateFileSha256(store.apkFile) == saved.sha256)
                check(verify(store.apkFile, saved.release) == saved.versionCode)
                saved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { store.clear(); null }
        }
        if (pending != null) mutableState.value = AppUpdateState(pending.release, AppUpdateStage.READY,
            message = "安装包已下载，可继续安装")
    }

    fun offer(release: UpdateRelease) = scope.launch {
        initialized.await()
        val old = state.value
        if (old.transferring || old.hasPackage && old.release?.tag == release.tag) {
            mutableState.update { it.copy(visible = true) }
        } else {
            mutableState.value = AppUpdateState(release = release, visible = true)
        }
    }

    fun show() { mutableState.update { it.copy(visible = it.release != null) } }
    fun hide() { mutableState.update { it.copy(visible = false, autoInstall = false) } }
    fun cancelDownload() { transfer?.cancel() }
    fun consumeAutoInstall() { mutableState.update { it.copy(autoInstall = false) } }
    suspend fun awaitInitialization() { initialized.await() }

    fun startDownload() {
        if (transfer?.isActive == true) return
        val release = state.value.release ?: return
        val asset = release.apk ?: return
        mutableState.update { it.copy(stage = AppUpdateStage.DOWNLOADING, downloaded = 0, total = asset.size,
            visible = true, message = null, autoInstall = false) }
        transfer = scope.launch {
            var temporary: File? = null
            var committed = false
            try {
                val file = withContext(Dispatchers.IO) { store.newTransferFile() }
                temporary = file
                downloader.download(asset, release.tag, file) { received, total ->
                    mutableState.update { it.copy(downloaded = received, total = total) }
                }
                mutableState.update { it.copy(stage = AppUpdateStage.VERIFYING) }
                val pending = withContext(Dispatchers.IO) {
                    val code = verify(file, release)
                    PendingAppUpdate(release, code, updateFileSha256(file))
                }
                // Persist package + identity together before permitting any installer intent.
                withContext(NonCancellable + Dispatchers.IO) { store.save(file, pending); committed = true }
                mutableState.update { it.copy(stage = AppUpdateStage.READY, autoInstall = true) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(stage = if (committed) AppUpdateStage.READY else AppUpdateStage.AVAILABLE,
                    message = if (committed) "安装包已下载，可继续安装" else "下载已取消", autoInstall = false) }
                throw cancelled
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) {
                    mutableState.update { it.copy(stage = if (committed) AppUpdateStage.READY else AppUpdateStage.AVAILABLE,
                        message = if (committed) "安装包已下载，可继续安装" else "下载已取消", autoInstall = false) }
                    currentCoroutineContext().ensureActive()
                } else mutableState.update { it.copy(stage = AppUpdateStage.FAILED,
                    message = e.message?.take(250) ?: "更新下载失败，请重试或使用浏览器下载", autoInstall = false) }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { temporary?.delete() }
                transfer = null
            }
        }
    }

    fun permissionNeeded(message: String = "尚未允许 Ciallo Reader 安装未知应用。可前往授权，也可导出安装包或用浏览器下载。") {
        mutableState.update { it.copy(stage = AppUpdateStage.NEED_PERMISSION, visible = true, message = message, autoInstall = false) }
    }

    fun installing() { mutableState.update { it.copy(stage = AppUpdateStage.INSTALLING, visible = false, autoInstall = false) } }

    fun installationReturned() = scope.launch {
        val original = state.value
        if (original.stage != AppUpdateStage.INSTALLING) return@launch
        val installed = withContext(Dispatchers.IO) { store.cleanupInstalled() }
        if (state.value.stage != AppUpdateStage.INSTALLING || state.value.release?.tag != original.release?.tag) return@launch
        if (installed) mutableState.value = AppUpdateState()
        else mutableState.update { it.copy(stage = AppUpdateStage.READY, visible = true,
            message = "安装包已保留。如果尚未完成安装，可以重试或导出安装包", autoInstall = false) }
    }

    fun installationFailed(message: String) {
        mutableState.update { it.copy(stage = AppUpdateStage.READY, visible = true, message = message, autoInstall = false) }
    }

    companion object {
        @Volatile private var instance: AppUpdateManager? = null
        fun get(context: Context): AppUpdateManager = instance ?: synchronized(this) {
            instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
        }
    }
}
