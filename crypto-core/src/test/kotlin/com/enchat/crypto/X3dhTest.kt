package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class X3dhTest {

    private fun bundleFor(bob: IdentityKeyPair, bobUserId: Long, spk: SignedPreKey, opk: OneTimePreKey? = null) =
        PreKeyBundle(
            userId = bobUserId,
            identityEd25519Public = bob.ed25519PublicKey,
            identityX25519Public = bob.x25519PublicKey,
            signedPreKeyId = spk.id,
            signedPreKeyPublic = spk.publicKey,
            signedPreKeySignature = spk.signature,
            oneTimePreKeyId = opk?.id,
            oneTimePreKeyPublic = opk?.publicKey
        )

    @Test
    fun `both sides derive identical shared secret with OPK`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(11, bob)
        val opk = OneTimePreKey.generate(22)
        val bundle = bundleFor(bob, 2001L, spk, opk)

        assertTrue(bundle.verifySelfConsistent())

        val (aliceSession, initMsg) = E2eeSession.createAsSender(alice, 1001L, bundle)
        val bobSession = E2eeSession.createAsReceiver(bob, spk, opk, initMsg)

        assertArrayEquals(aliceSession.sessionId, bobSession.sessionId)
        assertTrue(aliceSession.isInitiator)
        assertFalse(bobSession.isInitiator)

        val ct = aliceSession.encrypt("端到端第一条消息".toByteArray())
        assertArrayEquals("端到端第一条消息".toByteArray(), bobSession.decrypt(ct))
    }

    @Test
    fun `both sides derive identical shared secret without OPK`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(7, bob)
        val bundle = bundleFor(bob, 2002L, spk, null)

        val (aliceSession, initMsg) = E2eeSession.createAsSender(alice, 1002L, bundle)
        val bobSession = E2eeSession.createAsReceiver(bob, spk, null, initMsg)
        val ct = aliceSession.encrypt("no opk".toByteArray())
        assertArrayEquals("no opk".toByteArray(), bobSession.decrypt(ct))
    }

    @Test
    fun `tampered init message signature is rejected`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(1, bob)
        val opk = OneTimePreKey.generate(2)
        val bundle = bundleFor(bob, 3001L, spk, opk)

        val (_, initMsg) = E2eeSession.createAsSender(alice, 1003L, bundle)
        val tampered = X3dh.InitMessage.fromBytes(initMsg.toBytes()).let {
            val bad = it.toBytes().copyOf()
            bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0x01).toByte()
            X3dh.InitMessage.fromBytes(bad)
        }
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSession.createAsReceiver(bob, spk, opk, tampered)
        }
    }

    @Test
    fun `server cannot forge prekey bundle without user signature`() {
        val bob = IdentityKeyPair.generate()
        val attacker = IdentityKeyPair.generate()
        // 攻击者伪造的 SPK：用自己的身份签名，却塞进 bob 的包里
        val forgedSpk = SignedPreKey.generate(5, attacker)
        val bundle = PreKeyBundle(
            userId = 4001L,
            identityEd25519Public = bob.ed25519PublicKey,
            identityX25519Public = bob.x25519PublicKey,
            signedPreKeyId = forgedSpk.id,
            signedPreKeyPublic = forgedSpk.publicKey,
            signedPreKeySignature = forgedSpk.signature
        )
        assertFalse(bundle.verifySelfConsistent())
        val alice = IdentityKeyPair.generate()
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSession.createAsSender(alice, 1004L, bundle)
        }
    }

    @Test
    fun `spk id mismatch is rejected`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(3, bob)
        val opk = OneTimePreKey.generate(4)
        val bundle = bundleFor(bob, 5001L, spk, opk)
        val (_, initMsg) = E2eeSession.createAsSender(alice, 1005L, bundle)

        val wrongSpk = SignedPreKey.generate(999, bob)
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSession.createAsReceiver(bob, wrongSpk, opk, initMsg)
        }
    }

    @Test
    fun `opk requirement mismatch is rejected`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(8, bob)
        val opk = OneTimePreKey.generate(9)
        val bundle = bundleFor(bob, 6001L, spk, opk)
        val (_, initMsg) = E2eeSession.createAsSender(alice, 1006L, bundle)

        // 消息要求 OPK，但接收方未提供
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSession.createAsReceiver(bob, spk, null, initMsg)
        }
        // 消息没有 OPK，但接收方提供了一个
        val bundleNoOpk = bundleFor(bob, 6002L, spk, null)
        val (_, initNoOpk) = E2eeSession.createAsSender(alice, 1007L, bundleNoOpk)
        assertThrows(IllegalArgumentException::class.java) {
            E2eeSession.createAsReceiver(bob, spk, opk, initNoOpk)
        }
    }

    @Test
    fun `init message serialization round trips`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(12, bob)
        val opk = OneTimePreKey.generate(13)
        val bundle = bundleFor(bob, 7001L, spk, opk)
        val (_, initMsg) = E2eeSession.createAsSender(alice, 1008L, bundle)

        val bytes = initMsg.toBytes()
        assertEquals(X3dh.InitMessage.SERIALIZED_SIZE, bytes.size)
        val parsed = X3dh.InitMessage.fromBytes(bytes)
        assertTrue(parsed.verifySignature())
        assertArrayEquals(initMsg.toBytes(), parsed.toBytes())
        assertEquals(1008L, parsed.senderUserId)
        assertEquals(7001L, parsed.receiverUserId)
        assertEquals(12, parsed.receiverSignedPreKeyId)
        assertEquals(13, parsed.receiverOneTimePreKeyId)
    }

    @Test
    fun `identity key pair serialization round trips`() {
        val id = IdentityKeyPair.generate()
        val restored = IdentityKeyPair.fromBytes(id.toBytes())
        assertArrayEquals(id.ed25519PrivateKey, restored.ed25519PrivateKey)
        assertArrayEquals(id.ed25519PublicKey, restored.ed25519PublicKey)
        assertArrayEquals(id.x25519PrivateKey, restored.x25519PrivateKey)
        assertArrayEquals(id.x25519PublicKey, restored.x25519PublicKey)
    }

    @Test
    fun `safety number is symmetric and stable`() {
        val a = IdentityKeyPair.generate()
        val b = IdentityKeyPair.generate()
        val n1 = SafetyNumber.compute(a.ed25519PublicKey, b.ed25519PublicKey)
        val n2 = SafetyNumber.compute(b.ed25519PublicKey, a.ed25519PublicKey)
        assertEquals(n1, n2)
        assertEquals(12, n1.split(' ').size)
        val c = IdentityKeyPair.generate()
        assertNotEquals(n1, SafetyNumber.compute(a.ed25519PublicKey, c.ed25519PublicKey))
    }

    @Test
    fun `different sessions produce different secrets`() {
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(21, bob)
        val bundle = bundleFor(bob, 8001L, spk)
        val (s1, m1) = E2eeSession.createAsSender(alice, 1009L, bundle)
        val (s2, m2) = E2eeSession.createAsSender(alice, 1009L, bundle)
        assertFalse(s1.sessionId.contentEquals(s2.sessionId))
        assertFalse(m1.senderEphemeralPublic.contentEquals(m2.senderEphemeralPublic))
    }
}
