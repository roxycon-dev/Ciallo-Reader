package com.example.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Package replacement kills the old process; cleanup is performed by the new version. */
class AppUpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val result = goAsync()
        Thread {
            try { runCatching { AppUpdateStore(context.applicationContext).cleanupInstalled() } }
            finally { result.finish() }
        }.start()
    }
}
