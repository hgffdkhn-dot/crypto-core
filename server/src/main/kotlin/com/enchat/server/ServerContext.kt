package com.enchat.server

import com.enchat.crypto.PasswordHash
import com.enchat.protocol.ErrorCode
import io.ktor.server.application.ApplicationCall

/**
 * 服务端运行时上下文（HTTP 与 WebSocket 共用）。
 *
 * @param pepper Argon2id 输出之后的 HMAC 密钥，应来自 KMS/HSM；泄露即失去"拖库不可爆破"的保障。
 */
/**
 * 限速阈值。默认值为生产建议值；集成测试会放宽，避免同一 IP 下大量用例被拦。
 */
data class RateLimitSettings(
    val registerPerHourPerIp: Int = 5,
    val loginPerWindowPerIp: Int = 30,
    val loginPerWindowPerUser: Int = 5,
    val friendRequestPerDayPerUser: Int = 20,
    val groupCreatePerDayPerUser: Int = 5,
    val sendPerSecondPerUser: Int = 30,
    val keyUploadPerHourPerUser: Int = 10
)

class ServerContext(
    val config: Config,
    val repos: Repos,
    val serverKeys: ServerKeys,
    val limiter: RateLimiter,
    val pepper: ByteArray,
    val passwordParams: PasswordHash.Params,
    val limits: RateLimitSettings = RateLimitSettings(),
    val sessionTtlMs: Long = 30L * 24 * 3600 * 1000
) {
    /** 校验 `Authorization: Bearer <token>`；失败抛 401。 */
    fun authenticate(header: String?): Long {
        val raw = header?.trim() ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        if (!raw.startsWith("Bearer ", ignoreCase = true)) throw ApiError(ErrorCode.UNAUTHORIZED)
        val token = raw.substring(7).trim()
        if (token.isEmpty()) throw ApiError(ErrorCode.UNAUTHORIZED)
        return repos.sessionUser(SessionTokens.hashOf(token))
            ?: throw ApiError(ErrorCode.UNAUTHORIZED)
    }

    companion object {
        /** 测试用：跳过 Bearer 解析细节，直接用 token 换 userId。 */
        fun userIdForToken(repos: Repos, token: String): Long? =
            repos.sessionUser(SessionTokens.hashOf(token))
    }
}

/** 便捷重载：从 ApplicationCall 取 token。 */
fun ServerContext.authenticateCall(call: ApplicationCall): Long =
    authenticate(call.request.headers["Authorization"])
