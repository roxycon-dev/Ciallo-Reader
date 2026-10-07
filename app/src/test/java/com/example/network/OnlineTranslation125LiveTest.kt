package com.example.network

import com.example.mangatranslate.OnlineFallbackTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnlineTranslation125LiveTest {
    @Test fun publicTencentServiceTranslatesAndCachesWithoutApiKey() = runBlocking {
        assumeTrue(System.getenv("CIALLO_TRANSLATION_LIVE") == "1")
        val translator = OnlineFallbackTranslator()
        val start = System.nanoTime()
        val japanese = translator.translateBatch(listOf("今日はいい天気ですね。", "こんにちは。"), "ja")
        assertNotNull(japanese)
        assertEquals(2, japanese!!.size)
        assertTrue(japanese.all { it.isNotBlank() && !it.contains('�') })
        val english = translator.translateBatch(listOf("Hello, how are you?", "Thank you very much."), "en")
        assertNotNull(english)
        assertTrue(english!!.all { it.any { ch -> ch in '\u4e00'..'\u9fff' } })
        val cachedAt = System.nanoTime()
        assertEquals(japanese, translator.translateBatch(listOf("今日はいい天気ですね。", "こんにちは。"), "ja"))
        assertTrue((System.nanoTime() - cachedAt) / 1_000_000 < 250)
        println("Tencent live batches: ja=$japanese en=$english totalMs=${(System.nanoTime() - start) / 1_000_000}")
    }
}
