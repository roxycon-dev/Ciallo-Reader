package com.example.ui.comic

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 漫画翻译配置序列化（第十五轮）：
 * 旧版本 JSON（无翻译字段）反序列化得到默认关闭；新字段往返一致。
 */
@RunWith(RobolectricTestRunner::class)
class ComicTranslationConfigTest {

    @Test
    fun `legacy json without translation fields defaults off`() {
        val legacy = JSONObject("""{"mode":"SINGLE","direction":"RTL","pageAnim":"SLIDE"}""")
        val config = ComicReaderConfig.fromJson(legacy)
        assertFalse(config.translationEnabled)
        assertEquals("auto", config.translationLang)
        assertEquals(1.0f, config.translationTextScale, 0.0001f)
    }

    @Test
    fun `old online default migrates but a new explicit selection round trips`() {
        val old = JSONObject("""{"translationEngine":"online"}""")
        assertEquals("auto", ComicReaderConfig.fromJson(old).translationEngine)
        val explicit = ComicReaderConfig(translationEngine = "online").toJson()
        assertEquals("online", ComicReaderConfig.fromJson(explicit).translationEngine)
        assertEquals("ai", ComicReaderConfig.fromJson(JSONObject("""{"translationEngine":"ai"}""")).translationEngine)
    }

    @Test
    fun `translation fields round trip`() {
        val config = ComicReaderConfig(
            translationEnabled = true,
            translationLang = "ja",
            translationTextScale = 1.25f,
        )
        val restored = ComicReaderConfig.fromJson(config.toJson())
        assertTrue(restored.translationEnabled)
        assertEquals("ja", restored.translationLang)
        assertEquals(1.25f, restored.translationTextScale, 0.0001f)
    }

    @Test
    fun `translation text scale clamped to valid range`() {
        val json = ComicReaderConfig(translationTextScale = 9f).toJson()
        assertEquals(1.4f, ComicReaderConfig.fromJson(json).translationTextScale, 0.0001f)
        val json2 = ComicReaderConfig(translationTextScale = 0.1f).toJson()
        assertEquals(0.8f, ComicReaderConfig.fromJson(json2).translationTextScale, 0.0001f)
    }

    @Test
    fun `unknown language value falls back to auto`() {
        val json = JSONObject("""{"translationLang":"fr"}""")
        assertEquals("auto", ComicReaderConfig.fromJson(json).translationLang)
    }
}
