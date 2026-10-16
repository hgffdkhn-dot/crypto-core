package com.enchat.crypto

/**
 * Hash 链棘轮（对称棘轮，Symmetric-key Ratchet）。
 *
 * 每条消息推进一次：
 *   messageKey   = HMAC-SHA256(chainKey, 0x01)
 *   nextChainKey = HMAC-SHA256(chainKey, 0x02)
 *
 * 性质：
 * - 每个消息密钥只使用一次；使用后立即擦除 → 旧消息即使事后拿到链密钥也无法解密（前向保密）。
 * - 链只能单向前进，接收方可以"预推进"来追赶乱序到达的消息（缓存跳过的消息密钥）。
 */
class ChainKey(private var key: ByteArray, internal var index: Int = 0) {

    init {
        require(key.size == 32) { "chain key must be 32 bytes" }
        require(index >= 0) { "chain index must be non-negative" }
    }

    val currentIndex: Int get() = index

    fun keyBytes(): ByteArray = key.copyOf()

    /** 取出当前消息密钥并把链向前推进一步。 */
    fun nextMessageKey(): MessageKey {
        val mk = Primitives.hmacSha256(key, byteArrayOf(0x01))
        val next = Primitives.hmacSha256(key, byteArrayOf(0x02))
        Bytes.zeroize(key)
        key = next
        index += 1
        return MessageKey(mk, index - 1)
    }

    fun zeroize() {
        Bytes.zeroize(key)
    }

    companion object {
        fun fromSeed(seed: ByteArray, startIndex: Int = 0): ChainKey =
            ChainKey(seed.copyOf(), startIndex)
    }
}

/**
 * 一次性消息密钥。
 *
 * 加密材料由 HKDF 从消息密钥派生：AES-256-GCM 密钥 + 12 字节 nonce。
 * nonce 由密钥派生而非随机，避免"同一消息密钥被复用导致 nonce 重复"这一 GCM 灾难性错误。
 */
class MessageKey(private val key: ByteArray, val chainIndex: Int) {

    init {
        require(key.size == 32) { "message key must be 32 bytes" }
    }

    private fun encKey(): ByteArray = Primitives.hkdf(key, null, "ENCHAT-MK-AES-v1".toByteArray(), 32)

    private fun nonce(): ByteArray = Primitives.hkdf(key, null, "ENCHAT-MK-NONCE-v1".toByteArray(), 12)

    fun keyBytes(): ByteArray = key.copyOf()

    fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray {
        val k = encKey()
        val n = nonce()
        try {
            return Primitives.aes256GcmEncrypt(k, n, plaintext, aad)
        } finally {
            Bytes.zeroize(k)
        }
    }

    fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray {
        val k = encKey()
        val n = nonce()
        try {
            return Primitives.aes256GcmDecrypt(k, n, ciphertext, aad)
        } finally {
            Bytes.zeroize(k)
        }
    }

    fun zeroize() {
        Bytes.zeroize(key)
    }
}

/**
 * 接收侧链 + 乱序/重放处理（1:1 会话与群聊 sender key 共用）。
 *
 * - index == 当前：正常前进。
 * - index > 当前：预推进链，缓存被跳过的消息密钥（上限 [maxSkipped]，超出先丢弃最旧的一条）。
 * - index < 当前：只能命中缓存；命中即删除（每条消息密钥只允许成功解密一次 → 防重放）。
 */
internal class SkippingChainReceiver(private var chain: ChainKey, private val maxSkipped: Int) {

    private val skipped = HashMap<Int, ByteArray>()

    val currentIndex: Int get() = chain.currentIndex
    val skippedKeyCount: Int get() = skipped.size

    fun chainKeyBytes(): ByteArray = chain.keyBytes()

    fun skippedEntries(): Map<Int, ByteArray> = skipped.toMap()

    fun restoreSkipped(index: Int, key: ByteArray) {
        skipped[index] = key
    }

    fun keyFor(index: Int): MessageKey {
        if (index < 0) throw CryptoException("negative chain index")
        return when {
            index == chain.currentIndex -> chain.nextMessageKey()
            index > chain.currentIndex -> catchUpTo(index)
            else -> {
                val cached = skipped.remove(index)
                    ?: throw CryptoException("replayed or already-consumed message at index $index")
                MessageKey(cached, index)
            }
        }
    }

    private fun catchUpTo(index: Int): MessageKey {
        val distance = index - chain.currentIndex
        require(distance <= maxSkipped) {
            "message index $index is too far ahead (expected <= ${chain.currentIndex + maxSkipped})"
        }
        while (chain.currentIndex < index) {
            if (skipped.size >= maxSkipped) {
                val oldest = skipped.keys.minOrNull()
                if (oldest != null) {
                    skipped.remove(oldest)?.let { Bytes.zeroize(it) }
                }
            }
            val mk = chain.nextMessageKey()
            skipped[mk.chainIndex] = mk.keyBytes()
            mk.zeroize()
        }
        return chain.nextMessageKey()
    }
}
