package com.example

import android.app.Application
import com.example.data.StartupDiagnostics

class ReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.install(this)
    }
}
