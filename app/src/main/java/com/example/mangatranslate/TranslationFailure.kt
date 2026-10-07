package com.example.mangatranslate

/** Safe, actionable diagnostics. Never expose the response body, endpoint or API key. */
data class TranslationFailure(val code: String, val description: String, val retryable: Boolean = false) {
    fun exception() = TranslationException(this)

    companion object {
        val Privacy = TranslationFailure("privacy", "隐私模式禁止在线翻译，请关闭隐私模式后再试")
        val Models = TranslationFailure("models", "OCR 模型未就绪，请到翻译设置检查下载")
        val Ocr = TranslationFailure("ocr", "文字识别未成功，请检查清晰度或选择原文语言", true)
        val OcrEngine = TranslationFailure("ocr_init", "OCR 引擎加载失败，请关闭翻译后重新开启", true)
        val Empty = TranslationFailure("empty", "未获得有效译文，请检查原文语言或切换翻译引擎", true)
        val Config = TranslationFailure("config", "请先配置 AI 地址和模型名")
        val Format = TranslationFailure("format", "服务返回的译文格式不正确，请更换模型或重试", true)
        val Timeout = TranslationFailure("timeout", "翻译请求超时，请检查网络或换用更快的模型", true)
        val Network = TranslationFailure("network", "无法连接翻译服务，请检查网络和代理设置", true)
        fun http(status: Int) = when (status) {
            401, 403 -> TranslationFailure("auth", "服务拒绝访问（$status），请检查 API Key 或切换引擎")
            402 -> TranslationFailure("quota", "服务额度不足，请检查账户额度或切换引擎")
            404 -> TranslationFailure("endpoint", "接口或模型不存在（404），请检查地址和模型名")
            429 -> TranslationFailure("limited", "翻译服务繁忙或限流，请稍后重试", true)
            in 500..599 -> TranslationFailure("service", "翻译服务暂时不可用（$status），请稍后重试", true)
            else -> TranslationFailure("request", "服务未接受翻译请求（$status），请检查接口配置")
        }
        fun from(error: Throwable): TranslationFailure = when (error) {
            is TranslationException -> error.failure
            is java.net.SocketTimeoutException, is kotlinx.coroutines.TimeoutCancellationException -> Timeout
            is java.io.IOException -> Network
            is OutOfMemoryError -> TranslationFailure("memory", "页面过大，请降低图片清晰度后翻译")
            is IllegalArgumentException -> TranslationFailure("input", "页面或接口参数无效，请检查清晰度和翻译设置")
            else -> TranslationFailure("internal", "翻译处理失败，请切换引擎或重新进入阅读器", true)
        }
    }
}

class TranslationException(val failure: TranslationFailure) : Exception(failure.description)
