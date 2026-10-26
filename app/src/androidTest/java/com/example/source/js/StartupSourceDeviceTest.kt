package com.example.source.js

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.PreferencesManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupSourceDeviceTest {
    @Test fun loginStateDoesNotTouchRuntimeAssetsOrInitializeNativeEngine() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val noRuntime = object : ContextWrapper(app) {
            override fun getAssets(): AssetManager = error("Login checks must not load JS runtime assets")
        }
        val prefs = app.getSharedPreferences("js_source_data", Context.MODE_PRIVATE)
        val key = "logged_startup_regression"
        try {
            val source = JsComicSource(noRuntime, "startup_regression", "Startup test", "1", "throw new Error('must not execute')")
            prefs.edit().putBoolean(key, true).commit()
            assertTrue(source.isLoggedIn())
            prefs.edit().putBoolean(key, false).commit()
            assertFalse(source.isLoggedIn())
            prefs.edit().remove(key).commit()
            assertFalse(source.isLoggedIn())
        } finally { prefs.edit().remove(key).commit() }
    }

    @Test fun adultSourcesRemainAvailableWithLegacyClosedSetting() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = context.getSharedPreferences("novel_reader_prefs", Context.MODE_PRIVATE)
        val old = storage.all["show_adult_sources"]
        try {
            storage.edit().putBoolean("show_adult_sources", false).commit()
            assertTrue(PreferencesManager(context).showAdultSources)
            assertTrue(JsSourceRepo.ADULT_KEYS.containsAll(setOf("jm", "picacg", "ehentai", "wnacg")))
        } finally {
            storage.edit().apply {
                if (old is Boolean) putBoolean("show_adult_sources", old) else remove("show_adult_sources")
            }.commit()
        }
    }

    @Test fun galleryReadPermissionsAreAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse(permissions.contains("android.permission.READ_MEDIA_IMAGES"))
        assertFalse(permissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
    }

}
