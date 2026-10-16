package com.enchat.crypto

/**
 * 客户端 ↔ 服务器传输层加密（"默认加密"）。
 *
 * 定位：在 TLS 之上再加一层**应用层信封加密**。
 * - 传输安全的第一道防线仍然是 TLS 1.3（防中间人、防窃听），这一层并不替代 TLS。
 * - 加这一层的目的：即使 TLS 在负载均衡/网关处被终止，或网关/APM/日志组件接触到明文流量，
 *   业务载荷依然是密文；只有真正到达业务服务器进程后才解密成明文入库（符合需求"到服务器后解密成明文保存"）。
 *
 * 握手（带服务器身份认证）：
 *   C → S: clientEphPub(32) || clientNonce(16)
 *   S → C: serverEphPub(32) || serverNonce(16) || Ed25519签名(64)
 *   签名数据 = "ENCHAT-TS-HS-v1" || clientEphPub || clientNonce || serverEphPub || serverNonce
 *
 * 关键前提：客户端**必须**通过硬编码/证书固定（pinning）获得服务器长期公钥 [ServerStaticKeys]，
 * 否则中间人可以用自己的密钥完成握手，这一层加密将形同虚设。见 docs/01-架构设计.md 的风险条目。
 */
object TransportCrypto {

    private val HS_DOMAIN: ByteArray = "ENCHAT-TS-HS-v1".toByteArray()
    private val KDF_DOMAIN: ByteArray = "ENCHAT-TS-v1".toByteArray()
    internal val FRAME_VERSION: ByteArray = byteArrayOf(1)

    const val CLIENT_HELLO_SIZE = 32 + 16
    const val SERVER_HELLO_SIZE = 32 + 16 + 64

    /** 服务器长期公钥（必须预置在客户端内，随版本更新，更新时要求用户确认或使用多版本 pin 列表）。 */
    data class ServerStaticKeys(val ed25519Public: ByteArray, val x25519Public: ByteArray) {
        init {
            require(ed25519Public.size == 32) { "server ed25519 public must be 32 bytes" }
            require(x25519Public.size == 32) { "server x25519 public must be 32 bytes" }
        }
    }

    /** 客户端握手状态（握手中途需保存在内存，完成后即可丢弃）。 */
    class ClientHandshake internal constructor(
        private val ephemeral: X25519KeyPair,
        private val nonce: ByteArray
    ) {
        fun helloBytes(): ByteArray = Bytes.concat(ephemeral.publicKey, nonce)

        /**
         * 校验服务器响应并完成握手。
         * @throws IllegalArgumentException 服务器签名无效（可能是中间人或服务器密钥已轮换）
         */
        fun complete(serverHello: ByteArray, serverStatic: ServerStaticKeys): TransportSession {
            require(serverHello.size == SERVER_HELLO_SIZE) { "server hello length mismatch: ${serverHello.size}" }
            val serverEph = serverHello.copyOfRange(0, 32)
            val serverNonce = serverHello.copyOfRange(32, 48)
            val signature = serverHello.copyOfRange(48, 112)

            val signedData = Bytes.concat(
                HS_DOMAIN,
                Bytes.writeLp(ephemeral.publicKey),
                Bytes.writeLp(nonce),
                Bytes.writeLp(serverEph),
                Bytes.writeLp(serverNonce)
            )
            require(Primitives.ed25519Verify(serverStatic.ed25519Public, signedData, signature)) {
                "server handshake signature verification failed (possible MITM or rotated server key)"
            }

            var dh1: ByteArray? = null
            var dh2: ByteArray? = null
            try {
                dh1 = Primitives.x25519SharedSecret(ephemeral.privateKey, serverStatic.x25519Public)
                dh2 = Primitives.x25519SharedSecret(ephemeral.privateKey, serverEph)
                val transcript = Bytes.concat(
                    KDF_DOMAIN,
                    Bytes.writeLp(ephemeral.publicKey),
                    Bytes.writeLp(serverEph),
                    Bytes.writeLp(nonce),
                    Bytes.writeLp(serverNonce)
                )
                val ikm = Bytes.concat(dh1, dh2)
                val salt = Bytes.concat(nonce, serverNonce)
                val channelId = Primitives.sha256(transcript).copyOfRange(0, 16)
                val c2s = Primitives.hkdf(ikm, salt, Bytes.concat(transcript, "c2s".toByteArray()), 36)
                val s2c = Primitives.hkdf(ikm, salt, Bytes.concat(transcript, "s2c".toByteArray()), 36)
                try {
                    return TransportSession(
                        channelId = channelId,
                        sendKey = c2s.copyOfRange(0, 32),
                        sendNoncePrefix = c2s.copyOfRange(32, 36),
                        receiveKey = s2c.copyOfRange(0, 32),
                        receiveNoncePrefix = s2c.copyOfRange(32, 36)
                    )
                } finally {
                    Bytes.zeroize(c2s); Bytes.zeroize(s2c)
                }
            } finally {
                dh1?.let { Bytes.zeroize(it) }
                dh2?.let { Bytes.zeroize(it) }
            }
        }
    }

