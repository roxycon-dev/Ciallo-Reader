package com.example.source

sealed class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    object LoginRequired : SourceException("需要登录后继续操作")
    data class NetworkError(val errorMsg: String, val throwable: Throwable? = null) : SourceException(errorMsg, throwable)
    data class ParseError(val errorMsg: String) : SourceException(errorMsg)
    object BookNotFound : SourceException("未能找到对应图书")
    data class Unknown(val errorMsg: String, val throwable: Throwable? = null) : SourceException(errorMsg, throwable)

    /**
     * 登录失败的具体原因。登录窗口要告诉用户“是密码错了还是网络问题”，
     * 书源层把失败归类成以下几种；UI 直接展示 [LoginError.detail]（已是可操作文案），
     * 并用 [LoginError.kind] 决定兜底方式。
     */
    enum class LoginFailureKind {
        /** 站点明确拒绝邮箱/密码（账号或密码错误、账号未验证）。 */
        CREDENTIALS,

        /** 站点要求人机验证（Cloudflare/DiamWall 挑战页），HTTP 客户端无法通过。 */        NEEDS_VERIFICATION,

        /** 请求过于频繁（HTTP 429）。 */
        RATE_LIMITED,

        /** 服务端错误（HTTP 5xx）。 */
        SERVER_ERROR,

        /** 所有候选节点都连不上（DNS 失败/超时/被重置）。 */
        NO_REACHABLE_NODE,

        /** 登录接口响应不符合预期（站点改版）。 */
        UNRECOGNIZED_RESPONSE,

        /** 其余网络层失败（超时、连接重置等）。 */
        NETWORK
    }

    data class LoginError(
        val kind: LoginFailureKind,
        val detail: String,
        val throwable: Throwable? = null
    ) : SourceException(detail, throwable)
}
