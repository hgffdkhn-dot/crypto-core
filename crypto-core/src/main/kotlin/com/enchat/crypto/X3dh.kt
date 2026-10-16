package com.enchat.crypto

/**
 * X3DH-lite：会话初始密钥协商（Signal X3DH 的简化变体）。
 *
 * 与标准 X3DH 的差别（取舍原因见 docs/01-架构设计.md）：
 * - 保留：长期身份 DH、临时密钥 DH、签名预密钥、一次性预密钥、SPK 签名校验、发起方对临时密钥的显式签名。
 * - 去掉：DH 棘轮（Double Ratchet 的 DH 部分）。因此具备**前向保密**（旧消息密钥用过即弃），
 *   但**不具备后向保密**（会话根密钥若被完整窃取，之后通信需重新建会话才能恢复安全）。
 *
 * 取舍原因：保证异步/乱序/弱网下的聊天体验，避免 DH 棘轮带来的"消息必须按轮次到达"约束。
 */
object X3dh {

    private val KDF_DOMAIN: ByteArray = "ENCHAT-X3DH-v1".toByteArray()
    private val SIGN_DOMAIN: ByteArray = "ENCHAT-X3DH-INIT-v1".toByteArray()

    /** 会话初始化消息：发起方 → 接收方（经服务器中转，服务器只能看到公开字段）。 */
    class InitMessage(
        val senderUserId: Long,
        val receiverUserId: Long,
        val senderIdentityEd25519: ByteArray,
        val senderIdentityX25519: ByteArray,
        val senderEphemeralPublic: ByteArray,
        val receiverSignedPreKeyId: Int,
        val receiverOneTimePreKeyId: Int?,
        val signature: ByteArray
    ) {
        init {
            require(senderIdentityEd25519.size == 32) { "sender ed25519 public must be 32 bytes" }
            require(senderIdentityX25519.size == 32) { "sender x25519 public must be 32 bytes" }
            require(senderEphemeralPublic.size == 32) { "ephemeral public must be 32 bytes" }
            require(signature.size == 64) { "init signature must be 64 bytes" }
            receiverOneTimePreKeyId?.let { require(it >= 0) { "OPK id must be non-negative" } }
        }

        /** 签名覆盖的数据：绑定双方身份 + 临时公钥 + 使用的预密钥，防止服务器替换或跨会话重放。 */
        fun signedData(): ByteArray = buildSignedData(
            senderUserId, receiverUserId,
            senderIdentityEd25519, senderIdentityX25519, senderEphemeralPublic,
            receiverSignedPreKeyId, receiverOneTimePreKeyId
        )

        fun verifySignature(): Boolean =
            Primitives.ed25519Verify(senderIdentityEd25519, signedData(), signature)

        /** 线格式：version(1) || senderId(8) || receiverId(8) || idEd(32) || idX(32) || eph(32) || spkId(4) || opkId(4, -1=无) || sig(64) */
        fun toBytes(): ByteArray = Bytes.concat(
            byteArrayOf(1),
            Bytes.writeUInt64BE(senderUserId),
            Bytes.writeUInt64BE(receiverUserId),
            senderIdentityEd25519,
            senderIdentityX25519,
            senderEphemeralPublic,
            Bytes.writeUInt32BE(receiverSignedPreKeyId),
            Bytes.writeUInt32BE(receiverOneTimePreKeyId ?: -1),
            signature
        )

        companion object {
            const val SERIALIZED_SIZE = 1 + 8 + 8 + 32 + 32 + 32 + 4 + 4 + 64

            fun fromBytes(b: ByteArray): InitMessage {
                require(b.size == SERIALIZED_SIZE) { "init message length mismatch: ${b.size}" }
                require(b[0] == 1.toByte()) { "unsupported init message version: ${b[0]}" }
                var off = 1
                val senderUserId = Bytes.readUInt64BE(b, off); off += 8
                val receiverUserId = Bytes.readUInt64BE(b, off); off += 8
                val sed = b.copyOfRange(off, off + 32); off += 32
                val sxh = b.copyOfRange(off, off + 32); off += 32
                val eph = b.copyOfRange(off, off + 32); off += 32
                val spkId = Bytes.readUInt32BE(b, off); off += 4
                val opkId = Bytes.readUInt32BE(b, off); off += 4
                val sig = b.copyOfRange(off, off + 64)
                return InitMessage(
                    senderUserId, receiverUserId, sed, sxh, eph, spkId,
                    if (opkId == -1) null else opkId, sig
                )
            }
        }
    }