    fun beginClientHandshake(): ClientHandshake =
        ClientHandshake(Primitives.x25519GenerateKeyPair(), Bytes.random(16))

    /**
     * 服务器端处理 client hello，返回 (serverHello, 本连接的 TransportSession)。
     * @param serverIdentity 服务器长期密钥（Ed25519 用于签名，X25519 用于 DH）
     */
    fun respondAsServer(
        clientHello: ByteArray,
        serverIdentity: IdentityKeyPair
    ): Pair<ByteArray, TransportSession> {
        require(clientHello.size == CLIENT_HELLO_SIZE) { "client hello length mismatch: ${clientHello.size}" }
        val clientEph = clientHello.copyOfRange(0, 32)
        val clientNonce = clientHello.copyOfRange(32, 48)

        val serverEph = Primitives.x25519GenerateKeyPair()
        val serverNonce = Bytes.random(16)
        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        try {
            dh1 = Primitives.x25519SharedSecret(serverIdentity.x25519PrivateKey, clientEph)
            dh2 = Primitives.x25519SharedSecret(serverEph.privateKey, clientEph)

            val signedData = Bytes.concat(
                HS_DOMAIN,
                Bytes.writeLp(clientEph),
                Bytes.writeLp(clientNonce),
                Bytes.writeLp(serverEph.publicKey),
                Bytes.writeLp(serverNonce)
            )
            val serverHello = Bytes.concat(
                serverEph.publicKey, serverNonce, serverIdentity.sign(signedData)
            )

            val transcript = Bytes.concat(
                KDF_DOMAIN,
                Bytes.writeLp(clientEph),
                Bytes.writeLp(serverEph.publicKey),
                Bytes.writeLp(clientNonce),
                Bytes.writeLp(serverNonce)
            )
            val ikm = Bytes.concat(dh1, dh2)
            val salt = Bytes.concat(clientNonce, serverNonce)
            val channelId = Primitives.sha256(transcript).copyOfRange(0, 16)
            // 注意方向：服务器的 send = s2c，receive = c2s
            val c2s = Primitives.hkdf(ikm, salt, Bytes.concat(transcript, "c2s".toByteArray()), 36)
            val s2c = Primitives.hkdf(ikm, salt, Bytes.concat(transcript, "s2c".toByteArray()), 36)
            try {
                val session = TransportSession(
                    channelId = channelId,
                    sendKey = s2c.copyOfRange(0, 32),
                    sendNoncePrefix = s2c.copyOfRange(32, 36),
                    receiveKey = c2s.copyOfRange(0, 32),
                    receiveNoncePrefix = c2s.copyOfRange(32, 36)
                )
                return serverHello to session
            } finally {
                Bytes.zeroize(c2s); Bytes.zeroize(s2c)
            }
        } finally {
            Bytes.zeroize(serverEph.privateKey)
            dh1?.let { Bytes.zeroize(it) }
            dh2?.let { Bytes.zeroize(it) }
        }
    }
}

