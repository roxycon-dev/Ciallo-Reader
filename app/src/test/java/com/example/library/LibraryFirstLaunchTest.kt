package com.example.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.PreferencesManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class LibraryFirstLaunchTest {

    private lateinit var context: Context
    private lateinit var prefs: PreferencesManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Android startup normally initializes this; this fixture creates the ViewModel directly.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        prefs = PreferencesManager(context)
        prefs.hasSeenWelcome = false
        prefs.hasConfiguredSource = false
        prefs.hasImportedLocalBook = false
    }

    @Test
    fun testFirstLaunchShowsWelcomeState() {
        val viewModel = LibraryViewModel(context as android.app.Application)
        assertFalse(viewModel.hasSeenWelcome.value)
    }

    @Test
    fun testMarkWelcomeSeenPersistsState() {
        val viewModel = LibraryViewModel(context as android.app.Application)
        assertFalse(prefs.hasSeenWelcome)

        viewModel.markWelcomeSeen()
        assertTrue(prefs.hasSeenWelcome)
        assertTrue(viewModel.hasSeenWelcome.value)
    }

    @Test
    fun testSubsequentLaunchSkipsWelcome() {
        prefs.hasSeenWelcome = true
        val viewModel = LibraryViewModel(context as android.app.Application)
        assertTrue(viewModel.hasSeenWelcome.value)
    }
}
