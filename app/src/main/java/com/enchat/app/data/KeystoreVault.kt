package com.enchat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.enchat.crypto.Bytes
import com.enchat.crypto.Primitives
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地密钥保护：用 Android Keystore 里的 AES-256-GCM 密钥，加密落在磁盘上的身份密钥与会话状态。
 *
 * 为什么不能直接把原始密钥塞进 Keystore：
 * Android Keystore 只托管**非对称**密钥与对称 AES 密钥的原始材料，无法用它直接执行 X25519/Ed25519；
 * 而且 Keystore 里塞不下我们自己生成的 X25519 私钥。所以做法是"Keystore 保管一把包装密钥，
 * 用它加密我们自己的密钥包"，再把密文落库/落 DataStore。
 *
 * 局限（诚实说明）：
 * - 有 root 或内存中被注入时仍可被窃取；Keystore 提供的是"离线取走存储芯片也读不出"的保障。
 * - 若设备支持 StrongBox 可进一步提高，这里未强制要求（部分机型没有）。
 */
class KeystoreVault(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val wrappingKey: SecretKey by lazy { getOrCreateKey() }

    private fun getOrCreateKey(): SecretKey {
        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true) // 强制每次随机 IV
                .build()
        )
        return generator.generateKey()
    }

    /** 加密字节序列，返回 base64( iv(12) || ciphertext || tag(16) )。 */
    fun seal(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        val iv = cipher.iv
        val ct = cipher.doFinal(plaintext)
        Bytes.zeroize(plaintext)
        return Bytes.toBase64(Bytes.concat(iv, ct))
    }

    /** 解密 [seal] 的输出；格式不对或 key 不匹配时抛异常（调用方决定降级策略）。 */
    fun open(blobB64: String): ByteArray {
        val raw = Bytes.fromBase64(blobB64)
        require(raw.size > 12 + 16) { "密封数据过短" }
        val iv = raw.copyOfRange(0, 12)
        val ct = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    // ---------------- 简单键值（令牌等） ----------------

    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun getString(key: String): String? = prefs.getString(key, null)

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** 退出登录/重置：清掉所有本地凭据。 */
    fun wipe() {
        prefs.edit().clear().apply()
        runCatching { keyStore.deleteEntry(ALIAS) }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "enchat_local_vault_v1"
        private const val PREFS_NAME = "enchat_vault"

        /** 派生一个稳定的本地完整性校验值（仅用于自检，不承担安全职责）。 */
        fun fingerprint(data: ByteArray): String = Bytes.toHex(Primitives.sha256(data)).take(16)
    }
}
