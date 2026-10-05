package com.example.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppUpdatePackageTest {
    private lateinit var context: Context
    private lateinit var store: AppUpdateStore
    private val asset = UpdateApkAsset("Ciallo-Reader-v1.2.4.apk", "$UPDATE_REPOSITORY/releases/download/v1.2.4/Ciallo-Reader-v1.2.4.apk")
    private val release = UpdateRelease("v1.2.4", "$UPDATE_REPOSITORY/releases/tag/v1.2.4", "Changes", asset)

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = AppUpdateStore(context)
        store.clear()
        // Preserve the real manifest providers and their XML metadata while changing
        // installed-version/signature fixtures; flags=0 drops the FileProvider.
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
        installed.versionName = "1.2.3"
        installed.longVersionCode = 204
        installed.signingInfo = signing(1)
        shadowOf(context.packageManager).installPackage(installed)
    }

    private fun signing(value: Int) = SigningInfo().apply {
        shadowOf(this).setSignatures(arrayOf(Signature(byteArrayOf(value.toByte()))))
    }
    private fun fixture(file: File, packageName: String = context.packageName, version: String = "1.2.4", code: Long = 205, signer: Int = 1) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            versionName = version
            longVersionCode = code
            signingInfo = signing(signer)
        }
        shadowOf(context.packageManager).setPackageArchiveInfo(file.absolutePath, info)
    }
    private fun savePending(): PendingAppUpdate {
        val file = store.newTransferFile().apply { writeText("test APK") }
        val saved = PendingAppUpdate(release, 205, updateFileSha256(file))
        store.save(file, saved)
        return saved
    }

    @Test fun acceptsOnlyMatchingPackageNewerVersionAndSameSigner() {
        val file = store.newTransferFile().apply { writeText("test APK") }
        fixture(file)
        assertEquals(205L, UpdateApkVerifier(context).verify(file, release))
    }

    @Test fun rejectsWrongPackageVersionCodeNameAndSigningCertificate() {
        val file = store.newTransferFile().apply { writeText("test APK") }
        for (case in 0..3) {
            fixture(file, packageName = if (case == 0) "attacker.reader" else context.packageName,
                version = if (case == 1) "1.2.5" else "1.2.4", code = if (case == 2) 204 else 205,
                signer = if (case == 3) 2 else 1)
            try { UpdateApkVerifier(context).verify(file, release); fail("case $case") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun invalidArchiveAndChecksumNeverPassVerification() {
        val file = store.newTransferFile().apply { writeText("not an APK") }
        try { UpdateApkVerifier(context).verify(file, release); fail("Archive should fail") }
        catch (_: IllegalStateException) { }
        fixture(file)
        try {
            UpdateApkVerifier(context).verify(file, release.copy(apk = asset.copy(sha256 = "0".repeat(64))))
            fail("Checksum should fail")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun recordsCanBeRestoredAfterProcessDeath() {
        val saved = savePending()
        assertEquals(saved, AppUpdateStore(context).load())
        assertEquals("test APK", store.apkFile.readText())
    }

    @Test fun longChineseReleaseNotesDoNotInvalidateSavedPackage() {
        val file = store.newTransferFile().apply { writeText("test APK") }
        val saved = PendingAppUpdate(release.copy(notes = "更新说明".repeat(6_000)), 205, updateFileSha256(file))
        store.save(file, saved)
        assertEquals(saved, store.load())
    }

    @Test fun pendingPackageIsKeptUntilTargetVersionIsActuallyInstalled() {
        savePending()
        assertFalse(store.cleanupInstalled())
        assertTrue(store.apkFile.exists())
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.longVersionCode = 205
        shadowOf(context.packageManager).installPackage(installed)
        assertTrue(store.cleanupInstalled())
        assertFalse(store.apkFile.exists())
        assertNull(store.load())
    }

    @Test fun cleanupNeverTouchesBooksOrExportedCopies() {
        val book = File(context.filesDir, "books/test-book.txt").apply { parentFile!!.mkdirs(); writeText("book") }
        val exported = File(context.filesDir, "share_temp/exported.apk").apply { parentFile!!.mkdirs(); writeText("user export") }
        savePending()
        store.newTransferFile().writeText("interrupted")
        store.clear()
        assertEquals("book", book.readText())
        assertEquals("user export", exported.readText())
    }

    @Test fun malformedSavedMetadataCannotPointToAnotherFileOrRepository() {
        savePending()
        val json = File(context.filesDir, "app_updates/pending.json")
        json.writeText(json.readText().replace("roxycon-dev", "attacker"))
        assertNull(store.load())
    }

    @Test fun installerUsesGrantedContentUriAndPackageSpecificPermissionPage() {
        savePending()
        // AndroidX FileProvider assumes '/' canonical paths; host Windows File uses
        // '\\'. Validate the real manifest/XML root below, substituting only URI creation.
        val provider = context.packageManager.resolveContentProvider("${context.packageName}.fileprovider", PackageManager.GET_META_DATA)!!
        assertEquals(com.example.R.xml.file_paths, provider.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        var allowed = false
        context.resources.getXml(com.example.R.xml.file_paths).use { xml ->
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && xml.name == "files-path") {
                    val root = File(context.filesDir, xml.getAttributeValue(null, "path")).canonicalFile.toPath()
                    allowed = allowed || store.apkFile.canonicalFile.toPath().startsWith(root)
                }
                xml.next()
            }
        }
        assertTrue("Real FileProvider XML must include the private update directory", allowed)
        val platform = AppUpdateInstaller(context) { file ->
            assertEquals(store.apkFile.canonicalFile, file.canonicalFile)
            Uri.parse("content://${context.packageName}.fileprovider/internal_files/app_updates/update.apk")
        }
        val intent = platform.installationIntent(store.apkFile)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.fileprovider", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, platform.permissionIntent().action)
        assertEquals("package:${context.packageName}", platform.permissionIntent().dataString)
    }

    @Test fun deniedPermissionCanBeDetectedAgainAfterUserGrantsIt() {
        val shadow = shadowOf(context.packageManager)
        val platform = AppUpdateInstaller(context)
        shadow.setCanRequestPackageInstalls(false)
        assertFalse(platform.canInstall())
        shadow.setCanRequestPackageInstalls(true)
        assertTrue(platform.canInstall())
    }
}
