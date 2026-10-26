package com.example.data

import android.app.ActivityManager
import android.content.Context
import androidx.annotation.RequiresApi

/** Keep API 30 framework types out of classes loaded by older ROMs. */
@RequiresApi(30)
internal object ProcessExitDiagnostics {
    fun read(context: Context): String = runCatching {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
        if (exits.isEmpty()) return@runCatching "No process exit records available."
        buildString {
            exits.forEach { exit ->
                appendLine("time=${exit.timestamp} pid=${exit.pid} process=${exit.processName} reason=${exit.reason} status=${exit.status} importance=${exit.importance} pssKiB=${exit.pss} rssKiB=${exit.rss}")
                appendLine("description=${StartupDiagnostics.redact(exit.description.orEmpty())}")
                runCatching {
                    exit.traceInputStream?.use {
                        appendLine("--- System-provided ANR/native trace ---")
                        appendLine(StartupDiagnostics.redact(StartupDiagnostics.readLimited(it, 128 * 1024)))
                    }
                }.onFailure { appendLine("trace unavailable: ${it.javaClass.name}") }
            }
        }
    }.getOrElse { "Process exit history unavailable: ${it.javaClass.name}" }
}
