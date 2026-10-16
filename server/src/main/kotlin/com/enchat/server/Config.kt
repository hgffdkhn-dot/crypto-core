package com.enchat.server

import com.enchat.crypto.Bytes
import com.enchat.crypto.IdentityKeyPair
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端配置。全部可用环境变量覆盖；默认值只适合本地开发与集成测试，**不适合直接上生产**。
 */
data class Config(
    val port: Int,
    val host: String,
    val jdbcUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val dbPoolSize: Int,
    val pepper: ByteArray,
    val serverKeyFile: String,
    val uploadDir: String,
    val publicBaseUrl: String,
    val e2eeCiphertextRetentionDays: Int,
    val isH2: Boolean
) {
    companion object {
        fun fromEnv(): Config {
            val jdbcUrl = env("ENCHAT_JDBC_URL", "jdbc:h2:mem:enchat;DB_CLOSE_DELAY=-1")
            return Config(
                port = env("ENCHAT_PORT", "8080").toInt(),
                host = env("ENCHAT_HOST", "0.0.0.0"),
                jdbcUrl = jdbcUrl,
                dbUser = env("ENCHAT_DB_USER", "sa"),
                dbPassword = env("ENCHAT_DB_PASSWORD", ""),
                dbPoolSize = env("ENCHAT_DB_POOL", "8").toInt(),
                // pepper 必须来自 KMS/密钥管理；默认随机值仅用于本地跑通（重启后旧口令全部失效）
                pepper = envOrNull("ENCHAT_PEPPER_B64")?.let { Bytes.fromBase64(it) } ?: Bytes.random(32),
                serverKeyFile = env("ENCHAT_SERVER_KEY_FILE", "./data/server-identity.key"),
                uploadDir = env("ENCHAT_UPLOAD_DIR", "./data/uploads"),
                publicBaseUrl = env("ENCHAT_PUBLIC_BASE_URL", "http://localhost:8080"),
                e2eeCiphertextRetentionDays = env("ENCHAT_E2EE_RETENTION_DAYS", "30").toInt(),
                isH2 = jdbcUrl.startsWith("jdbc:h2")
            )
        }

        private fun env(key: String, default: String): String = System.getenv(key) ?: default
        private fun envOrNull(key: String): String? = System.getenv(key)
    }
}

/**
 * 服务器长期身份密钥（Ed25519 用于握手签名，X25519 用于 DH）。
 *
 * 客户端必须把其中的公钥硬编码/证书固定，否则传输层那层加密可被中间人绕过。
 * 首次启动自动生成并落盘；**更换此文件会导致所有已装客户端连不上**（需发版更新 pin）。
 */
class ServerKeys(internal val identityPair: IdentityKeyPair) {

    val identity: IdentityKeyPair get() = identityPair

    val ed25519Public: ByteArray get() = identityPair.ed25519PublicKey
    val x25519Public: ByteArray get() = identityPair.x25519PublicKey

    val ed25519PublicB64: String get() = Bytes.toBase64(identityPair.ed25519PublicKey)
    val x25519PublicB64: String get() = Bytes.toBase64(identityPair.x25519PublicKey)

    companion object {
        /** 从文件加载；不存在则生成并保存。文件权限应尽量收紧（600）。 */
        fun loadOrCreate(path: String): ServerKeys {
            val file = File(path)
            val pair = if (file.exists() && file.length() == 128L) {
                IdentityKeyPair.fromBytes(file.readBytes())
            } else {
                val generated = IdentityKeyPair.generate()
                file.parentFile?.mkdirs()
                file.writeBytes(generated.toBytes())
                generated
            }
            return ServerKeys(pair)
        }
    }
}

/** 会话令牌：随机 32 字节，服务端只存 SHA-256，校验走数据库。比 JWT 更可控（可即时吊销）。 */
object SessionTokens {
    fun issue(): Pair<String, String> {
        val raw = Bytes.random(32)
        val token = Bytes.toBase64(raw).replace('+', '-').replace('/', '_').trimEnd('=')
        return token to Bytes.toHex(com.enchat.crypto.Primitives.sha256(token.toByteArray()))
    }

    fun hashOf(token: String): String =
        Bytes.toHex(com.enchat.crypto.Primitives.sha256(token.toByteArray()))
}

/**
 * 极简滑动窗口限速器（单实例内存态）。
 *
 * 局限：只在单进程内有效；多实例部署必须换成 Redis 之类的共享计数器。
 */
class RateLimiter {
    private data class Bucket(val hits: ArrayDeque<Long> = ArrayDeque())

    private val buckets = ConcurrentHashMap<String, Bucket>()

    /** @return true 表示放行；false 表示命中限速 */
    fun allow(key: String, max: Int, windowMs: Long): Boolean {
        val now = System.currentTimeMillis()
        val bucket = buckets.computeIfAbsent(key) { Bucket() }
        synchronized(bucket) {
            while (bucket.hits.isNotEmpty() && now - bucket.hits.first() > windowMs) {
                bucket.hits.removeFirst()
            }
            return if (bucket.hits.size >= max) {
                false
            } else {
                bucket.hits.addLast(now)
                true
            }
        }
    }

    fun remaining(key: String, max: Int, windowMs: Long): Int {
        val now = System.currentTimeMillis()
        val bucket = buckets[key] ?: return max
        synchronized(bucket) {
            while (bucket.hits.isNotEmpty() && now - bucket.hits.first() > windowMs) {
                bucket.hits.removeFirst()
            }
            return (max - bucket.hits.size).coerceAtLeast(0)
        }
    }

    /** 仅供测试与运维：清空计数（例如登录成功后）。 */
    fun reset(key: String) {
        buckets.remove(key)
    }
}

/** 头像上传路径安全处理：只用随机文件名，禁止路径穿越。 */
object FileNames {
    fun randomFor(mime: String): String {
        val ext = when (mime.lowercase(Locale.ROOT)) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "bin"
        }
        return UUID.randomUUID().toString().replace("-", "") + "." + ext
    }
}