    internal fun buildSignedData(
        senderUserId: Long,
        receiverUserId: Long,
        senderIdentityEd25519: ByteArray,
        senderIdentityX25519: ByteArray,
        senderEphemeralPublic: ByteArray,
        signedPreKeyId: Int,
        oneTimePreKeyId: Int?
    ): ByteArray = Bytes.concat(
        SIGN_DOMAIN,
        Bytes.writeUInt64BE(senderUserId),
        Bytes.writeUInt64BE(receiverUserId),
        Bytes.writeLp(senderIdentityEd25519),
        Bytes.writeLp(senderIdentityX25519),
        Bytes.writeLp(senderEphemeralPublic),
        Bytes.writeUInt32BE(signedPreKeyId),
        Bytes.writeUInt32BE(oneTimePreKeyId ?: -1)
    )

    /** 发起方产物：共享密钥 + 待发送的 InitMessage。 */
    data class SenderResult(val sharedSecret: ByteArray, val initMessage: InitMessage)

    /**
     * 发起方（Alice）建立会话。
     *
     * @param sender Alice 的长期身份密钥
     * @param senderUserId Alice 的 UID（参与签名绑定）
     * @param bundle 服务器返回的 Bob 公钥包（本函数会校验其 SPK 自洽性）
     * @return 32 字节共享密钥；调用方应立刻派生会话密钥并擦除返回值
     */
    fun initiateAsSender(sender: IdentityKeyPair, senderUserId: Long, bundle: PreKeyBundle): SenderResult {
        require(bundle.verifySelfConsistent()) { "pre-key bundle SPK signature is invalid" }

        val ephemeral = Primitives.x25519GenerateKeyPair()
        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        var dh3: ByteArray? = null
        var dh4: ByteArray? = null
        try {
            dh1 = Primitives.x25519SharedSecret(sender.x25519PrivateKey, bundle.signedPreKeyPublic)
            dh2 = Primitives.x25519SharedSecret(ephemeral.privateKey, bundle.identityX25519Public)
            dh3 = Primitives.x25519SharedSecret(ephemeral.privateKey, bundle.signedPreKeyPublic)
            dh4 = bundle.oneTimePreKeyPublic?.let {
                Primitives.x25519SharedSecret(ephemeral.privateKey, it)
            }

            val transcript = buildTranscript(
                senderEd25519Public = sender.ed25519PublicKey,
                senderX25519Public = sender.x25519PublicKey,
                senderEphemeralPublic = ephemeral.publicKey,
                receiverEd25519Public = bundle.identityEd25519Public,
                receiverX25519Public = bundle.identityX25519Public,
                signedPreKeyPublic = bundle.signedPreKeyPublic,
                oneTimePreKeyPublic = bundle.oneTimePreKeyPublic
            )

            val signedData = buildSignedData(
                senderUserId, bundle.userId,
                sender.ed25519PublicKey, sender.x25519PublicKey, ephemeral.publicKey,
                bundle.signedPreKeyId, bundle.oneTimePreKeyId
            )
            val initMessage = InitMessage(
                senderUserId = senderUserId,
                receiverUserId = bundle.userId,
                senderIdentityEd25519 = sender.ed25519PublicKey,
                senderIdentityX25519 = sender.x25519PublicKey,
                senderEphemeralPublic = ephemeral.publicKey,
                receiverSignedPreKeyId = bundle.signedPreKeyId,
                receiverOneTimePreKeyId = bundle.oneTimePreKeyId,
                signature = sender.sign(signedData)
            )
            val sharedSecret = deriveSharedSecret(dh1, dh2, dh3, dh4, transcript)
            return SenderResult(sharedSecret, initMessage)
        } finally {
            Bytes.zeroize(ephemeral.privateKey)
            dh1?.let { Bytes.zeroize(it) }
            dh2?.let { Bytes.zeroize(it) }
            dh3?.let { Bytes.zeroize(it) }
            dh4?.let { Bytes.zeroize(it) }
        }
    }

