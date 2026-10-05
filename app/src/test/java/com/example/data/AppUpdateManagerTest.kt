package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdateManagerTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val asset = UpdateApkAsset("Ciallo-Reader-v1.2.4.apk", "$UPDATE_REPOSITORY/releases/download/v1.2.4/Ciallo-Reader-v1.2.4.apk")
    private val release = UpdateRelease("v1.2.4", "$UPDATE_REPOSITORY/releases/tag/v1.2.4", apk = asset)
    private fun downloader() = AppUpdateDownload(context, OkHttpClient.Builder().addInterceptor {
        Response.Builder().request(it.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body("fixture apk".toResponseBody()).build()
    }.build())

    @Test fun downloadedPackageWaitsForPermissionAndRemainsAvailableAfterDenial() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppUpdateStore(context).clear()
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ -> 205 }
            manager.offer(release).join()
            manager.startDownload()
            val ready = withTimeout(10_000) { manager.state.first { it.stage == AppUpdateStage.READY } }
            assertTrue(ready.autoInstall)
            manager.consumeAutoInstall()
            manager.permissionNeeded()
            manager.permissionNeeded("Permission denied")
            assertEquals(AppUpdateStage.NEED_PERMISSION, manager.state.value.stage)
            assertTrue(manager.apkFile.exists())
            assertFalse(manager.state.value.autoInstall)
            manager.installing()
            manager.installationReturned().join()
            assertEquals(AppUpdateStage.READY, manager.state.value.stage)
            assertTrue(manager.apkFile.exists())
        } finally { scope.cancel() }
    }

    @Test fun checksumOrSignerFailureNeverRequestsInstallationAndCanRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppUpdateStore(context).clear()
        var reject = true
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ ->
                if (reject) error("Invalid signature") else 205
            }
            manager.offer(release).join()
            manager.startDownload()
            withTimeout(10_000) { manager.state.first { it.stage == AppUpdateStage.FAILED } }
            assertFalse(manager.state.value.autoInstall)
            assertFalse(manager.apkFile.exists())
            reject = false
            // The failed operation finishes its cleanup before the next UI turn.
            withTimeout(10_000) {
                while (manager.state.value.stage != AppUpdateStage.READY) {
                    manager.startDownload()
                    kotlinx.coroutines.delay(10)
                }
            }
            assertTrue(manager.apkFile.exists())
        } finally { scope.cancel() }
    }

    @Test fun relaunchRestoresVerifiedPackageWithoutLaunchingInstallerAutomatically() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AppUpdateStore(context)
        store.clear()
        val file = store.newTransferFile().apply { writeText("fixture apk") }
        store.save(file, PendingAppUpdate(release, 205, updateFileSha256(file)))
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ -> 205 }
            manager.awaitInitialization()
            assertEquals(AppUpdateStage.READY, manager.state.value.stage)
            assertFalse(manager.state.value.visible)
            assertFalse(manager.state.value.autoInstall)
            manager.show()
            assertTrue(manager.state.value.visible)
        } finally { scope.cancel() }
    }

    @Test fun relaunchDiscardsTamperedPackageAndInterruptedDownload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AppUpdateStore(context)
        store.clear()
        val file = store.newTransferFile().apply { writeText("fixture apk") }
        store.save(file, PendingAppUpdate(release, 205, updateFileSha256(file)))
        store.apkFile.writeText("changed bytes")
        val partial = store.newTransferFile().apply { writeText("interrupted") }
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ -> fail("Tampered file should fail before APK validation"); 205 }
            manager.awaitInitialization()
            assertNull(manager.state.value.release)
            assertFalse(store.apkFile.exists())
            assertFalse(partial.exists())
        } finally { scope.cancel() }
    }

    @Test fun immediateCancellationNeverLeavesDownloadingStateStuck() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppUpdateStore(context).clear()
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ -> 205 }
            manager.offer(release).join()
            manager.startDownload()
            manager.cancelDownload()
            val result = withTimeout(10_000) { manager.state.first { !it.transferring } }
            assertTrue(result.stage in setOf(AppUpdateStage.AVAILABLE, AppUpdateStage.READY))
        } finally { scope.cancel() }
    }

    @Test fun staleInstallerResultAfterRestartCannotInventAReadyPackage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppUpdateStore(context).clear()
        try {
            val manager = AppUpdateManager(context, scope, downloader()) { _, _ -> 205 }
            manager.awaitInitialization()
            manager.installationReturned().join()
            assertFalse(manager.state.value.hasPackage)
            assertFalse(manager.state.value.visible)
            manager.offer(release).join()
            manager.installationReturned().join()
            assertEquals(AppUpdateStage.AVAILABLE, manager.state.value.stage)
        } finally { scope.cancel() }
    }
}
