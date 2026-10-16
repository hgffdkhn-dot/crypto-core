package com.enchat.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * 注册/登录口令哈希。
 *
 * 需求里"数字密码"熵很低（6 位仅 10^6 种，离线爆破成本近乎为零），因此本实现提供两层保护：
 * 1. Argon2id（抗 GPU/ASIC 的内存硬 KDF）；
 * 2. 可选 pepper：把 Argon2id 输出再过一次 HMAC-SHA256(pepper)，pepper 存放于 KMS/HSM，
 *    使"只拖库不拿到 pepper"的攻击者无法离线爆破。
 *
 * 仅靠本模块仍不够，服务端必须同时做：登录失败限速、单账号/IP 风控、异常登录提醒。
 */
object PasswordHash {

    data class Params(
        val memoryKiB: Int,
        val iterations: Int,
        val parallelism: Int,
        val keyLength: Int = 32
    ) {
        init {
            require(memoryKiB >= 8 * parallelism) { "memory too small for parallelism" }
            require(iterations >= 1) { "iterations must be >= 1" }
            require(parallelism >= 1) { "parallelism must be >= 1" }
            require(keyLength in 16..64) { "keyLength should be 16..64" }
        }
    }

    /** 服务端默认：64 MiB / t=3 / p=1（OWASP 建议量级，按并发压测再调）。 */
    val SERVER_PARAMS = Params(memoryKiB = 64 * 1024, iterations = 3, parallelism = 1)

    /** 移动端默认（若需要在端侧做派生）：32 MiB / t=2 / p=1，避免低端机卡顿。 */
    val MOBILE_PARAMS = Params(memoryKiB = 32 * 1024, iterations = 2, parallelism = 1)

    private const val SALT_SIZE = 16
    private const val PHC_PREFIX = "argon2id"

    /** 校验口令：空列表 = 通过。策略见 [PasswordPolicy]。 */
    fun validatePassword(password: String, username: String? = null): List<String> =
        PasswordPolicy.validate(password, username).problems

    /**
     * 生成口令哈希。
     * @param pepper 服务端密钥（不入库）；传 null 则不加 pepper（不建议生产使用）
     * @return PHC 风格字符串：`argon2id$v=19$m=..,t=..,p=..$<b64 salt>$<b64 hash>`
     */
    fun hash(password: String, params: Params = SERVER_PARAMS, pepper: ByteArray? = null): String {
        val salt = Bytes.random(SALT_SIZE)
        val raw = deriveRaw(password.toByteArray(Charsets.UTF_8), salt, params)
        val stored = applyPepper(raw, pepper)
        Bytes.zeroize(raw)
        return encode(params, salt, stored)
    }

    /**
     * 用指定盐派生（不生成随机盐）。
     * 用途：与外部系统对齐、迁移既有哈希，以及用参考向量做实现自检。
     */
    fun derive(
        password: ByteArray,
        salt: ByteArray,
        params: Params = SERVER_PARAMS,
        pepper: ByteArray? = null
    ): ByteArray {
        require(salt.size >= 8) { "salt should be at least 8 bytes" }
        val raw = deriveRaw(password, salt, params)
        val stored = applyPepper(raw, pepper)
        Bytes.zeroize(raw)
        return stored
    }

    /** 校验口令；比较过程为常数时间。 */
    fun verify(password: String, encoded: String, pepper: ByteArray? = null): Boolean {
        return try {
            val parsed = decode(encoded)
            val raw = deriveRaw(password.toByteArray(Charsets.UTF_8), parsed.salt, parsed.params)
            val stored = applyPepper(raw, pepper)
            Bytes.zeroize(raw)
            Bytes.constantTimeEquals(stored, parsed.hash)
        } catch (t: Throwable) {
            false
        }
    }

    /** 参数是否需要升级（用于登录成功后透明重哈希）。 */
    fun needsRehash(encoded: String, params: Params = SERVER_PARAMS): Boolean {
        val parsed = runCatching { decode(encoded) }.getOrNull() ?: return true
        return parsed.params != params
    }

    private fun deriveRaw(password: ByteArray, salt: ByteArray, params: Params): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(params.memoryKiB)
            .withIterations(params.iterations)
            .withParallelism(params.parallelism)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        val gen = Argon2BytesGenerator()
        gen.init(builder.build())
        val out = ByteArray(params.keyLength)
        gen.generateBytes(password, out, 0, out.size)
        return out
    }

    private fun applyPepper(raw: ByteArray, pepper: ByteArray?): ByteArray =
        if (pepper == null) raw.copyOf() else Primitives.hmacSha256(pepper, raw)

    private fun encode(params: Params, salt: ByteArray, hash: ByteArray): String =
        "$PHC_PREFIX\$v=${Argon2Parameters.ARGON2_VERSION_13}\$m=${params.memoryKiB},t=${params.iterations},p=${params.parallelism}\$${Bytes.toBase64(salt)}\$${Bytes.toBase64(hash)}"

    private data class Parsed(val params: Params, val salt: ByteArray, val hash: ByteArray)

    private fun decode(encoded: String): Parsed {
        val parts = encoded.split('$')
        require(parts.size == 5) { "malformed password hash" }
        require(parts[0] == PHC_PREFIX) { "unsupported algorithm: ${parts[0]}" }
        val version = parts[1].removePrefix("v=").toInt()
        require(version == Argon2Parameters.ARGON2_VERSION_13) { "unsupported argon2 version: $version" }
        val kv = parts[2].split(',').associate {
            val (k, v) = it.split('=')
            k to v.toInt()
        }
        val params = Params(
            memoryKiB = kv["m"] ?: throw IllegalArgumentException("missing m"),
            iterations = kv["t"] ?: throw IllegalArgumentException("missing t"),
            parallelism = kv["p"] ?: throw IllegalArgumentException("missing p"),
            keyLength = 32
        )
        return Parsed(params, Bytes.fromBase64(parts[3]), Bytes.fromBase64(parts[4]))
    }
}
