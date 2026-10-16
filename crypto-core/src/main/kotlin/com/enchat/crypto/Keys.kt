package com.enchat.crypto

/**
 * 用户长期身份密钥。
 *
 * 两对密钥，职责分离：
 * - [ed25519]：签名与身份指纹（Safety Number 由此而来），不参与 DH。
 *   不做 Ed25519→X25519 转换，避免转换带来的实现与安全性争议。
 * - [x25519]：长期 DH 密钥，参与 X3DH 的 DH1/DH2。
 *
 * 所有 getter 返回副本，调用方修改返回数组不会影响内部状态。
 */
class IdentityKeyPair(
    private val ed25519Priv: ByteArray,
    private val ed25519Pub: ByteArray,
    private val x25519Priv: ByteArray,
    private val x25519Pub: ByteArray
) {
    init {
        require(ed25519Priv.size == 32 && ed25519Pub.size == 32) { "Ed25519 keys must be 32 bytes" }
        require(x25519Priv.size == 32 && x25519Pub.size == 32) { "X25519 keys must be 32 bytes" }
    }

    val ed25519PrivateKey: ByteArray get() = ed25519Priv.copyOf()
    val ed25519PublicKey: ByteArray get() = ed25519Pub.copyOf()
    val x25519PrivateKey: ByteArray get() = x25519Priv.copyOf()
    val x25519PublicKey: ByteArray get() = x25519Pub.copyOf()

    /** 用长期身份签名任意数据（如预密钥、会话初始化消息）。 */
    fun sign(data: ByteArray): ByteArray = Primitives.ed25519Sign(ed25519Priv, data)

    /** 本地持久化格式：ed25519Priv || ed25519Pub || x25519Priv || x25519Pub = 128B。 */
    fun toBytes(): ByteArray = Bytes.concat(ed25519Priv, ed25519Pub, x25519Priv, x25519Pub)

    companion object {
        fun generate(): IdentityKeyPair {
            val ed = Primitives.ed25519GenerateKeyPair()
            val x = Primitives.x25519GenerateKeyPair()
            return IdentityKeyPair(ed.privateKey, ed.publicKey, x.privateKey, x.publicKey)
        }

        fun fromPrivateKeys(ed25519Private: ByteArray, x25519Private: ByteArray): IdentityKeyPair =
            IdentityKeyPair(
                ed25519Private.copyOf(),
                org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(ed25519Private, 0)
                    .generatePublicKey().encoded,
                x25519Private.copyOf(),
                Primitives.x25519PublicFromPrivate(x25519Private)
            )

        fun fromBytes(b: ByteArray): IdentityKeyPair {
            require(b.size == 128) { "identity bytes must be 128 bytes, got ${b.size}" }
            return IdentityKeyPair(
                b.copyOfRange(0, 32),
                b.copyOfRange(32, 64),
                b.copyOfRange(64, 96),
                b.copyOfRange(96, 128)
            )
        }
    }
}

/**
 * 签名预密钥（Signed PreKey）：由服务器分发，发起方用它建立会话。
 * 默认 7 天轮换一次；轮换后旧会话仍可用（旧链继续走），新会话使用新 SPK。
 */
class SignedPreKey(val id: Int, private val privateKey: ByteArray, val publicKey: ByteArray, val signature: ByteArray) {

    init {
        require(privateKey.size == 32) { "SPK private key must be 32 bytes" }
        require(publicKey.size == 32) { "SPK public key must be 32 bytes" }
        require(signature.size == 64) { "SPK signature must be 64 bytes" }
    }

    fun privateKeyBytes(): ByteArray = privateKey.copyOf()

    /** 校验该预密钥确实由 [identityEd25519Public] 对应的长期身份签名。 */
    fun verify(identityEd25519Public: ByteArray): Boolean =
        Primitives.ed25519Verify(identityEd25519Public, signingData(id, publicKey), signature)

