package com.example.source

/** Source-generated failure messages are not novel paragraphs. Never silently strip them. */
internal fun hasMissingNovelContent(text: String): Boolean = listOf(
    "内容加载失败", "內容加載失敗", "本章节内容正在手打中", "本章暂无正文", "章节内容缺失",
    "本章尚未翻译", "本章中文译文尚未齐全", "内容还在处理中", "內容還在處理中",
    "内容正在处理中", "內容正在處理中"
).any(text::contains)
