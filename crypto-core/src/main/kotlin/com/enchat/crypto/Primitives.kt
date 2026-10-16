package com.enchat.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** X25519 密钥对（32 字节私钥 / 32 字节公钥）。 */
data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/** Ed25519 密钥对（32 字节种子作为私钥表示 / 32 字节公钥）。 */
data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

/**
 * 密码学原语层。
 *
 * 全部基于 BouncyCastle 的 lightweight API（不依赖 JCE Provider 注册），
 * 因此 Android 与 JVM 服务端行为完全一致，不受系统 Provider 差异影响。
 *
 * 已在本仓库单元测试中用 RFC 7748 / RFC 8032 / RFC 5869 官方向量校验。
 */
object Primitives {

    const val X25519_KEY_SIZE = 32
    const val ED25519_KEY_SIZE = 32
    const val AES256_KEY_SIZE = 32
    const val GCM_NONCE_SIZE = 12
    const val GCM_TAG_SIZE_BITS = 128
    const val GCM_TAG_SIZE_BYTES = 16
    /** HKDF-SHA256 单次输出上限：255 * HashLen。 */
    const val HKDF_MAX_OUTPUT = 255 * 32

    // ---------------- X25519 ----------------

    fun x25519GenerateKeyPair(): X25519KeyPair {
        val priv = X25519PrivateKeyParameters(Bytes.random(X25519_KEY_SIZE))
        val pub = priv.generatePublicKey()
        return X25519KeyPair(priv.encoded, pub.encoded)
    }

    fun x25519PublicFromPrivate(privateKey: ByteArray): ByteArray {
        require(privateKey.size == X25519_KEY_SIZE) { "X25519 private key must be 32 bytes" }
        return X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
    }

    /**
     * X25519 共享密钥。
     * @throws CryptoException 当输入长度非法、或协商结果为全零（低阶/无效公钥攻击）。
     */
    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == X25519_KEY_SIZE) { "X25519 private key must be 32 bytes" }
        require(peerPublicKey.size == X25519_KEY_SIZE) { "X25519 public key must be 32 bytes" }
        val out = ByteArray(X25519_KEY_SIZE)
        try {
            X25519AgreementCompat().calculate(privateKey, peerPublicKey, out)
        } catch (t: Throwable) {
            throw CryptoException("X25519 agreement failed", t)
        }
        if (out.all { it == 0.toByte() }) {
            Bytes.zeroize(out)
            throw CryptoException("X25519 shared secret is all-zero (invalid public key)")
        }
        return out
    }

    // ---------------- Ed25519 ----------------

    fun ed25519GenerateKeyPair(): Ed25519KeyPair {
        val priv = Ed25519PrivateKeyParameters(Bytes.random(ED25519_KEY_SIZE))
        val pub = priv.generatePublicKey()
        return Ed25519KeyPair(priv.encoded, pub.encoded)
    }

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        require(privateKey.size == ED25519_KEY_SIZE) { "Ed25519 private key must be 32 bytes" }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == ED25519_KEY_SIZE) { "Ed25519 public key must be 32 bytes" }
        if (signature.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (t: Throwable) {
            false
        }
    }

    // ---------------- HKDF / Hash / MAC ----------------

    /**
     * HKDF-SHA256。
     * @param ikm  输入密钥材料
     * @param salt 盐，可为空；跨协议调用请务必传入，以隔离不同上下文
     * @param info 上下文绑定串，必须唯一标识协议/方向/参与者，防止跨协议密钥复用
     */
    fun hkdf(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        require(length <= HKDF_MAX_OUTPUT) { "length exceeds HKDF-SHA256 limit ($HKDF_MAX_OUTPUT)" }
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFBytesGeneratorParams(ikm, salt, info))
        val out = ByteArray(length)
        gen.generateBytes(out, 0, length)
        return out
    }

    fun sha256(data: ByteArray): ByteArray {
        val d = SHA256Digest()
        d.update(data, 0, data.size)
        val out = ByteArray(d.digestSize)
        d.doFinal(out, 0)
        return out
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }

    // ---------------- AES-256-GCM ----------------

    /**
     * AES-256-GCM 加密，输出 ciphertext || tag(16B)。
     * @param aad 附加认证数据（不加密但参与认证），务必包含版本/会话/序号等上下文
     */
    fun aes256GcmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == AES256_KEY_SIZE) { "AES key must be 32 bytes" }
        require(nonce.size == GCM_NONCE_SIZE) { "GCM nonce must be 12 bytes" }
        val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
        gcm.init(true, AEADParameters(KeyParameter(key), GCM_TAG_SIZE_BITS, nonce, aad))
        val out = ByteArray(gcm.getOutputSize(plaintext.size))
        var n = 0
        if (plaintext.isNotEmpty()) n = gcm.processBytes(plaintext, 0, plaintext.size, out, 0)
        n += gcm.doFinal(out, n)
        return out
    }

    /**
     * AES-256-GCM 解密。任何认证失败都抛 [CryptoException]，不返回可区分错误。
     */
    fun aes256GcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray? = null): ByteArray {
        require(key.size == AES256_KEY_SIZE) { "AES key must be 32 bytes" }
        require(nonce.size == GCM_NONCE_SIZE) { "GCM nonce must be 12 bytes" }
        if (ciphertextWithTag.size < GCM_TAG_SIZE_BYTES) throw CryptoException("ciphertext too short")
        val gcm = GCMBlockCipher.newInstance(AESEngine.newInstance())
        gcm.init(false, AEADParameters(KeyParameter(key), GCM_TAG_SIZE_BITS, nonce, aad))
        val out = ByteArray(gcm.getOutputSize(ciphertextWithTag.size))
        var n = 0
        if (ciphertextWithTag.isNotEmpty()) {
            n = gcm.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
        }
        try {
            n += gcm.doFinal(out, n)
        } catch (t: Throwable) {
            Bytes.zeroize(out)
            throw CryptoException("AES-GCM authentication failed", t)
        }
        return out.copyOf(n)
    }
}

/**
 * 对 BouncyCastle [org.bouncycastle.crypto.agreement.X25519Agreement] 的薄封装。
 * 单独成类，便于将来在 Android 上替换为平台 XDH / JCE 实现而不用改上层代码。
 */
internal class X25519AgreementCompat {
    fun calculate(privateKey: ByteArray, peerPublicKey: ByteArray, out: ByteArray) {
        val agreement = org.bouncycastle.crypto.agreement.X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPublicKey, 0), out, 0)
    }
}

/**
 * HKDF 参数适配：salt 为 null/空时也要走标准 extract（而不是 skipExtract），
 * 保证与 RFC 5869 一致。
 */
internal fun HKDFBytesGeneratorParams(ikm: ByteArray, salt: ByteArray?, info: ByteArray): org.bouncycastle.crypto.params.HKDFParameters =
    org.bouncycastle.crypto.params.HKDFParameters(ikm, salt ?: ByteArray(0), info)
