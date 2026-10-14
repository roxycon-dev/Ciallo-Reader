package com.example.source.js

import com.example.source.SourceException
import org.json.JSONArray
import org.json.JSONObject

/** Validate adapter contracts; optional gallery chapters may be absent, required lists may not. */
internal object JsSourcePayload {
    fun requiredArray(data: JSONObject?, field: String): JSONArray = data?.opt(field) as? JSONArray
        ?: throw SourceException.ParseError("源站返回的${when (field) { "comics" -> "搜索列表"; "images" -> "图片列表"; else -> "目录" }}格式异常，请更新书源或稍后重试")

    fun chapters(data: JSONObject): JSONArray {
        if (!data.has("chapters") || data.isNull("chapters")) return JSONArray()
        return requiredArray(data, "chapters")
    }

    fun string(data: JSONObject, key: String): String = when (val value = data.opt(key)) {
        is String -> value.trim()
        is Number -> if (key == "id") value.toString() else ""
        else -> ""
    }

    fun images(data: JSONObject?): List<String> {
        val array = requiredArray(data, "images")
        return (0 until array.length()).map { index ->
            val value = array.opt(index) as? String
            if (value.isNullOrBlank()) throw SourceException.ParseError("源站图片列表包含缺失或异常的页面，请稍后重试")
            value.trim()
        }
    }
}
