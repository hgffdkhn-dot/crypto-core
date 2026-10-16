package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class E2eeSessionTest {

    private data class Pair(
        val alice: E2eeSession,
        val bob: E2eeSession
    )

    private fun newPair(): Pair {
        val aId = IdentityKeyPair.generate()
        val bId = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(1, bId)
        val opk = OneTimePreKey.generate(1)
        val bundle = PreKeyBundle(
            userId = 9001L,
            identityEd25519Public = bId.ed25519PublicKey,
            identityX25519Public = bId.x25519PublicKey,
            signedPreKeyId = spk.id,
            signedPreKeyPublic = spk.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = opk.id,
            oneTimePreKeyPublic = opk.publicKey
        )
        val (alice, initMsg) = E2eeSession.createAsSender(aId, 8001L, bundle)
        val bob = E2eeSession.createAsReceiver(bId, spk, opk, initMsg)
        return Pair(alice, bob)
    }

    @Test
    fun `one thousand sequential messages round trip`() {
        val (alice, bob) = newPair()
        repeat(1000) { i ->
            val text = "msg-$i".toByteArray()
            assertArrayEquals(text, bob.decrypt(alice.encrypt(text)))
        }
        assertEquals(1000, alice.sendIndex)
        assertEquals(1000, bob.receiveIndex)
    }

    @Test
    fun `bidirectional messaging works`() {
        val (alice, bob) = newPair()
        assertArrayEquals("ping".toByteArray(), bob.decrypt(alice.encrypt("ping".toByteArray())))
        assertArrayEquals("pong".toByteArray(), alice.decrypt(bob.encrypt("pong".toByteArray())))
        assertArrayEquals("ping2".toByteArray(), bob.decrypt(alice.encrypt("ping2".toByteArray())))
    }

    @Test
    fun `out of order delivery is handled`() {
        val (alice, bob) = newPair()
        val frames = (0 until 6).map { alice.encrypt("m$it".toByteArray()) }
        // 按 0,3,1,5,2,4 的顺序投递
        assertArrayEquals("m0".toByteArray(), bob.decrypt(frames[0]))
        assertArrayEquals("m3".toByteArray(), bob.decrypt(frames[3]))
        assertEquals(4, bob.receiveIndex)
        assertEquals(2, bob.skippedKeyCount) // index 1,2 被缓存
        assertArrayEquals("m1".toByteArray(), bob.decrypt(frames[1]))
        assertArrayEquals("m5".toByteArray(), bob.decrypt(frames[5]))
        assertArrayEquals("m2".toByteArray(), bob.decrypt(frames[2]))
        assertArrayEquals("m4".toByteArray(), bob.decrypt(frames[4]))
        assertEquals(0, bob.skippedKeyCount)
        assertEquals(6, bob.receiveIndex)
    }

    @Test
    fun `replayed message is rejected`() {
        val (alice, bob) = newPair()
        val frame = alice.encrypt("once".toByteArray())
        assertArrayEquals("once".toByteArray(), bob.decrypt(frame))
        assertThrows(CryptoException::class.java) { bob.decrypt(frame) }
    }

    @Test
    fun `skipped message can be consumed exactly once`() {
        val (alice, bob) = newPair()
        val f0 = alice.encrypt("a".toByteArray())
        val f1 = alice.encrypt("b".toByteArray())
        bob.decrypt(f1) // 跳过 f0
        assertArrayEquals("a".toByteArray(), bob.decrypt(f0))
        assertThrows(CryptoException::class.java) { bob.decrypt(f0) }
    }

    @Test
    fun `message far beyond window is rejected`() {
        val (alice, bob) = newPair()
        repeat(E2eeSession.MAX_SKIPPED_KEYS + 5) { alice.encrypt("x".toByteArray()) }
        val tooFar = alice.encrypt("far".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { bob.decrypt(tooFar) }
    }

    @Test
    fun `tampered ciphertext and wrong session are rejected`() {
        val (alice, bob) = newPair()
        val frame = alice.encrypt("hello".toByteArray())
        val bad = frame.copyOf()
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
        assertThrows(CryptoException::class.java) { bob.decrypt(bad) }

        // 另一个会话的密文（sessionId 不同）
        val (alice2, _) = newPair()
        assertThrows(CryptoException::class.java) { bob.decrypt(alice2.encrypt("other".toByteArray())) }
    }

    @Test
    fun `forward secrecy - present state cannot decrypt consumed history`() {
        val (alice, bob) = newPair()
        // 历史消息全部正常解密并消费（消息密钥随即被擦除）
        val history = (0 until 50).map { alice.encrypt("old-$it".toByteArray()) }
        history.forEachIndexed { i, frame -> assertArrayEquals("old-$i".toByteArray(), bob.decrypt(frame)) }

        // 取"当前状态"（含最新链密钥、无跳过缓存），尝试解历史消息
        val present = E2eeSession.deserialize(E2eeSession.serialize(bob))
        assertEquals(50, present.receiveIndex)
        assertEquals(0, present.skippedKeyCount)
        assertThrows(CryptoException::class.java) { present.decrypt(history[10]) }
        assertThrows(CryptoException::class.java) { present.decrypt(history[49]) }

        // 对照：新消息仍然可以正常解
        assertArrayEquals("new".toByteArray(), present.decrypt(alice.encrypt("new".toByteArray())))
    }

    @Test
    fun `serialize and deserialize keeps session usable`() {
        val (alice, bob) = newPair()
        repeat(10) { bob.decrypt(alice.encrypt("m$it".toByteArray())) }
        // bob 跳过两条
        val f10 = alice.encrypt("m10".toByteArray())
        val f11 = alice.encrypt("m11".toByteArray())
        bob.decrypt(f11)

        val blob = E2eeSession.serialize(bob)
        val restored = E2eeSession.deserialize(blob)
        assertEquals(bob.receiveIndex, restored.receiveIndex)
        assertEquals(bob.skippedKeyCount, restored.skippedKeyCount)
        assertArrayEquals("m10".toByteArray(), restored.decrypt(f10))
        assertArrayEquals("m12".toByteArray(), restored.decrypt(alice.encrypt("m12".toByteArray())))

        // 序列化后再序列化，结果稳定
        val aliceBlob = E2eeSession.serialize(alice)
        val restoredAlice = E2eeSession.deserialize(aliceBlob)
        assertEquals(alice.sendIndex, restoredAlice.sendIndex)
        assertArrayEquals("after".toByteArray(), restored.decrypt(restoredAlice.encrypt("after".toByteArray())))
    }

    @Test
    fun `deserialize rejects malformed blob`() {
        val (alice, _) = newPair()
        val blob = E2eeSession.serialize(alice)
        assertThrows(IllegalArgumentException::class.java) { E2eeSession.deserialize(blob.copyOfRange(0, blob.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { E2eeSession.deserialize(ByteArray(10)) }
        val badVersion = blob.copyOf()
        badVersion[0] = 9
        assertThrows(IllegalArgumentException::class.java) { E2eeSession.deserialize(badVersion) }
    }

    @Test
    fun `empty and large payloads work`() {
        val (alice, bob) = newPair()
        assertArrayEquals(ByteArray(0), bob.decrypt(alice.encrypt(ByteArray(0))))
        val big = ByteArray(500_000) { (it % 251).toByte() }
        assertArrayEquals(big, bob.decrypt(alice.encrypt(big)))
    }

    @Test
    fun `safety number matches on both sides`() {
        val (alice, bob) = newPair()
        assertEquals(alice.safetyNumber(), bob.safetyNumber())
    }
}
