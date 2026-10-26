package com.example.data

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.os.SystemClock
import android.util.AtomicFile
import com.example.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.Executors

/** Bounded local diagnostics; no upload or database/account contents. */
internal object StartupDiagnostics {
    private const val REPORT_LIMIT = 384 * 1024
    private const val HISTORY_LIMIT = 8
    private val lock = Any()
    private val events = ArrayDeque<String>()
    private val session = java.util.UUID.randomUUID().toString()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "diagnostic-writer").apply { isDaemon = true } }
    @Volatile private var installed = false
    private var sequence = 0L
    private fun reportFile(context: Context, fatal: Boolean) =
        AtomicFile(File(context.filesDir, if (fatal) "last-crash.txt" else "startup-diagnostic.txt"))

    fun install(context: Context) {
        synchronized(lock) {
            if (installed) return
            val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
            runCatching {
                val old = File(context.filesDir, "diagnostic-events.txt")
                if (old.isFile) writeAtomic(File(context.filesDir, "diagnostic-previous-events.txt"), readBounded(old, 64 * 1024))
            }
            Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
                try {
                    runCatching { HomeRenderRecovery.onUncaught(context, failure) }
                    record(context, "uncaught", failure)
                } finally { previous.uncaughtException(thread, failure) }
            }
            installed = true
        }
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private fun event(activity: Activity, state: String) = breadcrumb(context, "activity:${activity.javaClass.simpleName}:$state")
            override fun onActivityCreated(activity: Activity, saved: Bundle?) = event(activity, "created:restored=${saved != null}")
            override fun onActivityStarted(activity: Activity) = event(activity, "started")
            override fun onActivityResumed(activity: Activity) = event(activity, "resumed")
            override fun onActivityPaused(activity: Activity) = event(activity, "paused")
            override fun onActivityStopped(activity: Activity) = event(activity, "stopped")
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = event(activity, "saved")
            override fun onActivityDestroyed(activity: Activity) = event(activity, "destroyed")
        })
        breadcrumb(context, "application:created")
    }

    /** Fixed stage names / navigation templates only; never user content or concrete URLs. */
    fun breadcrumb(context: Context, stage: String) {
        val entry = "time=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} ${redact(stage).take(200)}"
        synchronized(lock) {
            events.addLast(entry)
            while (events.size > 256) events.removeFirst()
        }
        runCatching { writer.execute {
            runCatching { synchronized(lock) {
                writeAtomic(File(context.filesDir, "diagnostic-events.txt"), "session=$session\n" + events.joinToString("\n"))
            } }
        } }
    }

    internal fun redact(text: String): String = text
        .replace(Regex("(?i)(?:https?|wss?)://[^\\s<>\\\"]+"), "[url]")
        .replace(Regex("(?i)\\b(?:authorization|cookie|set-cookie|password|passwd|secret|access[_-]?token|refresh[_-]?token|api[_-]?key|token)\\b\\s*[:=]\\s*[^\\r\\n]+"), "[credential]")
        .replace(Regex("(?i)\\bBearer\\s+\\S+"), "[credential]")
        .replace(Regex("(?i)\\b(?:secret[-_]\\S+|password|passwd)\\b"), "[credential]")
        .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
        .replace(Regex("(?:[A-Za-z]:[\\\\/]|/(?:data|storage|sdcard|private|home)/)[^\\s\\\"]+"), "[path]")

    internal fun describe(failure: Throwable): String {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val text = StringBuilder()
        fun append(error: Throwable, relation: String, depth: Int) {
            if (text.length >= 128 * 1024) return
            if (depth >= 24 || !seen.add(error)) { text.appendLine("$relation [cycle/depth limit]"); return }
            text.appendLine("$relation ${error.javaClass.name}: ${redact(error.message.orEmpty().take(4096)).take(2048)}")
            error.stackTrace.take(256).forEach { text.appendLine("  at $it") }
            if (error.stackTrace.size > 256) text.appendLine("  [additional frames omitted]")
            error.suppressed.take(8).forEach { append(it, "Suppressed:", depth + 1) }
            error.cause?.let { append(it, "Caused by:", depth + 1) }
        }
        append(failure, "Exception:", 0)
        return redact(text.toString()).take(128 * 1024)
    }

    private fun environment(context: Context): String = buildString {
        appendLine("diagnostic-schema=2 session=$session")
        appendLine("app=${context.packageName} version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build=${BuildConfig.BUILD_TYPE}")
        appendLine("time=${System.currentTimeMillis()} uptime=${SystemClock.uptimeMillis()} pid=${android.os.Process.myPid()} thread=${redact(Thread.currentThread().name)}")
        appendLine("manufacturer=${Build.MANUFACTURER} brand=${Build.BRAND} model=${Build.MODEL} device=${Build.DEVICE} product=${Build.PRODUCT}")
        appendLine("Android=${Build.VERSION.RELEASE} SDK=${Build.VERSION.SDK_INT} fingerprint=${Build.FINGERPRINT}")
        appendLine("hardware=${Build.HARDWARE} board=${Build.BOARD} buildId=${Build.ID} display=${Build.DISPLAY} type=${Build.TYPE} tags=${Build.TAGS}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()} supported32=${Build.SUPPORTED_32_BIT_ABIS.joinToString()} supported64=${Build.SUPPORTED_64_BIT_ABIS.joinToString()}")
        if (Build.VERSION.SDK_INT >= 23) appendLine("securityPatch=${Build.VERSION.SECURITY_PATCH}")
        val config = context.resources.configuration
        val display = context.resources.displayMetrics
        appendLine("screen=${display.widthPixels}x${display.heightPixels} density=${display.density} dpi=${display.densityDpi} fontScale=${config.fontScale} dp=${config.screenWidthDp}x${config.screenHeightDp} smallestDp=${config.smallestScreenWidthDp} orientation=${config.orientation} uiMode=${config.uiMode}")
        appendLine("locale=${java.util.Locale.getDefault().toLanguageTag()} timezone=${java.util.TimeZone.getDefault().id}")
        val runtime = Runtime.getRuntime()
        appendLine("heap used=${runtime.totalMemory() - runtime.freeMemory()} allocated=${runtime.totalMemory()} max=${runtime.maxMemory()} nativeAllocated=${android.os.Debug.getNativeHeapAllocatedSize()}")
        runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            appendLine("systemMemory total=${memory.totalMem} available=${memory.availMem} low=${memory.lowMemory} threshold=${memory.threshold} memoryClass=${manager.memoryClass} largeMemoryClass=${manager.largeMemoryClass}")
            appendLine("requiredGles=${manager.deviceConfigurationInfo?.glEsVersion}")
        }
        runCatching { val disk = StatFs(context.filesDir.path); appendLine("storage available=${disk.availableBytes} total=${disk.totalBytes}") }
        val prefs = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
        runCatching { appendLine("displaySettings quality=${prefs.getInt("render_quality", 2)} onboarding=${prefs.getBoolean("has_seen_onboarding", false)}") }
            .onFailure { appendLine("displaySettings unavailable: ${it.javaClass.name}") }
        appendLine("renderEffectAvailable=${Build.VERSION.SDK_INT >= 31} runtimeShaderAvailable=${Build.VERSION.SDK_INT >= 33}")
    }

    private fun threads(): String = buildString {
        Thread.getAllStackTraces().entries.sortedWith(compareBy({ it.key.name != "main" }, { it.key.id })).take(48).forEach { (thread, stack) ->
            appendLine("thread id=${thread.id} name=${redact(thread.name)} state=${thread.state} daemon=${thread.isDaemon}")
            stack.take(96).forEach { appendLine("  at $it") }
        }
    }.take(192 * 1024)

    fun record(context: Context, stage: String, failure: Throwable) {
        runCatching { synchronized(lock) {
            val text = buildString {
                appendLine(environment(context))
                appendLine("stage=${redact(stage).take(200)}")
                appendLine("\n=== Exception chain and suppressed exceptions ===\n${describe(failure)}")
                appendLine("\n=== Recent lifecycle, routes and startup tasks ===\n" + events.joinToString("\n"))
                if (failure !is VirtualMachineError) {
                    appendLine("\n=== Threads at failure ===")
                    runCatching { appendLine(threads()) }
                }
            }.let(::redact).take(REPORT_LIMIT)
            val history = File(context.filesDir, "diagnostic-history").apply { mkdirs() }
            writeAtomic(File(history, "${System.currentTimeMillis()}-${(++sequence).toString().padStart(8, '0')}-${if (stage == "uncaught") "crash" else "error"}.txt"), text)
            history.listFiles()?.filter { it.extension == "txt" }?.sortedByDescending { it.name }?.drop(HISTORY_LIMIT)?.forEach { it.delete() }
            writeAtomic(reportFile(context, stage == "uncaught"), text)
        } }
    }

    /** IO only: APK hashing and historical trace reads happen when the user exports. */
    fun read(context: Context): String = buildString {
        appendLine("Ciallo Reader diagnostic bundle\n=== Current export environment ===\n${environment(context)}")
        runCatching {
            val hash = MessageDigest.getInstance("SHA-256")
            File(context.applicationInfo.sourceDir).inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            appendLine("APK SHA-256=${hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }}")
        }.onFailure { appendLine("APK hash unavailable: ${it.javaClass.name}") }
        appendLine("Use mapping.txt from this exact APK to retrace release stacks.")
        val records = synchronized(lock) {
            val history = File(context.filesDir, "diagnostic-history").listFiles()?.filter { it.extension == "txt" }?.sortedByDescending { it.name }?.take(HISTORY_LIMIT).orEmpty()
            (history + listOf("last-crash.txt", "startup-diagnostic.txt", "diagnostic-previous-events.txt", "diagnostic-events.txt").map { File(context.filesDir, it) })
                .filter { it.isFile }.map { it.name to runCatching { readBounded(it, REPORT_LIMIT) }.getOrElse { "read unavailable: ${it.javaClass.name}" } }
        }
        records.distinctBy { it.second }.forEach { (name, body) -> appendLine("\n=== $name ===\n$body") }
        appendLine("\n=== Current process timeline ===\n" + synchronized(lock) { events.joinToString("\n") })
        appendLine("\n=== Current threads ===\n" + runCatching { threads() }.getOrDefault("unavailable"))
        appendLine("\n=== Android historical process exits ===")
        if (Build.VERSION.SDK_INT >= 30) appendLine(ProcessExitDiagnostics.read(context))
        else appendLine("This OS does not expose ApplicationExitInfo; native crash / ANR traces may require device system logs.")
        if (records.isEmpty()) appendLine("No captured Java/Kotlin failure yet; environment and timeline are included.")
    }.let(::redact)

    internal fun readLimited(input: java.io.InputStream, max: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var remaining = max
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            if (n == 0) break
            output.write(buffer, 0, n)
            remaining -= n
        }
        return output.toByteArray().toString(Charsets.UTF_8) + if (remaining == 0) "\n[trace/file size limit reached]" else ""
    }
    private fun readBounded(file: File, max: Int) = AtomicFile(file).openRead().use { readLimited(it, max) }
    private fun writeAtomic(file: File, text: String) = writeAtomic(AtomicFile(file), text)
    private fun writeAtomic(file: AtomicFile, text: String) {
        val stream = file.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}

internal suspend fun <T> diagnosedStartupTask(context: Context, stage: String, task: suspend () -> T): T? {
    StartupDiagnostics.breadcrumb(context, "task:$stage:start")
    var failed = false
    val result = try {
        optionalStartupTask({ failed = true; StartupDiagnostics.record(context, stage, it) }, task)
    } catch (cancelled: CancellationException) {
        StartupDiagnostics.breadcrumb(context, "task:$stage:cancelled")
        throw cancelled
    }
    StartupDiagnostics.breadcrumb(context, "task:$stage:${if (failed) "failed" else "complete"}")
    return result
}

/** Optional startup work must not terminate the reading process. Cancellation still propagates. */
internal suspend fun <T> optionalStartupTask(onFailure: (Throwable) -> Unit, task: suspend () -> T): T? = try {
    task()
} catch (cancelled: CancellationException) { throw cancelled
} catch (failure: Exception) { onFailure(failure); null
} catch (failure: LinkageError) { onFailure(failure); null }
