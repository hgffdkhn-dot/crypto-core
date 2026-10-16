package com.enchat.crypto

/**
 * 群聊端到端加密：Sender-Key 方案。
 *
 * 为什么不直接对每条群消息做 N 次双边加密？
 * 500 人群一条消息 = 499 次加密 + 499 份密文，客户端与服务器都扛不住。
 * 标准做法（Signal Sender Key / MLS 思路）：
 * - 每个发送者在每个群持有一条自己的 sender chain（Hash 链），群消息只用该链加密一次，广播给所有人。
 * - sender chain 的**当前链密钥**通过与每个成员的双边 [E2eeSession] 加密后分发（成员加入时 / 密钥轮换时）。
 * - 成员离开或被移除时，必须提升 epoch 并生成全新 chain，重新分发 —— 否则离开者仍能读后续消息。
 *
 * 线格式（群消息）：version(1) || groupId(16) || epoch(4) || iteration(4) || senderUserId(8) || ciphertextWithTag
 * AAD：同上头部全部字段。
 */
object GroupCrypto {

    const val GROUP_ID_SIZE = 16
    const val MAX_SKIPPED_KEYS = 1000
    private val VERSION: ByteArray = byteArrayOf(1)
    private const val HEADER_SIZE = 1 + 16 + 4 + 4 + 8

    /** 发送侧状态：本人在本群的 sender chain。 */
    class SenderKeyState(
        val groupId: ByteArray,
        val epoch: Int,
        private var chain: ChainKey
    ) {
        init {
            require(groupId.size == GROUP_ID_SIZE) { "groupId must be 16 bytes" }
            require(epoch >= 0) { "epoch must be non-negative" }
        }

        val iteration: Int get() = chain.currentIndex

        /** 当前链密钥：仅用于持久化与分发，勿外泄。 */
        internal fun chainKeyBytes(): ByteArray = chain.keyBytes()

        fun encrypt(plaintext: ByteArray, senderUserId: Long): ByteArray {
            val mk = chain.nextMessageKey()
            try {
                val iteration = mk.chainIndex
                val aad = aad(groupId, epoch, iteration, senderUserId)
                val ct = mk.encrypt(plaintext, aad)
                return Bytes.concat(
                    VERSION, groupId, Bytes.writeUInt32BE(epoch), Bytes.writeUInt32BE(iteration),
                    Bytes.writeUInt64BE(senderUserId), ct
                )
            } finally {
                mk.zeroize()
            }
        }

        /** 生成分发载荷：用与成员的双边会话加密后发给对方。 */
        fun distributionPayload(): ByteArray = Bytes.concat(
            VERSION,
            groupId,
            Bytes.writeUInt32BE(epoch),
            Bytes.writeUInt32BE(chain.currentIndex),
            chain.keyBytes()
        )

        companion object {
            /** 新建（或轮换）sender chain。轮换时 epoch 必须递增。 */
            fun create(groupId: ByteArray, epoch: Int): SenderKeyState =
                SenderKeyState(groupId, epoch, ChainKey.fromSeed(Bytes.random(32)))

            fun fromDistributionPayload(b: ByteArray): SenderKeyState {
                require(b.size == 1 + 16 + 4 + 4 + 32) { "distribution payload length mismatch: ${b.size}" }
                require(b[0] == VERSION[0]) { "unsupported distribution version: ${b[0]}" }
                val groupId = b.copyOfRange(1, 17)
                val epoch = Bytes.readUInt32BE(b, 17)
                val iteration = Bytes.readUInt32BE(b, 21)
                val key = b.copyOfRange(25, 57)
                return SenderKeyState(groupId, epoch, ChainKey(key, iteration))
            }
        }
    }

    /** 接收侧状态：针对某个发送者在某个群的链。 */
    class ReceiverKeyState internal constructor(
        val groupId: ByteArray,
        val senderUserId: Long,
        val epoch: Int,
        private val receiver: SkippingChainReceiver
    ) {
        val nextIteration: Int get() = receiver.currentIndex

        fun decrypt(wire: ByteArray): ByteArray {
            if (wire.size < HEADER_SIZE + Primitives.GCM_TAG_SIZE_BYTES) {
                throw CryptoException("group message too short: ${wire.size}")
            }
            if (wire[0] != VERSION[0]) throw CryptoException("unsupported group message version: ${wire[0]}")
            val gid = wire.copyOfRange(1, 17)
            if (!Bytes.constantTimeEquals(gid, groupId)) throw CryptoException("group id mismatch")
            val epoch = Bytes.readUInt32BE(wire, 17)
            if (epoch != this.epoch) {
                throw CryptoException("group epoch mismatch: got $epoch, expected ${this.epoch}")
            }
            val iteration = Bytes.readUInt32BE(wire, 21)
            if (iteration < 0) throw CryptoException("negative iteration")
            val sender = Bytes.readUInt64BE(wire, 25)
            if (sender != senderUserId) throw CryptoException("sender mismatch")
            val ct = wire.copyOfRange(HEADER_SIZE, wire.size)

            val mk = receiver.keyFor(iteration)
            try {
                return mk.decrypt(ct, aad(groupId, epoch, iteration, senderUserId))
            } finally {
                mk.zeroize()
            }
        }

        companion object {
            fun fromDistributionPayload(b: ByteArray, senderUserId: Long): ReceiverKeyState {
                val state = SenderKeyState.fromDistributionPayload(b)
                return ReceiverKeyState(
                    state.groupId, senderUserId, state.epoch,
                    SkippingChainReceiver(
                        ChainKey(state.chainKeyBytes(), state.iteration),
                        MAX_SKIPPED_KEYS
                    )
                )
            }
        }
    }

    private fun aad(groupId: ByteArray, epoch: Int, iteration: Int, senderUserId: Long): ByteArray =
        Bytes.concat(
            VERSION, groupId, Bytes.writeUInt32BE(epoch), Bytes.writeUInt32BE(iteration),
            Bytes.writeUInt64BE(senderUserId)
        )
}
