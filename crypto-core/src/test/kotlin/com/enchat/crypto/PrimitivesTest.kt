package com.enchat.crypto

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 用 RFC 官方向量校验底层原语实现是否正确。
 * 这些向量是"实现自检"的地基：任何一条不通过，上层协议都不必再看。
 */
class PrimitivesTest {

    // ---------- RFC 7748 §6.1: X25519 ----------
    @Test
    fun `x25519 matches RFC 7748 test vector`() {
        val alicePriv = Bytes.fromHex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val alicePub = Bytes.fromHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPriv = Bytes.fromHex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val bobPub = Bytes.fromHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val expected = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        assertEquals(alicePub.toList(), Primitives.x25519PublicFromPrivate(alicePriv).toList())
        assertEquals(bobPub.toList(), Primitives.x25519PublicFromPrivate(bobPriv).toList())
        assertEquals(expected, Bytes.toHex(Primitives.x25519SharedSecret(alicePriv, bobPub)))
        assertEquals(expected, Bytes.toHex(Primitives.x25519SharedSecret(bobPriv, alicePub)))
    }

    @Test
    fun `x25519 random keypairs agree in both directions`() {
        repeat(20) {
            val a = Primitives.x25519GenerateKeyPair()
            val b = Primitives.x25519GenerateKeyPair()
            assertEquals(
                Bytes.toHex(Primitives.x25519SharedSecret(a.privateKey, b.publicKey)),
                Bytes.toHex(Primitives.x25519SharedSecret(b.privateKey, a.publicKey))
            )
        }
    }

    @Test
    fun `x25519 rejects all-zero public key`() {
        val a = Primitives.x25519GenerateKeyPair()
        assertThrows(CryptoException::class.java) {
            Primitives.x25519SharedSecret(a.privateKey, ByteArray(32))
        }
    }

    @Test
    fun `x25519 rejects wrong length inputs`() {
        val a = Primitives.x25519GenerateKeyPair()
        assertThrows(IllegalArgumentException::class.java) {
            Primitives.x25519SharedSecret(ByteArray(31), a.publicKey)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Primitives.x25519SharedSecret(a.privateKey, ByteArray(33))
        }
    }

    // ---------- RFC 8032 §7.1 TEST 1: Ed25519 ----------
    @Test
    fun `ed25519 matches RFC 8032 test vector`() {
        val priv = Bytes.fromHex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
        val pub = Bytes.fromHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
        val expectedSig =
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

        val identity = IdentityKeyPair.fromPrivateKeys(priv, ByteArray(32) { 1 })
        assertEquals(Bytes.toHex(pub), Bytes.toHex(identity.ed25519PublicKey))
        val sig = Primitives.ed25519Sign(priv, ByteArray(0))
        assertEquals(expectedSig, Bytes.toHex(sig))
        assertTrue(Primitives.ed25519Verify(pub, ByteArray(0), sig))
    }

    @Test
    fun `ed25519 rejects tampered message and signature`() {
        val kp = Primitives.ed25519GenerateKeyPair()
        val msg = "hello".toByteArray()
        val sig = Primitives.ed25519Sign(kp.privateKey, msg)
        assertTrue(Primitives.ed25519Verify(kp.publicKey, msg, sig))
        assertFalse(Primitives.ed25519Verify(kp.publicKey, "hellp".toByteArray(), sig))

        val badSig = sig.copyOf()
        badSig[0] = (badSig[0].toInt() xor 0x01).toByte()
        assertFalse(Primitives.ed25519Verify(kp.publicKey, msg, badSig))
        assertFalse(Primitives.ed25519Verify(kp.publicKey, msg, ByteArray(63)))
    }

    // ---------- RFC 5869 Test Case 1: HKDF-SHA256 ----------
    @Test
    fun `hkdf matches RFC 5869 test vector`() {
        val ikm = ByteArray(22) { 0x0b.toByte() }
        val salt = Bytes.fromHex("000102030405060708090a0b0c")
        val info = Bytes.fromHex("f0f1f2f3f4f5f6f7f8f9")
        val expected =
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
        assertEquals(expected, Bytes.toHex(Primitives.hkdf(ikm, salt, info, 42)))
    }

