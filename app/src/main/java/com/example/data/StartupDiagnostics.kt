package com.example.data

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File

/** Local, bounded diagnostics. Never include exception messages, URLs, books or credentials. */
internal object StartupDiagnostics {
    private val lock = Any()
    @Volatile private var installed = false
    private fun reportFile(context: Context, fatal: Boolean) =
        AtomicFile(File(context.filesDir, if (fatal) "last-crash.txt" else "startup-diagnostic.txt"))

    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                try { record(context, "uncaught", failure) }
                finally { previous.uncaughtException(thread, failure) }
            }
            installed = true
        }
    }

    internal fun describe(failure: Throwable): String = buildString {
        var current: Throwable? = failure
        // Cause cycles and pathological stacks must not exhaust memory in the crash handler.
        repeat(8) {
            val error = current ?: return@buildString
            appendLine(error.javaClass.name)
            error.stackTrace.take(32).forEach { frame ->
                appendLine("  ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
            }
            current = error.cause?.takeUnless { it === error }
        }
    }.take(24 * 1024)

    fun record(context: Context, stage: String, failure: Throwable) {
        // Recording must never replace the original failure or prevent Android's crash handling.
        runCatching { synchronized(lock) {
            val file = reportFile(context, stage == "uncaught")
            val text = buildString {
                appendLine("Ciallo Reader ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("time=${System.currentTimeMillis()} stage=$stage")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT}")
                appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
                append(describe(failure))
            }
            val stream = file.startWrite()
            try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
        } }
    }

    fun read(context: Context): String = synchronized(lock) {
        listOf(true, false).mapNotNull { fatal ->
            runCatching { reportFile(context, fatal).openRead().use { it.readImportBytes(32 * 1024).toString(Charsets.UTF_8) } }
                .getOrNull()
        }.joinToString("\n").ifBlank {
            "暂无 Java/Kotlin 崩溃记录。系统拦截或原生库崩溃可能不会产生此记录。"
        }
    }
}

/** Optional startup work must not terminate the reading process. Cancellation still propagates. */
internal suspend fun <T> optionalStartupTask(
    onFailure: (Throwable) -> Unit,
    task: suspend () -> T,
): T? = try {
    task()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    onFailure(failure)
    null
} catch (failure: LinkageError) {
    onFailure(failure)
    null
}
