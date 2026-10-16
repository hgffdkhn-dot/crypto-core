package com.enchat.server

import com.enchat.crypto.Bytes
import com.enchat.crypto.PasswordHash
import com.enchat.crypto.PasswordPolicy
import com.enchat.protocol.ErrorCode
import com.enchat.protocol.Protocol
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** 业务错误：统一转成 {ok:false, err, msg}。 */
class ApiError(val code: ErrorCode, override val message: String = code.message) : RuntimeException(message)

private val AVATAR_MIMES = setOf("image/jpeg", "image/png", "image/webp")

fun Application.configureHttp(ctx: ServerContext) {
    routing {
        get("/health") {
            call.respondText(
                JSONObject().put("ok", true).put("version", Protocol.VERSION).toString(),
                ContentType.Application.Json
            )
        }

        route("/api/v1") {
            post("/auth/register") {
                val body = JSONObject(call.receiveText().ifBlank { "{}" })
                val username = body.optString("username").trim()
                val password = body.optString("password", "")
                val nickname = body.optString("nickname").ifBlank { username }

                if (!Protocol.isValidUsername(username)) throw ApiError(ErrorCode.BAD_REQUEST, "用户名需为字母开头的 3-32 位字母/数字/下划线")
                val policy = PasswordPolicy.validate(password, username)
                if (!policy.accepted) throw ApiError(ErrorCode.WEAK_PASSWORD, policy.problems.first())

                val ip = call.request.local.remoteAddress
                if (!ctx.limiter.allow("register:$ip", ctx.limits.registerPerHourPerIp, 3600_000)) throw ApiError(ErrorCode.RATE_LIMITED)

                if (ctx.repos.findByUsername(username) != null) throw ApiError(ErrorCode.USERNAME_TAKEN)

                // 服务端参数：注册/登录走 Argon2id(64MiB, t=3)；沙盒与低端机可通过环境变量降级
                val hash = PasswordHash.hash(password, ctx.passwordParams, ctx.pepper)
                val uid = ctx.repos.createUser(username, hash, nickname.take(Protocol.NICKNAME_MAX))
                val userId = ctx.repos.findUserIdByUid(uid)!!

                val (token, tokenHash) = SessionTokens.issue()
                ctx.repos.createSession(tokenHash, userId, body.optString("deviceId"), ctx.sessionTtlMs)

                call.respondText(
                    ok().put("data", JSONObject()
                        .put("userId", userId)
                        .put("uid", uid)
                        .put("uidDisplay", Protocol.formatUid(uid))
                        .put("nickname", nickname)
                        .put("accessToken", token)
                        .put("expiresIn", ctx.sessionTtlMs / 1000)
                        .put("passwordScore", policy.score)).toString(),
                    ContentType.Application.Json
                )
            }

            post("/auth/login") {
                val body = JSONObject(call.receiveText().ifBlank { "{}" })
                val username = body.optString("username").trim()
                val password = body.optString("password", "")
                val ip = call.request.local.remoteAddress

                if (!ctx.limiter.allow("login-ip:$ip", ctx.limits.loginPerWindowPerIp, 900_000)) throw ApiError(ErrorCode.RATE_LIMITED)
                if (!ctx.limiter.allow("login-user:$username", ctx.limits.loginPerWindowPerUser, 900_000)) throw ApiError(ErrorCode.RATE_LIMITED)

                val row = ctx.repos.findByUsername(username)
                    ?: throw ApiError(ErrorCode.WRONG_CREDENTIALS)
                val stored = row["password_hash"] as? String ?: throw ApiError(ErrorCode.WRONG_CREDENTIALS)

                if (!PasswordHash.verify(password, stored, ctx.pepper)) throw ApiError(ErrorCode.WRONG_CREDENTIALS)

                val userId = (row["id"] as Number).toLong()
                ctx.limiter.reset("login-user:$username")

                val (token, tokenHash) = SessionTokens.issue()
                ctx.repos.createSession(tokenHash, userId, body.optString("deviceId"), ctx.sessionTtlMs)

                // 参数升级：登录成功后透明重哈希
                if (PasswordHash.needsRehash(stored, ctx.passwordParams)) {
                    runCatching {
                        ctx.repos.execRaw(
                            "UPDATE users SET password_hash = ? WHERE id = ?",
                            PasswordHash.hash(password, ctx.passwordParams, ctx.pepper), userId
                        )
                    }
                }

                call.respondText(
                    ok().put("data", JSONObject()
                        .put("userId", userId)
                        .put("uid", row["uid"])
                        .put("uidDisplay", Protocol.formatUid((row["uid"] as Number).toLong()))
                        .put("nickname", row["nickname"])
                        .put("avatarUrl", row["avatar_url"])
                        .put("accessToken", token)
                        .put("expiresIn", ctx.sessionTtlMs / 1000)).toString(),
                    ContentType.Application.Json
                )
            }

            post("/auth/logout") {
                val userId = ctx.authenticateCall(call)
                ctx.repos.execRaw("DELETE FROM sessions WHERE user_id = ?", userId)
                call.respondText(ok().toString(), ContentType.Application.Json)
            }

            get("/me") {
                val userId = ctx.authenticateCall(call)
                val row = ctx.repos.userIdRow(userId) ?: throw ApiError(ErrorCode.NOT_FOUND)
                val user = ctx.repos.toUser(row)
                call.respondText(ok().put("data", with(ctx.repos) { user.toJson() }).toString(), ContentType.Application.Json)
            }

            post("/me/profile") {
                val userId = ctx.authenticateCall(call)
                val body = JSONObject(call.receiveText().ifBlank { "{}" })
                val nickname = body.optString("nickname").takeIf { it.isNotBlank() }
                nickname?.let {
                    if (it.length < Protocol.NICKNAME_MIN || it.length > Protocol.NICKNAME_MAX) {
                        throw ApiError(ErrorCode.BAD_REQUEST, "昵称长度需在 1-${Protocol.NICKNAME_MAX} 之间")
                    }
                }
                val signature = body.optString("signature").takeIf { it.isNotBlank() }?.take(100)
                ctx.repos.updateProfile(userId, nickname, signature, null)
                call.respondText(ok().toString(), ContentType.Application.Json)
            }

            post("/me/avatar") {
                val userId = ctx.authenticateCall(call)
                val body = JSONObject(call.receiveText().ifBlank { "{}" })
                val mime = body.optString("mime").lowercase(Locale.ROOT)
                val dataB64 = body.optString("data")
                if (mime !in AVATAR_MIMES) throw ApiError(ErrorCode.BAD_REQUEST, "仅支持 JPEG/PNG/WebP")
                val bytes = runCatching { Bytes.fromBase64(dataB64) }.getOrNull()
                    ?: throw ApiError(ErrorCode.BAD_REQUEST, "头像数据格式错误")
                if (bytes.isEmpty() || bytes.size > Protocol.MAX_AVATAR_BYTES) {
                    throw ApiError(ErrorCode.BAD_REQUEST, "头像大小需在 1B - ${Protocol.MAX_AVATAR_BYTES / 1024 / 1024}MB 之间")
                }

                val dir = File(ctx.config.uploadDir)
                if (!dir.exists() && !dir.mkdirs()) throw ApiError(ErrorCode.INTERNAL, "无法创建上传目录")
                val name = FileNames.randomFor(mime)
                File(dir, name).writeBytes(bytes)

                val url = "${ctx.config.publicBaseUrl.trimEnd('/')}/files/$name"
                ctx.repos.updateProfile(userId, null, null, url)
                call.respondText(ok().put("data", JSONObject().put("avatarUrl", url)).toString(), ContentType.Application.Json)
            }

            get("/users/{uid}") {
                ctx.authenticateCall(call)
                val uid = call.parameters["uid"]?.toLongOrNull() ?: throw ApiError(ErrorCode.BAD_REQUEST)
                val row = ctx.repos.findByUid(uid) ?: throw ApiError(ErrorCode.INVALID_UID)
                val user = ctx.repos.toUser(row)
                call.respondText(
                    ok().put("data", JSONObject()
                        .put("uid", user.uid)
                        .put("uidDisplay", Protocol.formatUid(user.uid))
                        .put("username", user.username)
                        .put("nickname", user.nickname)
                        .put("avatarUrl", user.avatarUrl)
                        .put("signature", user.signature)).toString(),
                    ContentType.Application.Json
                )
            }

            post("/keys/upload") {
                val userId = ctx.authenticateCall(call)
                if (!ctx.limiter.allow("keys:$userId", ctx.limits.keyUploadPerHourPerUser, 3600_000)) throw ApiError(ErrorCode.RATE_LIMITED)
                val body = JSONObject(call.receiveText().ifBlank { "{}" })

                val idEd = body.optString("identityEd25519Pub")
                val idX = body.optString("identityX25519Pub")
                requireB64(idEd, 32); requireB64(idX, 32)
                ctx.repos.uploadIdentity(userId, idEd, idX)

                val spk = body.optJSONObject("signedPreKey") ?: throw ApiError(ErrorCode.BAD_REQUEST, "缺少 signedPreKey")
                val spkPub = spk.optString("publicKey"); val spkSig = spk.optString("signature")
                requireB64(spkPub, 32); requireB64(spkSig, 64)
                ctx.repos.rotateSignedPreKey(userId, spk.getInt("id"), spkPub, spkSig)

                val opks = mutableListOf<Pair<Int, String>>()
                body.optJSONArray("oneTimePreKeys")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val pub = o.optString("publicKey")
                        requireB64(pub, 32)
                        opks += o.getInt("id") to pub
                    }
                }
                if (opks.isNotEmpty()) ctx.repos.addOneTimePreKeys(userId, opks.take(200))

                call.respondText(
                    ok().put("data", JSONObject().put("remainingOneTimePreKeys", ctx.repos.remainingOneTimePreKeys(userId))).toString(),
                    ContentType.Application.Json
                )
            }

            /** 打开（或创建）与某人的单聊会话；标准模式与端到端模式各一条。 */
            post("/conversations/direct") {
                val userId = ctx.authenticateCall(call)
                val body = JSONObject(call.receiveText().ifBlank { "{}" })
                val peerUid = body.optLong("peerUid", -1L)
                val mode = if (body.optInt("encryptionMode", 1) == 2) 2 else 1
                val peerId = ctx.repos.findUserIdByUid(peerUid) ?: throw ApiError(ErrorCode.INVALID_UID)
                if (peerId == userId) throw ApiError(ErrorCode.BAD_REQUEST, "不能与自己聊天")
                if (!ctx.repos.areFriends(userId, peerId)) throw ApiError(ErrorCode.NOT_FRIENDS)

                val conversationId = ctx.repos.findOrCreateDirect(userId, peerId, mode)
                val peer = ctx.repos.toUser(ctx.repos.userIdRow(peerId)!!)
                call.respondText(
                    ok().put("data", JSONObject()
                        .put("conversationId", conversationId)
                        .put("encryptionMode", mode)
                        .put("peer", JSONObject()
                            .put("uid", peer.uid)
                            .put("uidDisplay", Protocol.formatUid(peer.uid))
                            .put("nickname", peer.nickname)
                            .put("avatarUrl", peer.avatarUrl))).toString(),
                    ContentType.Application.Json
                )
            }

            get("/keys/bundle") {
                val userId = ctx.authenticateCall(call)
                val uid = call.request.queryParameters["uid"]?.toLongOrNull() ?: throw ApiError(ErrorCode.BAD_REQUEST)
                val row = ctx.repos.findByUid(uid) ?: throw ApiError(ErrorCode.INVALID_UID)
                val targetId = (row["id"] as Number).toLong()

                val spk = ctx.repos.activeSignedPreKey(targetId)
                    ?: throw ApiError(ErrorCode.PREKEY_EXHAUSTED, "对方尚未上传密钥")
                val opk = ctx.repos.consumeOneTimePreKey(targetId)

                val data = JSONObject()
                    .put("userId", targetId)
                    .put("uid", uid)
                    .put("identityEd25519Public", row["identity_ed"] as? String ?: "")
                    .put("identityX25519Public", row["identity_x"] as? String ?: "")
                    .put("signedPreKeyId", spk["spk_id"] as Number)
                    .put("signedPreKeyPublic", spk["public_key"] as String)
                    .put("signedPreKeySignature", spk["signature"] as String)
                if (opk != null) {
                    data.put("oneTimePreKeyId", opk["opk_id"] as Number)
                        .put("oneTimePreKeyPublic", opk["public_key"] as String)
                } else {
                    data.put("oneTimePreKeyId", JSONObject.NULL).put("oneTimePreKeyPublic", JSONObject.NULL)
                }
                call.respondText(ok().put("data", data).toString(), ContentType.Application.Json)
            }
        }

        get("/files/{name}") {
            // 只按随机文件名读取，拒绝任何含路径分隔符的输入
            val name = call.parameters["name"] ?: throw ApiError(ErrorCode.NOT_FOUND)
            if (name.contains('/') || name.contains('\\') || name.contains("..")) throw ApiError(ErrorCode.NOT_FOUND)
            val file = File(File(ctx.config.uploadDir), name)
            if (!file.exists() || !file.isFile) throw ApiError(ErrorCode.NOT_FOUND)
            val mime = when (file.extension.lowercase(Locale.ROOT)) {
                "png" -> ContentType.Image.PNG
                "webp" -> ContentType.parse("image/webp")
                else -> ContentType.Image.JPEG
            }
            call.respondBytes(file.readBytes(), mime, HttpStatusCode.OK)
        }
    }
}

private fun requireB64(value: String, expectedBytes: Int) {
    val decoded = runCatching { Bytes.fromBase64(value) }.getOrNull()
    if (decoded == null || decoded.size != expectedBytes) {
        throw ApiError(ErrorCode.BAD_REQUEST, "字段需为标准 Base64 且解码后 $expectedBytes 字节")
    }
}

internal fun ok(): JSONObject = JSONObject().put("ok", true)
