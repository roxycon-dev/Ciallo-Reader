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
    @Test fun defaultServiceTranslatesThreeLanguagesWithoutApiKey() = runBlocking {
        assumeTrue(System.getenv("CIALLO_TRANSLATION_LIVE") == "1")
        val translator = OnlineFallbackTranslator()
        val start = System.nanoTime()
        val japanese = translator.translateBatch(listOf("本気で言ってるの？", "無理しなくていい。"), "ja")
        assertNotNull(japanese)
        assertEquals(2, japanese!!.size)
        assertTrue(japanese[0].contains("认真") && japanese[1].contains("勉强"))
        val english = translator.translateBatch(listOf("You got this.", "Hold on, I can explain."), "en")
        assertNotNull(english)
        assertTrue(english!![0].contains("行") && english[1].contains("解释"))
        val korean = translator.translateBatch(listOf("진심으로 하는 말이야?", "무리하지 않아도 돼."), "ko")
        assertNotNull(korean)
        assertTrue(korean!![0].contains("认真") && korean[1].contains("勉强"))
        val cachedAt = System.nanoTime()
        assertEquals(japanese, translator.translateBatch(listOf("本気で言ってるの？", "無理しなくていい。"), "ja"))
        assertTrue((System.nanoTime() - cachedAt) / 1_000_000 < 250)
        println("Key-free live batches: ja=$japanese en=$english ko=$korean totalMs=${(System.nanoTime() - start) / 1_000_000}")
    }
}