    @Test
    fun `hkdf rejects oversized output`() {
        assertThrows(IllegalArgumentException::class.java) {
            Primitives.hkdf(ByteArray(32), ByteArray(16), ByteArray(4), Primitives.HKDF_MAX_OUTPUT + 1)
        }
    }

    // ---------- AES-256-GCM ----------
    @Test
    fun `aes-gcm round trip and tamper detection`() {
        val key = Bytes.random(32)
        val nonce = Bytes.random(12)
        val pt = "这是一条包含 emoji 😀 的消息".toByteArray()
        val aad = "ctx".toByteArray()

        val ct = Primitives.aes256GcmEncrypt(key, nonce, pt, aad)
        assertNotEquals(Bytes.toHex(pt), Bytes.toHex(ct))
        assertEquals(pt.size + 16, ct.size)
        assertArrayEquals(pt, Primitives.aes256GcmDecrypt(key, nonce, ct, aad))

        // 篡改密文
        val bad = ct.copyOf()
        bad[0] = (bad[0].toInt() xor 0x80).toByte()
        assertThrows(CryptoException::class.java) { Primitives.aes256GcmDecrypt(key, nonce, bad, aad) }
        // 篡改 AAD
        assertThrows(CryptoException::class.java) {
            Primitives.aes256GcmDecrypt(key, nonce, ct, "ctx2".toByteArray())
        }
        // 错误 nonce
        assertThrows(CryptoException::class.java) {
            Primitives.aes256GcmDecrypt(key, Bytes.random(12), ct, aad)
        }
    }

    /** 与 JDK JCE 互操作：确认我们的 GCM 封装与标准实现字节兼容。 */
    @Test
    fun `aes-gcm interoperates with JCE in both directions`() {
        val key = Bytes.random(32)
        val nonce = Bytes.random(12)
        val pt = ByteArray(137) { it.toByte() }
        val aad = "aad".toByteArray()

        val jceCt = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(pt)
        }
        assertArrayEquals(pt, Primitives.aes256GcmDecrypt(key, nonce, jceCt, aad))

        val ourCt = Primitives.aes256GcmEncrypt(key, nonce, pt, aad)
        val dec = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(ourCt)
        }
        assertArrayEquals(pt, dec)
    }

    @Test
    fun `aes-gcm handles empty plaintext`() {
        val key = Bytes.random(32)
        val nonce = Bytes.random(12)
        val ct = Primitives.aes256GcmEncrypt(key, nonce, ByteArray(0), null)
        assertEquals(16, ct.size)
        assertArrayEquals(ByteArray(0), Primitives.aes256GcmDecrypt(key, nonce, ct, null))
    }

    // ---------- 杂项 ----------
    @Test
    fun `constant time equals and zeroize work`() {
        assertTrue(Bytes.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Bytes.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 2)))
        val b = byteArrayOf(9, 9, 9)
        Bytes.zeroize(b)
        assertArrayEquals(byteArrayOf(0, 0, 0), b)
    }

    @Test
    fun `big endian helpers round trip`() {
        val v = 0x12345678
        assertArrayEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), Bytes.writeUInt32BE(v))
        assertEquals(v, Bytes.readUInt32BE(Bytes.writeUInt32BE(v), 0))
        val l = 0x0102030405060708L
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            Bytes.writeUInt64BE(l)
        )
        assertEquals(l, Bytes.readUInt64BE(Bytes.writeUInt64BE(l), 0))
    }

    @Test
    fun `length prefix framing is unambiguous`() {
        val a = Bytes.writeLp(byteArrayOf(1, 2))
        val b = Bytes.writeLp(byteArrayOf(1, 2, 3))
        assertNotEquals(Bytes.toHex(a), Bytes.toHex(b))
        assertArrayEquals(byteArrayOf(0, 0), Bytes.writeLp(null))
    }
}
