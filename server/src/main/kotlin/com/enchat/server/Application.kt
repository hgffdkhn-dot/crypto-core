package com.enchat.server

import com.enchat.crypto.PasswordHash
import com.enchat.protocol.Protocol
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.websocket.WebSockets
import org.json.JSONObject

/** Ktor 模块装配：HTTP + WebSocket + 错误统一处理。 */
/** 把业务错误码映射为 HTTP 状态码；业务自定义码（>1000）回落 400，语义靠 err 字段表达。 */
private fun httpStatusFor(code: com.enchat.protocol.ErrorCode): HttpStatusCode = when (code.code) {
    400 -> HttpStatusCode.BadRequest
    401 -> HttpStatusCode.Unauthorized
    403 -> HttpStatusCode.Forbidden
    404 -> HttpStatusCode.NotFound
    409 -> HttpStatusCode.Conflict
    429 -> HttpStatusCode.TooManyRequests
    500 -> HttpStatusCode.InternalServerError
    else -> HttpStatusCode.BadRequest
}

fun Application.module(ctx: ServerContext) {
    install(WebSockets) {
        // 心跳由业务层 PING/PONG 帧负责（25s），此处不额外设置 pingPeriod
        maxFrameSize = Protocol.MAX_FRAME_BYTES.toLong()
        masking = false
    }

    install(StatusPages) {
        exception<ApiError> { call, cause ->
            // ⚠️ 不能一律返回 400。
            // ErrorCode 自带语义化状态码（UNAUTHORIZED=401 / FORBIDDEN=403 / RATE_LIMITED=429 …），
            // 若这里硬编码 BadRequest，未登录也会返回 400 —— 客户端与运维脚本都无法凭
            // HTTP 状态区分「参数错了」和「登录失效了」，只能去解析 err 字段，很别扭。
            // 业务自定义码（1000+）没有对应的 HTTP 状态，统一回落到 400，err 字段仍保留原值。
            call.respondText(
                JSONObject().put("ok", false).put("err", cause.code.code).put("msg", cause.message).toString(),
                ContentType.Application.Json,
                httpStatusFor(cause.code)
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("unhandled", cause)
            call.respondText(
                JSONObject().put("ok", false).put("err", 500).put("msg", "服务器内部错误").toString(),
                ContentType.Application.Json,
                HttpStatusCode.InternalServerError
            )
        }
    }

    configureHttp(ctx)
    with(WsHub(ctx)) { installWebSocket() }
}

/** 依据环境变量构建整套运行时（供 main 与集成测试共用）。 */
fun buildContext(config: Config = Config.fromEnv()): ServerContext {
    val ds = Db.create(config)
    val repos = Repos(ds)
    val keys = ServerKeys.loadOrCreate(config.serverKeyFile)
    val params = if (System.getenv("ENCHAT_FAST_KDF") == "1") {
        // 仅用于集成测试：把 Argon2 参数降到极低，避免测试耗时过长
        PasswordHash.Params(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
    } else {
        PasswordHash.SERVER_PARAMS
    }
    return ServerContext(
        config = config,
        repos = repos,
        serverKeys = keys,
        limiter = RateLimiter(),
        pepper = config.pepper,
        passwordParams = params
    )
}

fun main() {
    val config = Config.fromEnv()
    val ctx = buildContext(config)

    println("EnChat server starting on ${config.host}:${config.port}")
    println("服务器长期公钥（客户端必须 pinning）：")
    println("  ed25519=${ctx.serverKeys.ed25519PublicB64}")
    println("  x25519 =${ctx.serverKeys.x25519PublicB64}")

    embeddedServer(Netty, port = config.port, host = config.host, configure = {
        // 业务处理里包含阻塞式 JDBC 调用，默认的 Netty 线程组偏小（与 CPU 核数挂钩），
        // 高并发下会拖慢整体响应。生产环境建议同时考虑把 DB 调用切到 Dispatchers.IO。
        connectionGroupSize = 8
        workerGroupSize = 32
        callGroupSize = 64
    }) {
        module(ctx)
    }.start(wait = true)
}
