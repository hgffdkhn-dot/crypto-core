package com.enchat.crypto

/**
 * 端到端加密会话（1:1）。
 *
 * 组成：X3DH-lite 协商出的根密钥 → 派生两条独立的 Hash 链（发送链 / 接收链）→ 每条消息一个一次性 AES-256-GCM 密钥。
 *
 * 线格式（每条消息）：
 *   version(1) || sessionId(16) || chainIndex(4) || ciphertextWithTag(n)
 * AAD（参与认证但不加密）：
 *   version(1) || sessionId(16) || chainIndex(4) || senderIdentityEd25519(32) || receiverIdentityEd25519(32)
 *
 * 体验与安全的平衡：
 * - 允许乱序到达：接收方会预推进链并缓存跳过的消息密钥（上限 [MAX_SKIPPED_KEYS]）。
 * - 防重放：每条消息密钥只能成功解密一次，用过即从缓存移除。
 * - 前向保密：消息密钥使用后立即擦除，链只前进。卸载/清数据后本地密钥丢失 → 历史消息永久不可读。
 */
class E2eeSession internal constructor(
    val sessionId: ByteArray,
    val peerUserId: Long,
    val localIdentityEd25519: ByteArray,
    val peerIdentityEd25519: ByteArray,
    val isInitiator: Boolean,
    private var rootKey: ByteArray,
    private var sendingChain: ChainKey,
    private val receiver: SkippingChainReceiver
) {

    init {
        require(sessionId.size == SESSION_ID_SIZE) { "sessionId must be 16 bytes" }
        require(localIdentityEd25519.size == 32) { "local identity must be 32 bytes" }
        require(peerIdentityEd25519.size == 32) { "peer identity must be 32 bytes" }
        require(rootKey.size == 32) { "root key must be 32 bytes" }
    }

    val sendIndex: Int get() = sendingChain.currentIndex
    val receiveIndex: Int get() = receiver.currentIndex
    val skippedKeyCount: Int get() = receiver.skippedKeyCount

    // ---------------- 加解密 ----------------

    /** 加密一条消息，返回可直接上线的字节序列。线程安全。 */
    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val index = sendingChain.currentIndex
        val messageKey = sendingChain.nextMessageKey()
        try {
            val aad = buildAad(sessionId, index, localIdentityEd25519, peerIdentityEd25519)
            val ciphertext = messageKey.encrypt(plaintext, aad)
            return Bytes.concat(MESSAGE_VERSION, sessionId, Bytes.writeUInt32BE(index), ciphertext)
        } finally {
            messageKey.zeroize()
        }
    }

    /**
     * 解密一条消息。
     * @throws CryptoException 认证失败、重放、超出乱序窗口
     */
    @Synchronized
    fun decrypt(wire: ByteArray): ByteArray {
        if (wire.size < HEADER_SIZE + Primitives.GCM_TAG_SIZE_BYTES) {
            throw CryptoException("e2ee message too short: ${wire.size}")
        }
        val version = wire[0]
        if (version != MESSAGE_VERSION[0]) throw CryptoException("unsupported e2ee message version: $version")
        val sid = wire.copyOfRange(1, 17)
        if (!Bytes.constantTimeEquals(sid, sessionId)) throw CryptoException("session id mismatch")
        val index = Bytes.readUInt32BE(wire, 17)
        if (index < 0) throw CryptoException("negative chain index")
        val ciphertext = wire.copyOfRange(HEADER_SIZE, wire.size)
        val aad = buildAad(sessionId, index, peerIdentityEd25519, localIdentityEd25519)

        val messageKey = receiver.keyFor(index)
        try {
            return messageKey.decrypt(ciphertext, aad)
        } finally {
            messageKey.zeroize()
        }
    }

    // ---------------- 建立会话 ----------------

    companion object {
        const val SESSION_ID_SIZE = 16
        const val MAX_SKIPPED_KEYS = 1000
        private val MESSAGE_VERSION = byteArrayOf(1)
        private const val HEADER_SIZE = 1 + 16 + 4

        /** sessionId 由 InitMessage 内容派生，双方独立计算得到同一值，无需额外传输字段。 */
        fun sessionIdOf(initMessage: X3dh.InitMessage): ByteArray =
            Primitives.sha256(Bytes.concat("ENCHAT-SESSIONID-v1".toByteArray(), initMessage.toBytes()))
                .copyOfRange(0, SESSION_ID_SIZE)

        /** 发起方：创建会话并产出要发送给对方的 InitMessage。 */
        fun createAsSender(
            localIdentity: IdentityKeyPair,
            localUserId: Long,
            peerBundle: PreKeyBundle
        ): Pair<E2eeSession, X3dh.InitMessage> {
            val result = X3dh.initiateAsSender(localIdentity, localUserId, peerBundle)
            val sessionId = sessionIdOf(result.initMessage)
            val session = fromSharedSecret(
                sharedSecret = result.sharedSecret,
                sessionId = sessionId,
                peerUserId = peerBundle.userId,
                localIdentityEd25519 = localIdentity.ed25519PublicKey,
                peerIdentityEd25519 = peerBundle.identityEd25519Public,
                isInitiator = true
            )
            Bytes.zeroize(result.sharedSecret)
            return session to result.initMessage
        }

        /** 接收方：用收到的 InitMessage 创建会话。 */
        fun createAsReceiver(
            localIdentity: IdentityKeyPair,
            signedPreKey: SignedPreKey,
            oneTimePreKey: OneTimePreKey?,
            initMessage: X3dh.InitMessage
        ): E2eeSession {
            val sharedSecret = X3dh.acceptAsReceiver(localIdentity, signedPreKey, oneTimePreKey, initMessage)
            val session = fromSharedSecret(
                sharedSecret = sharedSecret,
                sessionId = sessionIdOf(initMessage),
                peerUserId = initMessage.senderUserId,
                localIdentityEd25519 = localIdentity.ed25519PublicKey,
                peerIdentityEd25519 = initMessage.senderIdentityEd25519,
                isInitiator = false
            )
            Bytes.zeroize(sharedSecret)
            return session
        }

        private fun fromSharedSecret(
            sharedSecret: ByteArray,
            sessionId: ByteArray,
            peerUserId: Long,
            localIdentityEd25519: ByteArray,
            peerIdentityEd25519: ByteArray,
            isInitiator: Boolean
        ): E2eeSession {
            val root = Primitives.hkdf(
                sharedSecret, sessionId,
                Bytes.concat("ENCHAT-ROOT-v1".toByteArray(), sessionId), 32
            )
            val outboundSeed = Primitives.hkdf(
                root, null,
                Bytes.concat("ENCHAT-CHAIN-OUT-v1".toByteArray(), sessionId), 32
            )
            val inboundSeed = Primitives.hkdf(
                root, null,
                Bytes.concat("ENCHAT-CHAIN-IN-v1".toByteArray(), sessionId), 32
            )
            val sending = if (isInitiator) outboundSeed else inboundSeed
            val receiving = if (isInitiator) inboundSeed else outboundSeed
            val session = E2eeSession(
                sessionId = sessionId,
                peerUserId = peerUserId,
                localIdentityEd25519 = localIdentityEd25519,
                peerIdentityEd25519 = peerIdentityEd25519,
                isInitiator = isInitiator,
                rootKey = root,
                sendingChain = ChainKey.fromSeed(sending),
                receiver = SkippingChainReceiver(ChainKey.fromSeed(receiving), MAX_SKIPPED_KEYS)
            )
            Bytes.zeroize(outboundSeed)
            Bytes.zeroize(inboundSeed)
            return session
        }

        // ---------------- 本地持久化 ----------------

        /**
         * 序列化会话状态（含密钥材料），用于写入本地加密存储（如 Android Keystore 保护的 DB）。
         * 格式：
         * version(1) || sessionId(16) || peerUserId(8) || isInitiator(1) || localIdEd(32) || peerIdEd(32)
         *   || rootKey(32) || sendChainKey(32) || sendIndex(4) || recvChainKey(32) || recvIndex(4)
         *   || skipCount(2) || (index(4) || messageKey(32))*
         */
        fun serialize(session: E2eeSession): ByteArray {
            val parts = mutableListOf<ByteArray>()
            parts += byteArrayOf(1)
            parts += session.sessionId
            parts += Bytes.writeUInt64BE(session.peerUserId)
            parts += byteArrayOf(if (session.isInitiator) 1 else 0)
            parts += session.localIdentityEd25519
            parts += session.peerIdentityEd25519
            parts += session.rootKey
            parts += session.sendingChain.keyBytes()
            parts += Bytes.writeUInt32BE(session.sendingChain.currentIndex)
            parts += session.receiver.chainKeyBytes()
            parts += Bytes.writeUInt32BE(session.receiver.currentIndex)
            val skipped = session.receiver.skippedEntries()
            require(skipped.size <= 0xFFFF) { "too many skipped keys" }
            parts += byteArrayOf((skipped.size ushr 8).toByte(), skipped.size.toByte())
            skipped.forEach { (idx, key) ->
                parts += Bytes.writeUInt32BE(idx)
                parts += key
            }
            return Bytes.concat(*parts.toTypedArray())
        }

        fun deserialize(b: ByteArray): E2eeSession {
            var off = 0
            require(b.size >= 1 + 16 + 8 + 1 + 32 + 32 + 32 + 32 + 4 + 32 + 4 + 2) { "session blob too short" }
            val version = b[off]; off += 1
            require(version == 1.toByte()) { "unsupported session version: $version" }
            val sessionId = b.copyOfRange(off, off + 16); off += 16
            val peerUserId = Bytes.readUInt64BE(b, off); off += 8
            val isInitiator = b[off] == 1.toByte(); off += 1
            val localId = b.copyOfRange(off, off + 32); off += 32
            val peerId = b.copyOfRange(off, off + 32); off += 32
            val root = b.copyOfRange(off, off + 32); off += 32
            val sendKey = b.copyOfRange(off, off + 32); off += 32
            val sendIndex = Bytes.readUInt32BE(b, off); off += 4
            val recvKey = b.copyOfRange(off, off + 32); off += 32
            val recvIndex = Bytes.readUInt32BE(b, off); off += 4
            val skipCount = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF); off += 2
            require(off + skipCount * 36 == b.size) { "session blob length mismatch" }
            val receiver = SkippingChainReceiver(ChainKey(recvKey, recvIndex), MAX_SKIPPED_KEYS)
            repeat(skipCount) {
                val idx = Bytes.readUInt32BE(b, off); off += 4
                val key = b.copyOfRange(off, off + 32); off += 32
                receiver.restoreSkipped(idx, key)
            }
            return E2eeSession(
                sessionId = sessionId,
                peerUserId = peerUserId,
                localIdentityEd25519 = localId,
                peerIdentityEd25519 = peerId,
                isInitiator = isInitiator,
                rootKey = root,
                sendingChain = ChainKey(sendKey, sendIndex),
                receiver = receiver
            )
        }
    }

    private fun buildAad(
        sessionId: ByteArray,
        index: Int,
        senderIdentity: ByteArray,
        receiverIdentity: ByteArray
    ): ByteArray = Bytes.concat(
        MESSAGE_VERSION,
        sessionId,
        Bytes.writeUInt32BE(index),
        senderIdentity,
        receiverIdentity
    )

    /** 安全数字：与对方的会话指纹，用于带外核验（防服务器伪造公钥包）。 */
    fun safetyNumber(): String = SafetyNumber.compute(localIdentityEd25519, peerIdentityEd25519)
}
