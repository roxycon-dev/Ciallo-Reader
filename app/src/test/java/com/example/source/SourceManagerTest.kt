package com.example.source

import androidx.test.core.app.ApplicationProvider
import com.example.source.storage.SharedPreferencesSourceStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SourceManagerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createSampleCustomSource(id: String, name: String): BookSource {
        val config = SourceConfig(
            id = id,
            name = name,
            baseUrl = "https://example.com",
            search = SearchRule(
                url = "https://example.com/search?q={keyword}",
                listPath = "items"
            ),
            isCustom = true
        )
        return com.example.source.impl.JsonBookSource(config)
    }

    @Test
    fun sourceOrdersRestoreAndDefaultAlwaysFollowsRegistry() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = SharedPreferencesSourceStorage(context)
        val sources = (1..3).map { createSampleCustomSource("order-$it", "Source $it") }
        suspend fun manager() = SourceManager(storage).apply {
            initialize()
            sources.forEach { registerSource(it) }
        }

        val first = manager()
        first.saveCustomOrder(listOf("order-2", "order-3", "order-1"))
        first.recordSourceLoad(sources[0], 700)
        first.recordSourceLoad(sources[1], 200)
        first.setSortMode(SourceSortMode.SPEED)

        val restored = manager()
        assertEquals(listOf("order-2", "order-1", "order-3"), restored.allSources.value.map { it.id })
        restored.setSortMode(SourceSortMode.CUSTOM)
        assertEquals(listOf("order-2", "order-3", "order-1"), restored.availableSources.value.map { it.id })
        restored.setSortMode(SourceSortMode.DEFAULT)
        assertEquals(listOf("order-1", "order-2", "order-3"), restored.allSources.value.map { it.id })
    }

    @Test
    fun testRegisterAndUnregisterCustomSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = SharedPreferencesSourceStorage(context)
        val sourceManager = SourceManager(storage)

        val customSource = createSampleCustomSource("test_custom_1", "自定义书源1")

        // Test 1: Register source -> allSources contains it
        sourceManager.registerCustomSource(customSource)
        testScheduler.advanceUntilIdle()

        val sourcesAfterRegister = sourceManager.allSources.value
        assertTrue(sourcesAfterRegister.any { it.id == "test_custom_1" })

        // Test 2: Remove source -> allSources no longer contains it
        sourceManager.unregisterCustomSource("test_custom_1")
        testScheduler.advanceUntilIdle()

        val sourcesAfterRemove = sourceManager.allSources.value
        assertFalse(sourcesAfterRemove.any { it.id == "test_custom_1" })
    }

    @Test
    fun testPersistenceAndRestartRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage1 = SharedPreferencesSourceStorage(context)
        val sourceManager1 = SourceManager(storage1)

        val jsonStr = """
            {
                "id": "test_custom_persisted",
                "name": "持久化书源",
                "baseUrl": "https://example.com",
                "search": {
                    "url": "https://example.com/search?q={keyword}",
                    "listPath": "items"
                }
            }
        """.trimIndent()
        val customSource = createSampleCustomSource("test_custom_persisted", "持久化书源")

        // 1. Register custom source and set active
        sourceManager1.registerCustomSource(customSource, rawJson = jsonStr)
        sourceManager1.setActiveSource("test_custom_persisted")
        testScheduler.advanceUntilIdle()

        // 2. Simulate App Restart by creating a new SourceManager with same storage
        val storage2 = SharedPreferencesSourceStorage(context)
        val sourceManager2 = SourceManager(storage2)
        sourceManager2.loadPersistedData()
        testScheduler.advanceUntilIdle()

        // Test 3: Restart recovery -> verifies activeSource and custom sources restored
        val activeSource = sourceManager2.activeSource.value
        assertNotNull(activeSource)
        assertEquals("test_custom_persisted", activeSource?.id)
        assertEquals("持久化书源", activeSource?.name)

        val allSources = sourceManager2.allSources.value
        assertTrue(allSources.any { it.id == "test_custom_persisted" })
    }

    @Test
    fun testDisableActiveSourceTriggersFallback() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage = SharedPreferencesSourceStorage(context)
        val sourceManager = SourceManager(storage)

        val src1 = createSampleCustomSource("source_1", "书源1")
        val src2 = createSampleCustomSource("source_2", "书源2")

        sourceManager.registerCustomSource(src1)
        sourceManager.registerCustomSource(src2)
        sourceManager.setActiveSource("source_1")
        testScheduler.advanceUntilIdle()

        assertEquals("source_1", sourceManager.activeSource.value?.id)

        // Disable active source_1
        sourceManager.setSourceEnabled("source_1", false)
        testScheduler.advanceUntilIdle()

        // Active source should automatically fallback to source_2
        assertEquals("source_2", sourceManager.activeSource.value?.id)
        val available = sourceManager.availableSources.value
        assertFalse(available.any { it.id == "source_1" })
        assertTrue(available.any { it.id == "source_2" })
    }
}