    companion object {
        private val DOMAIN: ByteArray = "ENCHAT-SPK-v1".toByteArray()

        fun signingData(id: Int, publicKey: ByteArray): ByteArray =
            Bytes.concat(DOMAIN, Bytes.writeUInt32BE(id), publicKey)

        fun generate(id: Int, identity: IdentityKeyPair): SignedPreKey {
            val kp = Primitives.x25519GenerateKeyPair()
            val sig = identity.sign(signingData(id, kp.publicKey))
            return SignedPreKey(id, kp.privateKey, kp.publicKey, sig)
        }

        /**
         * 本地持久化格式：id(4) || private(32) || public(32) || signature(64) = 132B。
         *
         * 为什么要能"从私钥恢复"：客户端重启后，服务器仍可能把此前上传的 SPK 分给别人；
         * 若本地丢了私钥，收到 E2EE_INIT 时无法完成接收侧协商，会话直接建不起来。
         * 因此密钥必须可持久化（并由 Keystore 之类机制加密后落盘）。
         */
        fun toStorageBytes(key: SignedPreKey): ByteArray = Bytes.concat(
            Bytes.writeUInt32BE(key.id),
            key.privateKeyBytes(),
            key.publicKey,
            key.signature
        )

        fun fromStorageBytes(b: ByteArray): SignedPreKey {
            require(b.size == 132) { "SPK storage bytes must be 132 bytes, got \${b.size}" }
            return SignedPreKey(
                id = Bytes.readUInt32BE(b, 0),
                privateKey = b.copyOfRange(4, 36),
                publicKey = b.copyOfRange(36, 68),
                signature = b.copyOfRange(68, 132)
            )
        }
    }
}

/** 一次性预密钥（One-Time PreKey）：用后即焚，提供会话隔离与更强的前向保密。 */
class OneTimePreKey(val id: Int, private val privateKey: ByteArray, val publicKey: ByteArray) {
    init {
        require(privateKey.size == 32) { "OPK private key must be 32 bytes" }
        require(publicKey.size == 32) { "OPK public key must be 32 bytes" }
    }

    fun privateKeyBytes(): ByteArray = privateKey.copyOf()

    companion object {
        fun generate(id: Int): OneTimePreKey {
            val kp = Primitives.x25519GenerateKeyPair()
            return OneTimePreKey(id, kp.privateKey, kp.publicKey)
        }

        /** 从私钥恢复（持久化用途）。公钥由私钥确定性派生，无需另存。 */
        fun fromPrivateKey(id: Int, privateKey: ByteArray): OneTimePreKey =
            OneTimePreKey(id, privateKey.copyOf(), Primitives.x25519PublicFromPrivate(privateKey))
    }
}

/**
 * 公钥包（PreKey Bundle）：服务器转交给发起方的数据，全部为公开信息。
 *
 * 安全说明：服务器理论上可以整套替换为自己的密钥（"密钥分发中间人"）。
 * 本协议通过 [SafetyNumber] 让两名用户带外比对指纹来发现此类攻击；
 * 这是所有中心化分发密钥的 IM 都存在的前提风险，无法仅靠密码学消除。
 */
data class PreKeyBundle(
    val userId: Long,
    val identityEd25519Public: ByteArray,
    val identityX25519Public: ByteArray,
    val signedPreKeyId: Int,
    val signedPreKeyPublic: ByteArray,
    val signedPreKeySignature: ByteArray,
    val oneTimePreKeyId: Int? = null,
    val oneTimePreKeyPublic: ByteArray? = null
) {
    init {
        require(identityEd25519Public.size == 32) { "identity ed25519 public must be 32 bytes" }
        require(identityX25519Public.size == 32) { "identity x25519 public must be 32 bytes" }
        require(signedPreKeyPublic.size == 32) { "SPK public must be 32 bytes" }
        require(signedPreKeySignature.size == 64) { "SPK signature must be 64 bytes" }
        if (oneTimePreKeyId != null) {
            require(oneTimePreKeyPublic != null && oneTimePreKeyPublic.size == 32) { "OPK public must be 32 bytes" }
        } else {
            require(oneTimePreKeyPublic == null) { "OPK public must be null when OPK id is null" }
        }
    }

    /** 校验包内 SPK 自洽性（只能证明"包内公钥与包内签名匹配"，不能证明包来自真实用户）。 */
    fun verifySelfConsistent(): Boolean =
        Primitives.ed25519Verify(
            identityEd25519Public,
            SignedPreKey.signingData(signedPreKeyId, signedPreKeyPublic),
            signedPreKeySignature
        )
}
