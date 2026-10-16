package com.enchat.app.data.remote

import com.enchat.crypto.Bytes
import com.enchat.crypto.IdentityKeyPair
import com.enchat.crypto.OneTimePreKey
import com.enchat.crypto.PreKeyBundle
import com.enchat.crypto.SignedPreKey
import com.enchat.protocol.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** REST 调用的业务异常（携带服务端错误码）。 */
class ApiException(val code: Int, message: String) : IOException(message)

/**
 * 极简 REST 客户端。
 *
 * 说明：注册/登录等接口返回的令牌只在内存与 Keystore 加密的 SharedPreferences 中流转，
 * 不写入任何日志（因此这里**没有**开 HttpLoggingInterceptor，避免令牌落 logcat）。
 */
class ApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun register(username: String, password: String, nickname: String): AuthResult =
        post("/auth/register") {
            put("username", username)
            put("password", password)
            put("nickname", nickname)
        }.let { AuthResult.from(it) }

    suspend fun login(username: String, password: String): AuthResult =
        post("/auth/login") {
            put("username", username)
            put("password", password)
        }.let { AuthResult.from(it) }

    suspend fun logout() {
        try {
            post("/auth/logout") { }
        } catch (_: Exception) {
            // 服务端吊销失败不影响本地清理
        }
    }

    suspend fun me(): JSONObject = get("/me").let { parse(it).getJSONObject("data") }

    suspend fun updateProfile(nickname: String?, signature: String?) {
        post("/me/profile") {
            nickname?.let { put("nickname", it) }
            signature?.let { put("signature", it) }
        }
    }

    suspend fun uploadAvatar(mime: String, bytes: ByteArray): String {
        val json = post("/me/avatar") {
            put("mime", mime)
            put("data", Bytes.toBase64(bytes))
        }
        return parse(json).getJSONObject("data").getString("avatarUrl")
    }

    suspend fun user(uid: Long): JSONObject =
        parse(get("/users/$uid")).getJSONObject("data")

    suspend fun openDirect(peerUid: Long, mode: Int): DirectConversation {
        val json = post("/conversations/direct") {
            put("peerUid", peerUid)
            put("encryptionMode", mode)
        }
        val data = parse(json).getJSONObject("data")
        return DirectConversation(
            conversationId = data.getLong("conversationId"),
            encryptionMode = data.getInt("encryptionMode")
        )
    }

    suspend fun uploadKeys(
        identity: IdentityKeyPair,
        signedPreKey: SignedPreKey,
        oneTimePreKeys: List<OneTimePreKey>
    ) {
        val arr = JSONArray()
        oneTimePreKeys.forEach { k ->
            arr.put(jsonObjectOf("id" to k.id, "publicKey" to Bytes.toBase64(k.publicKey)))
        }
        post("/keys/upload") {
            put("identityEd25519Pub", Bytes.toBase64(identity.ed25519PublicKey))
            put("identityX25519Pub", Bytes.toBase64(identity.x25519PublicKey))
            put("signedPreKey", jsonObjectOf(
                "id" to signedPreKey.id,
                "publicKey" to Bytes.toBase64(signedPreKey.publicKey),
                "signature" to Bytes.toBase64(signedPreKey.signature)
            ))
            put("oneTimePreKeys", arr)
        }
    }

    suspend fun keyBundle(uid: Long): PreKeyBundle {
        val data = parse(get("/keys/bundle?uid=$uid")).getJSONObject("data")
        val opkId = if (data.isNull("oneTimePreKeyId")) null else data.getInt("oneTimePreKeyId")
        val opkPub = if (data.isNull("oneTimePreKeyPublic")) null
        else Bytes.fromBase64(data.getString("oneTimePreKeyPublic"))
        return PreKeyBundle(
            userId = data.getLong("userId"),
            identityEd25519Public = Bytes.fromBase64(data.getString("identityEd25519Public")),
            identityX25519Public = Bytes.fromBase64(data.getString("identityX25519Public")),
            signedPreKeyId = data.getInt("signedPreKeyId"),
            signedPreKeyPublic = Bytes.fromBase64(data.getString("signedPreKeyPublic")),
            signedPreKeySignature = Bytes.fromBase64(data.getString("signedPreKeySignature")),
            oneTimePreKeyId = opkId,
            oneTimePreKeyPublic = opkPub
        )
    }

    // ---------------- 底层 ----------------

    private suspend fun get(path: String): String = call(Request.Builder().url(url(path)).get())

    /**
     * 注意：body 的接收者是 [MutableMap] 而不是 JSONObject。
     * org.json 的 put 存在 Object/int/long/boolean/double 多套重载，Kotlin 容易出现
     * "Overload resolution ambiguity"；MutableMap.put(String, Any) 没有这个问题。
     */
    private suspend fun post(path: String, body: MutableMap<String, Any>.() -> Unit): String {
        val map = linkedMapOf<String, Any>()
        map.body()
        val json = jsonObjectOf(*map.toList().toTypedArray()).toString()
        return call(Request.Builder().url(url(path)).post(json.toRequestBody(jsonMedia)))
    }

    private fun url(path: String): String = baseUrl + Protocol.API_PREFIX + path

    private suspend fun call(builder: Request.Builder): String = withContext(Dispatchers.IO) {
        tokenProvider()?.let { token -> builder.header("Authorization", "Bearer $token") }
        http.newCall(builder.build()).execute().use { resp ->
            val payload: String = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // 先解析一次 JSON，再取字段。
                // 不要写成 runCatching { JSONObject(p).optInt("err", resp.code) }：
                // 默认值位置参与重载选择，类型一出错就报 "overload resolution ambiguity"。
                val errBody = try { JSONObject(payload) } catch (_: Exception) { null }
                val code: Int = errBody?.optInt("err", resp.code) ?: resp.code
                val msg: String = errBody?.optString("msg")?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                throw ApiException(code, msg)
            }
            payload
        }
    }

    private fun parse(raw: String): JSONObject {
        val json = JSONObject(raw)
        if (!json.optBoolean("ok", false)) {
            throw ApiException(json.optInt("err", -1), json.optString("msg", "请求失败"))
        }
        return json
    }

    data class AuthResult(
        val userId: Long,
        val uid: Long,
        val uidDisplay: String,
        val nickname: String,
        val avatarUrl: String,
        val accessToken: String,
        val passwordScore: Int
    ) {
        companion object {
            fun from(raw: String): AuthResult {
                val data = JSONObject(raw).getJSONObject("data")
                return AuthResult(
                    userId = data.getLong("userId"),
                    uid = data.getLong("uid"),
                    uidDisplay = data.optString("uidDisplay", Protocol.formatUid(data.getLong("uid"))),
                    nickname = data.optString("nickname", ""),
                    avatarUrl = data.optString("avatarUrl", ""),
                    accessToken = data.getString("accessToken"),
                    passwordScore = data.optInt("passwordScore", 0)
                )
            }
        }
    }

    data class DirectConversation(val conversationId: Long, val encryptionMode: Int)
}
