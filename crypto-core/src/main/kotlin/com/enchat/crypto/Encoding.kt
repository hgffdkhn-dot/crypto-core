package com.enchat.crypto

import org.bouncycastle.util.Arrays
import org.bouncycastle.util.encoders.Base64
import org.bouncycastle.util.encoders.Hex
import java.security.SecureRandom

/**
 * 字节层工具：随机、拼接、大端整数、常数时间比较、擦除、编解码。
 *
 * 设计约束：
 * - 这里的所有函数必须是纯 JVM/Kotlin + BouncyCastle 轻量 API，以便 Android 客户端与 Ktor 服务端复用同一份实现。
 * - 任何涉及密钥/口令的缓冲，用尽后调用 [zeroize]；注意 JVM 无法保证 JIT/GC 复制的残留副本被清除（见 README 风险项）。
 */
object Bytes {

    private val secureRandom: SecureRandom = SecureRandom()

    /** 生成 n 个密码学安全随机字节。 */
    fun random(n: Int): ByteArray {
        require(n >= 0) { "length must be >= 0" }
        val out = ByteArray(n)
        secureRandom.nextBytes(out)
        return out
    }

    /** 生成非负随机 Int（用于预密钥 id 等）。 */
    fun randomInt(boundExclusive: Int): Int {
        require(boundExclusive > 0)
        return secureRandom.nextInt(boundExclusive)
    }

    fun concat(vararg parts: ByteArray): ByteArray {
        val total = parts.sumOf { it.size }
        val out = ByteArray(total)
        var off = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, off, p.size)
            off += p.size
        }
        return out
    }

    /** 常数时间比较，避免通过比较耗时泄露密钥信息。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        Arrays.constantTimeAreEqual(a, b)

    /** 原地清零；同时返回自身便于链式调用。 */
    fun zeroize(a: ByteArray): ByteArray {
        java.util.Arrays.fill(a, 0)
        return a
    }

    // ---- 长度前缀分块：用于把多个变长字段无歧义地拼接进 HKDF info / AAD ----
    fun writeUInt32BE(value: Int): ByteArray =
        byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte()
        )

    fun readUInt32BE(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF shl 24) or
            (src[off + 1].toInt() and 0xFF shl 16) or
            (src[off + 2].toInt() and 0xFF shl 8) or
            (src[off + 3].toInt() and 0xFF)

    fun writeUInt64BE(value: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0..7) out[i] = (value ushr (56 - 8 * i)).toByte()
        return out
    }

    fun readUInt64BE(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0..7) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }

    /** 长度前缀写入（2 字节大端长度 + 内容），避免字段边界歧义造成 AAD 可伪造。 */
    fun writeLp(data: ByteArray?): ByteArray {
        if (data == null) return byteArrayOf(0, 0)
        require(data.size <= 0xFFFF) { "field too large: ${data.size}" }
        return concat(byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()), data)
    }

    // ---- 编解码 ----
    fun toHex(data: ByteArray): String = Hex.toHexString(data)

    fun fromHex(s: String): ByteArray = Hex.decode(s)

    fun toBase64(data: ByteArray): String = Base64.toBase64String(data)

    fun fromBase64(s: String): ByteArray = Base64.decode(s)
}

/**
 * 统一异常类型：所有解密/验签失败都抛出它，且不携带"哪一步失败"的细节，
 * 以免为攻击者提供 padding oracle / 分段 oracle。
 */
class CryptoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
