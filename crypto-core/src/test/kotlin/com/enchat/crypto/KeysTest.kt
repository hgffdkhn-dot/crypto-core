package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class KeysTest {

    @Test
    fun `identity key pair round trips through bytes`() {
        val identity = IdentityKeyPair.generate()
        val restored = IdentityKeyPair.fromBytes(identity.toBytes())
        assertArrayEquals(identity.ed25519PublicKey, restored.ed25519PublicKey)
        assertArrayEquals(identity.x25519PublicKey, restored.x25519PublicKey)
        assertArrayEquals(identity.ed25519PrivateKey, restored.ed25519PrivateKey)
        assertThrows(IllegalArgumentException::class.java) { IdentityKeyPair.fromBytes(ByteArray(10)) }
    }

    @Test
    fun `signed prekey round trips through storage bytes`() {
        val identity = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(7, identity)
        val restored = SignedPreKey.fromStorageBytes(SignedPreKey.toStorageBytes(spk))
        assertEquals(spk.id, restored.id)
        assertArrayEquals(spk.publicKey, restored.publicKey)
        assertArrayEquals(spk.privateKeyBytes(), restored.privateKeyBytes())
        assertArrayEquals(spk.signature, restored.signature)
        assertTrue(restored.verify(identity.ed25519PublicKey))
        assertThrows(IllegalArgumentException::class.java) { SignedPreKey.fromStorageBytes(ByteArray(10)) }
    }

    @Test
    fun `one time prekey restores from private key with matching public key`() {
        val opk = OneTimePreKey.generate(42)
        val restored = OneTimePreKey.fromPrivateKey(42, opk.privateKeyBytes())
        assertEquals(42, restored.id)
        assertArrayEquals(opk.publicKey, restored.publicKey, "公钥必须由私钥确定性派生")
        assertArrayEquals(opk.privateKeyBytes(), restored.privateKeyBytes())
    }

    @Test
    fun `restored prekeys still establish a working session`() {
        // 持久化恢复后的密钥必须真的能建会话，否则会出现"重启后收不到消息"
        val alice = IdentityKeyPair.generate()
        val bob = IdentityKeyPair.generate()
        val spk = SignedPreKey.generate(1, bob)
        val opk = OneTimePreKey.generate(5)

        val restoredSpk = SignedPreKey.fromStorageBytes(SignedPreKey.toStorageBytes(spk))
        val restoredOpk = OneTimePreKey.fromPrivateKey(5, opk.privateKeyBytes())

        val bundle = PreKeyBundle(
            userId = 1001L,
            identityEd25519Public = bob.ed25519PublicKey,
            identityX25519Public = bob.x25519PublicKey,
            signedPreKeyId = restoredSpk.id,
            signedPreKeyPublic = restoredSpk.publicKey,
            signedPreKeySignature = restoredSpk.signature,
            oneTimePreKeyId = restoredOpk.id,
            oneTimePreKeyPublic = restoredOpk.publicKey
        )
        val (aliceSession, init) = E2eeSession.createAsSender(alice, 1L, bundle)
        val bobSession = E2eeSession.createAsReceiver(bob, restoredSpk, restoredOpk, init)
        assertEquals("hello", String(bobSession.decrypt(aliceSession.encrypt("hello".toByteArray()))))
    }
}
