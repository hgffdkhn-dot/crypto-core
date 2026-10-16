package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class GroupCryptoTest {

    private val groupId = Bytes.random(16)
    private val senderUserId = 123456L

    private fun distribute(epoch: Int): Pair<GroupCrypto.SenderKeyState, GroupCrypto.ReceiverKeyState> {
        val sender = GroupCrypto.SenderKeyState.create(groupId, epoch)
        val payload = sender.distributionPayload()
        val receiver = GroupCrypto.ReceiverKeyState.fromDistributionPayload(payload, senderUserId)
        return sender to receiver
    }

    @Test
    fun `group messages round trip via sender key`() {
        val (sender, receiver) = distribute(epoch = 1)
        repeat(200) { i ->
            val text = "群消息-$i-🎉".toByteArray()
            assertArrayEquals(text, receiver.decrypt(sender.encrypt(text, senderUserId)))
        }
    }

    @Test
    fun `out of order group messages are handled`() {
        val (sender, receiver) = distribute(epoch = 1)
        val frames = (0 until 5).map { sender.encrypt("g$it".toByteArray(), senderUserId) }
        assertArrayEquals("g2".toByteArray(), receiver.decrypt(frames[2]))
        assertArrayEquals("g0".toByteArray(), receiver.decrypt(frames[0]))
        assertArrayEquals("g4".toByteArray(), receiver.decrypt(frames[4]))
        assertArrayEquals("g1".toByteArray(), receiver.decrypt(frames[1]))
        assertArrayEquals("g3".toByteArray(), receiver.decrypt(frames[3]))
    }

    @Test
    fun `replayed group message is rejected`() {
        val (sender, receiver) = distribute(epoch = 1)
        val frame = sender.encrypt("only once".toByteArray(), senderUserId)
        receiver.decrypt(frame)
        assertThrows(CryptoException::class.java) { receiver.decrypt(frame) }
    }

    @Test
    fun `old epoch is rejected after rotation`() {
        val (sender, receiver) = distribute(epoch = 1)
        val oldFrame = sender.encrypt("epoch1".toByteArray(), senderUserId)
        assertArrayEquals("epoch1".toByteArray(), receiver.decrypt(oldFrame))

        // 成员变动 → 轮换：epoch 提升，新链重新分发
        val rotated = GroupCrypto.SenderKeyState.create(groupId, 2)
        val receiver2 = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            rotated.distributionPayload(), senderUserId
        )
        assertThrows(CryptoException::class.java) { receiver.decrypt(rotated.encrypt("epoch2".toByteArray(), senderUserId)) }
        assertThrows(CryptoException::class.java) { receiver2.decrypt(sender.encrypt("still epoch1".toByteArray(), senderUserId)) }
        assertArrayEquals("epoch2".toByteArray(), receiver2.decrypt(rotated.encrypt("epoch2".toByteArray(), senderUserId)))
    }

    @Test
    fun `wrong group id or sender is rejected`() {
        val (sender, receiver) = distribute(epoch = 1)
        val frame = sender.encrypt("x".toByteArray(), senderUserId)

        val otherGroupReceiver = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            GroupCrypto.SenderKeyState.create(Bytes.random(16), 1).distributionPayload(), senderUserId
        )
        assertThrows(CryptoException::class.java) { otherGroupReceiver.decrypt(frame) }

        val otherSenderReceiver = GroupCrypto.ReceiverKeyState.fromDistributionPayload(
            sender.distributionPayload(), 999L
        )
        assertThrows(CryptoException::class.java) { otherSenderReceiver.decrypt(frame) }
    }

    @Test
    fun `distribution payload round trips and has fixed size`() {
        val sender = GroupCrypto.SenderKeyState.create(groupId, 7)
        val payload = sender.distributionPayload()
        assertEquals(1 + 16 + 4 + 4 + 32, payload.size)
        val parsed = GroupCrypto.SenderKeyState.fromDistributionPayload(payload)
        assertEquals(7, parsed.epoch)
        assertArrayEquals(groupId, parsed.groupId)
        assertEquals(sender.iteration, parsed.iteration)
        assertThrows(IllegalArgumentException::class.java) {
            GroupCrypto.SenderKeyState.fromDistributionPayload(payload.copyOfRange(0, payload.size - 1))
        }
    }

    @Test
    fun `tampered group message is rejected`() {
        val (sender, receiver) = distribute(epoch = 1)
        val frame = sender.encrypt("tamper".toByteArray(), senderUserId)
        val bad = frame.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x7F).toByte()
        assertThrows(CryptoException::class.java) { receiver.decrypt(bad) }
    }

    @Test
    fun `distribution payload is opaque to server`() {
        // 分发载荷本身是明文链密钥，必须由双边 E2EE 会话加密后传输；这里验证它可被会话安全封装。
        val aId = IdentityKeyPair.generate()
        val bId = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(1, bId)
        val bundle = PreKeyBundle(
            userId = 555L,
            identityEd25519Public = bId.ed25519PublicKey,
            identityX25519Public = bId.x25519PublicKey,
            signedPreKeyId = spk.id,
            signedPreKeyPublic = spk.publicKey,
            signedPreKeySignature = spk.signature
        )
        val (alice, initMsg) = E2eeSession.createAsSender(aId, 444L, bundle)
        val bob = E2eeSession.createAsReceiver(bId, spk, null, initMsg)

        val sender = GroupCrypto.SenderKeyState.create(groupId, 1)
        val payload = sender.distributionPayload()
        val delivered = bob.decrypt(alice.encrypt(payload))
        val receiver = GroupCrypto.ReceiverKeyState.fromDistributionPayload(delivered, 444L)
        assertArrayEquals("群消息".toByteArray(), receiver.decrypt(sender.encrypt("群消息".toByteArray(), 444L)))
    }
}
