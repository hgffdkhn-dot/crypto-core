package com.enchat.server

import com.enchat.crypto.Bytes
import com.enchat.crypto.TransportCrypto
import com.enchat.crypto.TransportSession
import com.enchat.protocol.FrameType
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 集成测试用的"迷你客户端"：封装 REST 调用 + WebSocket 传输层握手 + 加密帧收发。
 *
 * 它刻意只依赖 JDK 自带的 java.net.http.WebSocket，不引入测试专用的客户端框架。
 * 这里的流程与 Android 客户端要实现的流程是一致的，可作为客户端实现的参考。
 */
class TestClient(
    private val baseUrl: String,
    private val serverStatic: TransportCrypto.ServerStaticKeys
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val frames = ConcurrentLinkedQueue<JSONObject>()
    private val binaryQueue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var ws: WebSocket? = null

    @Volatile
    var transport: TransportSession? = null

    var token: String = ""
    var uid: Long = 0
        private set
    var userId: Long = 0
        private set

    // ---------------- REST ----------------

    private fun request(method: String, path: String, body: JSONObject? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            .timeout(Duration.ofSeconds(60))
            .header("Content-Type", "application/json")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
            ?: HttpRequest.BodyPublishers.noBody())
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    /** 不带 Authorization 头发一次 GET，用于验证未鉴权的 HTTP 状态码。 */
    fun getNoAuth(path: String): HttpResponse<String> =
        http.send(
            HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    fun register(username: String, password: String, nickname: String = username): JSONObject {
        val res = request("POST", "/api/v1/auth/register", JSONObject()
            .put("username", username).put("password", password).put("nickname", nickname))
        val json = JSONObject(res.body())
        if (json.optBoolean("ok")) {
            token = json.getJSONObject("data").getString("accessToken")
            uid = json.getJSONObject("data").getLong("uid")
            userId = json.getJSONObject("data").getLong("userId")
        }
        return json
    }

    fun login(username: String, password: String): JSONObject {
        val res = request("POST", "/api/v1/auth/login", JSONObject()
            .put("username", username).put("password", password))
        val json = JSONObject(res.body())
        if (json.optBoolean("ok")) {
            token = json.getJSONObject("data").getString("accessToken")
            uid = json.getJSONObject("data").getLong("uid")
            userId = json.getJSONObject("data").getLong("userId")
        }
        return json
    }

    fun uploadKeys(identityEd: String, identityX: String, spkId: Int, spkPub: String, spkSig: String, opks: List<Pair<Int, String>>): JSONObject {
        val arr = org.json.JSONArray()
        opks.forEach { (id, pub) -> arr.put(JSONObject().put("id", id).put("publicKey", pub)) }
        return JSONObject(request("POST", "/api/v1/keys/upload", JSONObject()
            .put("identityEd25519Pub", identityEd)
            .put("identityX25519Pub", identityX)
            .put("signedPreKey", JSONObject().put("id", spkId).put("publicKey", spkPub).put("signature", spkSig))
            .put("oneTimePreKeys", arr)).body())
    }

    fun bundle(peerUid: Long): JSONObject =
        JSONObject(request("GET", "/api/v1/keys/bundle?uid=$peerUid").body())

    fun direct(peerUid: Long, mode: Int): JSONObject =
        JSONObject(request("POST", "/api/v1/conversations/direct", JSONObject()
            .put("peerUid", peerUid).put("encryptionMode", mode)).body())

    fun setProfile(nickname: String): JSONObject =
        JSONObject(request("POST", "/api/v1/me/profile", JSONObject().put("nickname", nickname)).body())

    fun uploadAvatar(mime: String, dataB64: String): JSONObject =
        JSONObject(request("POST", "/api/v1/me/avatar", JSONObject().put("mime", mime).put("data", dataB64)).body())

    fun fetchAvatar(path: String): HttpResponse<String> {
        val req = HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build()
        return http.send(req, HttpResponse.BodyHandlers.ofString())
    }

    // ---------------- WebSocket ----------------

    /** 建立 WS 并完成传输层握手 + 鉴权；返回 AUTH_RESULT。 */
    fun connect(auth: Boolean = true): JSONObject {
        val handshake = TransportCrypto.beginClientHandshake()
        val helloDone = CountDownLatch(1)
        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                ws = webSocket
                webSocket.request(1)
                webSocket.sendBinary(ByteBuffer.wrap(handshake.helloBytes()), true)
            }

            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                if (transport == null) {
                    try {
                        transport = handshake.complete(bytes, serverStatic)
                    } catch (t: Throwable) {
                        handshakeError = t.message
                    }
                    helloDone.countDown()
                } else {
                    runCatching {
                        val payload = transport!!.open(bytes)
                        frames.add(JSONObject(String(payload, Charsets.UTF_8)))
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                webSocket.request(1)
            }
        }

        val url = baseUrl.replace("http://", "ws://").replace("https://", "wss://") + "/ws"
        ws = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .buildAsync(URI.create(url), listener)
            .get(10, TimeUnit.SECONDS)

        if (!helloDone.await(10, TimeUnit.SECONDS)) throw IllegalStateException("传输层握手超时")
        if (transport == null) throw IllegalStateException("传输层握手失败: $handshakeError")

        return if (auth) {
            sendSync(FrameType.AUTH, JSONObject().put("token", token).put("protocolVersion", 1).put("deviceId", "test"))
        } else {
            JSONObject().put("ok", true)
        }
    }

    @Volatile
    var handshakeError: String? = null

    /** 发送业务帧并等待同 rid 的应答。 */
    fun sendSync(type: FrameType, payload: JSONObject, timeoutMs: Long = 5000): JSONObject {
        val rid = "r-${ridSeq.incrementAndGet()}"
        send(type, payload, rid)
        return waitFor({ it.optString("rid") == rid }, timeoutMs)
            ?: throw IllegalStateException("等待 ${type.name} 应答超时（rid=$rid）")
    }

    /** 发送业务帧（不等待应答）。 */
    fun send(type: FrameType, payload: JSONObject, rid: String? = null) {
        val frame = JSONObject().put("t", type.code).put("payload", payload)
        if (rid != null) frame.put("rid", rid)
        val sealed = transport!!.seal(frame.toString().toByteArray(Charsets.UTF_8))
        ws!!.sendBinary(ByteBuffer.wrap(sealed), true)
    }

    /** 在收到的帧中等待满足条件的第一个（已有的会被消费掉）。 */
    fun waitFor(predicate: (JSONObject) -> Boolean, timeoutMs: Long = 5000): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = frames.poll()
            if (frame != null) {
                if (predicate(frame)) return frame
                // 不匹配的帧丢弃（测试里通常只关心特定类型）
                continue
            }
            Thread.sleep(20)
        }
        return null
    }

    fun waitForType(type: FrameType, timeoutMs: Long = 5000): JSONObject? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val skipped = mutableListOf<JSONObject>()
        while (System.currentTimeMillis() < deadline) {
            val frame = frames.poll()
            if (frame == null) {
                Thread.sleep(20)
                continue
            }
            if (frame.optInt("t", -1) == type.code) {
                skipped.forEach { frames.add(it) } // 归还跳过的帧
                return frame
            }
            skipped.add(frame)
        }
        skipped.forEach { frames.add(it) }
        return null
    }

    fun pingPong(): Boolean {
        val res = sendSync(FrameType.PING, JSONObject())
        return res.optInt("t") == FrameType.PONG.code
    }

    fun close() {
        runCatching { ws?.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        runCatching { transport?.zeroize() }
    }

    companion object {
        private val ridSeq = java.util.concurrent.atomic.AtomicInteger(0)
    }
}

/** 便捷：把服务端返回的 bundle 转成 crypto-core 的 PreKeyBundle。 */
fun bundleToPreKeyBundle(data: JSONObject): com.enchat.crypto.PreKeyBundle {
    val opkId = if (data.isNull("oneTimePreKeyId")) null else data.getInt("oneTimePreKeyId")
    val opkPub = if (data.isNull("oneTimePreKeyPublic")) null else Bytes.fromBase64(data.getString("oneTimePreKeyPublic"))
    return com.enchat.crypto.PreKeyBundle(
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
