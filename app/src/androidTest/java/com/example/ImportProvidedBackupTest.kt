package com.example

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.BackupManager
import com.example.data.PreferencesManager
import com.example.transfer.ImportMode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Imports the user-provided migration archive directly on the device.
 *
 * The archive is staged into app files by the harness (`files/Ciallo-backup.ciallo`)
 * because the document picker / share-sheet path is not needed for a deterministic
 * test fixture. The production import path is BackupManager.importArchive.
 */
@RunWith(AndroidJUnit4::class)
class ImportProvidedBackupTest {
    @Test
    fun importProvidedBackup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.filesDir, "Ciallo-backup.ciallo")
        check(archive.isFile && archive.length() > 0) { "missing ${archive.absolutePath}" }
        val backup = BackupManager(context, PreferencesManager(context))
        val preview = backup.inspectArchive(archive)
        println("CIALLO_PREVIEW package=${preview.packageId} version=${preview.version} counts=${preview.counts} protected=${preview.containsProtected}")
        val result = backup.importArchive(archive, ImportMode.REPLACE) { p ->
            println("CIALLO_IMPORT ${p.phase} ${p.completedBytes}/${p.totalBytes} ${p.message}")
        }
        println("CIALLO_IMPORT_DONE added=${result.added} merged=${result.merged} skipped=${result.skipped} warnings=${result.warnings}")
        check(result.added + result.merged > 0) { "archive imported nothing" }
    }
}