    /**
     * 接收方（Bob）处理 InitMessage。
     *
     * @param spk 消息中 spkId 对应的本地签名预密钥
     * @param opk 消息中 opkId 对应的本地一次性预密钥（消息未使用 OPK 时传 null）
     * @return 32 字节共享密钥，与发起方一致
     * @throws IllegalArgumentException 签名校验失败或预密钥 id 不匹配
     */
    fun acceptAsReceiver(
        receiver: IdentityKeyPair,
        spk: SignedPreKey,
        opk: OneTimePreKey?,
        message: InitMessage
    ): ByteArray {
        require(message.verifySignature()) { "init message signature is invalid" }
        require(spk.id == message.receiverSignedPreKeyId) {
            "SPK id mismatch: local=${spk.id}, message=${message.receiverSignedPreKeyId}"
        }
        if (message.receiverOneTimePreKeyId != null) {
            requireNotNull(opk) { "message requires OPK ${message.receiverOneTimePreKeyId} but none provided" }
            require(opk.id == message.receiverOneTimePreKeyId) { "OPK id mismatch: local=${opk.id}" }
        } else {
            require(opk == null) { "message has no OPK but one was provided" }
        }

        var dh1: ByteArray? = null
        var dh2: ByteArray? = null
        var dh3: ByteArray? = null
        var dh4: ByteArray? = null
        try {
            dh1 = Primitives.x25519SharedSecret(spk.privateKeyBytes(), message.senderIdentityX25519)
            dh2 = Primitives.x25519SharedSecret(receiver.x25519PrivateKey, message.senderEphemeralPublic)
            dh3 = Primitives.x25519SharedSecret(spk.privateKeyBytes(), message.senderEphemeralPublic)
            dh4 = opk?.let { Primitives.x25519SharedSecret(it.privateKeyBytes(), message.senderEphemeralPublic) }

            val transcript = buildTranscript(
                senderEd25519Public = message.senderIdentityEd25519,
                senderX25519Public = message.senderIdentityX25519,
                senderEphemeralPublic = message.senderEphemeralPublic,
                receiverEd25519Public = receiver.ed25519PublicKey,
                receiverX25519Public = receiver.x25519PublicKey,
                signedPreKeyPublic = spk.publicKey,
                oneTimePreKeyPublic = opk?.publicKey
            )
            return deriveSharedSecret(dh1, dh2, dh3, dh4, transcript)
        } finally {
            dh1?.let { Bytes.zeroize(it) }
            dh2?.let { Bytes.zeroize(it) }
            dh3?.let { Bytes.zeroize(it) }
            dh4?.let { Bytes.zeroize(it) }
        }
    }

    private fun buildTranscript(
        senderEd25519Public: ByteArray,
        senderX25519Public: ByteArray,
        senderEphemeralPublic: ByteArray,
        receiverEd25519Public: ByteArray,
        receiverX25519Public: ByteArray,
        signedPreKeyPublic: ByteArray,
        oneTimePreKeyPublic: ByteArray?
    ): ByteArray = Bytes.concat(
        KDF_DOMAIN,
        Bytes.writeLp(senderEd25519Public),
        Bytes.writeLp(senderX25519Public),
        Bytes.writeLp(senderEphemeralPublic),
        Bytes.writeLp(receiverEd25519Public),
        Bytes.writeLp(receiverX25519Public),
        Bytes.writeLp(signedPreKeyPublic),
        Bytes.writeLp(oneTimePreKeyPublic)
    )

    private fun deriveSharedSecret(
        dh1: ByteArray,
        dh2: ByteArray,
        dh3: ByteArray,
        dh4: ByteArray?,
        transcript: ByteArray
    ): ByteArray {
        // Signal 传统：IKM 前缀填充 32 字节 0xFF，使不同 DH 组合的 IKM 长度规整
        val fill = ByteArray(32) { 0xFF.toByte() }
        val ikm = if (dh4 != null) {
            Bytes.concat(fill, dh1, dh2, dh3, dh4)
        } else {
            Bytes.concat(fill, dh1, dh2, dh3)
        }
        val salt = Primitives.sha256(transcript)
        return Primitives.hkdf(ikm, salt, transcript, 32)
    }
}

/**
 * 安全数字（Safety Number）：两名用户可带外比对的短指纹，用于识别服务器伪造公钥包。
 * 与顺序无关（双人会话），输出 12 组 5 位数字。
 */
object SafetyNumber {
    fun compute(aliceEd25519Public: ByteArray, bobEd25519Public: ByteArray): String {
        val (a, b) = if (lexCompare(aliceEd25519Public, bobEd25519Public) <= 0) {
            aliceEd25519Public to bobEd25519Public
        } else {
            bobEd25519Public to aliceEd25519Public
        }
        val seed = Primitives.sha256(
            Bytes.concat("ENCHAT-SAFETY-v1".toByteArray(), Bytes.writeLp(a), Bytes.writeLp(b))
        )
        val material = Primitives.hkdf(seed, null, "ENCHAT-SAFETY-GROUPS".toByteArray(), 60)
        val sb = StringBuilder()
        for (i in 0 until 12) {
            var v = 0L
            for (j in 0 until 5) v = (v shl 8) or (material[i * 5 + j].toLong() and 0xFF)
            if (i > 0) sb.append(' ')
            sb.append((v % 100000).toString().padStart(5, '0'))
        }
        return sb.toString()
    }

    private fun lexCompare(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}
