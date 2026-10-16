package com.enchat.app.data.remote

import com.enchat.app.BuildConfig
import com.enchat.crypto.Bytes
import com.enchat.crypto.TransportCrypto
import com.enchat.crypto.TransportSession
import com.enchat.protocol.FrameType
import com.enchat.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** WebSocket 链路状态（对应需求里的顶部状态条）。 */
enum class LinkState { IDLE, CONNECTING, HANDSHAKING, LINKED, FAILED }

/**
 * WebSocket 客户端：传输层握手 + 加密帧收发 + 自动重连。
 *
 * 关键约束（与服务端一致）：
 * - 握手前两个字节帧是明文（clientHello 48B / serverHello 112B），之后**只允许加密二进制帧**；
 * - 收到任何 Text 帧直接判定为协议违规并断开；
 * - 服务器长期公钥必须预先 pinning（[staticKeys]），否则中间人可以冒充服务器完成握手。
 */
class WsClient(
    private val hostProvider: () -> String,
    private val tokenProvider: () -> String?,
    private val staticKeysProvider: () -> TransportCrypto.ServerStaticKeys,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)   // 长连接，不设读超时
        .pingInterval(25, TimeUnit.SECONDS)      // 业务层也有 PING，两者互补
        .build()

    private val _linkState = MutableStateFlow(LinkState.IDLE)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val incoming = _incoming.asSharedFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var session: TransportSession? = null

    private val ridSeq = AtomicInteger(0)

    /** 需要鉴权时发送的 token 快照；重连后重新发送 AUTH。 */
    private var pendingAuth: JSONObject? = null

    fun isLinked(): Boolean = _linkState.value == LinkState.LINKED

    /** 建立连接；[auth] 为鉴权帧 payload。已在连接中则忽略。 */
    fun connect(auth: JSONObject) {
        pendingAuth = auth
        if (_linkState.value == LinkState.CONNECTING || _linkState.value == LinkState.HANDSHAKING) return

        _linkState.value = LinkState.CONNECTING
        val request = Request.Builder()
            .url(hostProvider().replace("http://", "ws://").replace("https://", "wss://") + Protocol.WS_PATH)
            .build()

        socket = http.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                _linkState.value = LinkState.HANDSHAKING
                val handshake = TransportCrypto.beginClientHandshake()
                pendingHandshake = handshake
                webSocket.send(handshake.helloBytes().toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                val current = session
                if (current == null) {
                    // 服务端回的第一个二进制帧 = serverHello
                    val handshake = pendingHandshake ?: return
                    runCatching {
                        session = handshake.complete(data, staticKeysProvider())
                    }.onFailure { t ->
                        _lastError.value = "服务器身份验证失败：${t.message}"
                        _linkState.value = LinkState.FAILED
                        webSocket.close(1002, "bad handshake")
                        return
                    }
                    _linkState.value = LinkState.LINKED
                    pendingAuth?.let { send(FrameType.AUTH, it) }
                    return
                }
                runCatching { current.open(data) }
                    .onSuccess { payload ->
                        val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
                        if (json != null) _incoming.tryEmit(json)
                    }
                    .onFailure {
                        // 解密失败/序号错乱：断开重连（客户端会重新握手）
                        _lastError.value = "收到无法解密的帧"
                        webSocket.close(1002, "bad frame")
                    }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 传输层建立后绝不接受明文帧
                _lastError.value = "收到明文帧，连接已断开"
                webSocket.close(1002, "plaintext not allowed")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _lastError.value = t.message
                _linkState.value = if (_linkState.value == LinkState.LINKED) LinkState.CONNECTING else LinkState.FAILED
                session?.zeroize()
                session = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                session?.zeroize()
                session = null
                if (_linkState.value != LinkState.FAILED) _linkState.value = LinkState.CONNECTING
            }
        })
    }

    @Volatile
    private var pendingHandshake: TransportCrypto.ClientHandshake? = null

    /** 发送业务帧；未连通时丢弃并返回 false（由上层入队重试）。 */
    fun send(type: FrameType, payload: JSONObject, rid: String? = null): Boolean {
        val s = session ?: return false
        val frame = jsonObjectOf("t" to type.code, "payload" to payload)
        if (rid != null) frame.put("rid", rid)
        else frame.put("rid", nextRid())
        return runCatching {
            socket?.send(s.seal(frame.toString().toByteArray(Charsets.UTF_8)).toByteString())
            true
        }.getOrDefault(false)
    }

    fun nextRid(): String = "c-${ridSeq.incrementAndGet()}"

    /** 退避重连：由外部（Repository）按 [ReconnectPolicy] 的节奏调用。 */
    fun reconnectAfter(delayMs: Long) {
        scope.launch {
            delay(delayMs)
            val auth = pendingAuth
            if (auth != null && _linkState.value != LinkState.LINKED) connect(auth)
        }
    }

    fun close() {
        session?.zeroize()
        session = null
        runCatching { socket?.close(1000, "bye") }
        _linkState.value = LinkState.IDLE
    }

    companion object {
        /**
         * 从 BuildConfig 读取 pinning 的服务器公钥。
         *
         * ⚠️ 默认值 "REPLACE_WITH_YOUR_SERVER_KEY" 会导致启动即失败，
         * 这是**故意**的：宁可显式失败，也不要静默地接受任意服务器。
         */
        fun pinnedKeys(): TransportCrypto.ServerStaticKeys {
            val ed = BuildConfig.SERVER_ED25519_PUB_B64
            val x = BuildConfig.SERVER_X25519_PUB_B64
            require(!ed.contains("REPLACE_WITH")) { "未配置服务器公钥（pinning 缺失）" }
            require(!x.contains("REPLACE_WITH")) { "未配置服务器公钥（pinning 缺失）" }
            val edBytes = Bytes.fromBase64(ed)
            val xBytes = Bytes.fromBase64(x)
            require(edBytes.size == 32 && xBytes.size == 32) { "服务器公钥长度不正确" }
            return TransportCrypto.ServerStaticKeys(edBytes, xBytes)
        }
    }
}