/**
 * 传输层会话：对每条业务帧做 AES-256-GCM 密封。
 *
 * 帧格式：version(1) || seq(8, BE) || noncePrefix(4) || ciphertextWithTag
 * nonce = noncePrefix(4) || seq(8) = 12 字节；AAD = version || seq || channelId(16)
 *
 * 重放防护：单一有序流（WebSocket over TCP），要求 seq 严格等于下一个期望值。
 * 若将来改成 UDP/多连接并发，需要替换为滑动窗口（本实现未覆盖，属已知限制）。
 */
class TransportSession internal constructor(
    val channelId: ByteArray,
    private val sendKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val receiveKey: ByteArray,
    private val receiveNoncePrefix: ByteArray
) {
    private var sendSeq = 0L
    private var expectedReceiveSeq = 0L

    init {
        require(channelId.size == 16) { "channelId must be 16 bytes" }
        require(sendKey.size == 32 && receiveKey.size == 32) { "transport keys must be 32 bytes" }
        require(sendNoncePrefix.size == 4 && receiveNoncePrefix.size == 4) { "nonce prefix must be 4 bytes" }
    }

    val nextSendSeq: Long get() = sendSeq
    val nextExpectedReceiveSeq: Long get() = expectedReceiveSeq

    /** 单条密钥下的安全上限：GCM 建议不超过 2^32 条消息，超出必须重新握手。 */
    private val maxFrames = 1L shl 32

    @Synchronized
    fun seal(plaintext: ByteArray): ByteArray {
        if (sendSeq >= maxFrames) throw CryptoException("transport key exhausted, must re-handshake")
        val seq = sendSeq
        val nonce = Bytes.concat(sendNoncePrefix, Bytes.writeUInt64BE(seq))
        val aad = Bytes.concat(TransportCrypto.FRAME_VERSION, Bytes.writeUInt64BE(seq), channelId)
        val ct = Primitives.aes256GcmEncrypt(sendKey, nonce, plaintext, aad)
        sendSeq = seq + 1
        return Bytes.concat(TransportCrypto.FRAME_VERSION, Bytes.writeUInt64BE(seq), sendNoncePrefix, ct)
    }

    @Synchronized
    fun open(frame: ByteArray): ByteArray {
        if (frame.size < 1 + 8 + 4 + Primitives.GCM_TAG_SIZE_BYTES) {
            throw CryptoException("transport frame too short: ${frame.size}")
        }
        if (frame[0] != 1.toByte()) throw CryptoException("unsupported transport frame version: ${frame[0]}")
        val seq = Bytes.readUInt64BE(frame, 1)
        val noncePrefix = frame.copyOfRange(9, 13)
        val ct = frame.copyOfRange(13, frame.size)
        if (!Bytes.constantTimeEquals(noncePrefix, receiveNoncePrefix)) {
            throw CryptoException("transport frame nonce prefix mismatch")
        }
        if (seq != expectedReceiveSeq) {
            throw CryptoException("transport frame out of order or replayed: got $seq, expected $expectedReceiveSeq")
        }
        val nonce = Bytes.concat(receiveNoncePrefix, Bytes.writeUInt64BE(seq))
        val aad = Bytes.concat(TransportCrypto.FRAME_VERSION, Bytes.writeUInt64BE(seq), channelId)
        val plaintext = Primitives.aes256GcmDecrypt(receiveKey, nonce, ct, aad)
        expectedReceiveSeq = seq + 1
        return plaintext
    }

    fun zeroize() {
        Bytes.zeroize(sendKey)
        Bytes.zeroize(receiveKey)
    }
}
