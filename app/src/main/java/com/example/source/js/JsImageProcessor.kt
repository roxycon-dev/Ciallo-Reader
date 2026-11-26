package com.example.source.js

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片后处理注册表：JsComicSource 解析 onImageLoad 时，
 * 把需要 modifyImage 重排的 URL 与对应 JS 引擎/代码登记到这里，
 * 在线阅读器拦截器与章节下载器在拿到原始字节后统一调用 transform。
 */
object JsImageProcessor {

    private data class Entry(
        val engine: JsSourceEngine,
        val code: String
    )

    private data class ImageKey(val url: String, val sourceScope: String?)
    private val entries = ConcurrentHashMap<ImageKey, Entry>()

    fun register(url: String, engine: JsSourceEngine, code: String?, sourceScope: String? = null) {
        val key = ImageKey(url, sourceScope)
        if (code.isNullOrBlank()) {
            entries.remove(key)
        } else {
            entries[key] = Entry(engine, code)
        }
    }

    fun unregister(url: String, sourceScope: String? = null) {
        entries.remove(ImageKey(url, sourceScope))
    }

    /** Scrambled pages must never be displayed before their complete-image transform. */
    fun hasTransform(url: String, sourceScope: String? = null): Boolean = entries.containsKey(ImageKey(url, sourceScope))

    /** 同步执行（拦截器/下载器都在 IO 线程），失败时返回 null 表示保持原图。 */
    fun transform(url: String, bytes: ByteArray, sourceScope: String? = null): ByteArray? {
        val entry = entries[ImageKey(url, sourceScope)] ?: return null
        Log.i("JsImageProcessor", "transform found for ${url.take(80)}")
        return try {
            entry.engine.transformImageBlocking(entry.code, bytes)
        } catch (e: Exception) {
            Log.w("JsImageProcessor", "transform failed: $url", e)
            null
        }
    }

    fun clear() {
        entries.clear()
    }
}
