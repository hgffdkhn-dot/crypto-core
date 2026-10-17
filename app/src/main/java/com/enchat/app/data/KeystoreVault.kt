package com.enchat.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.enchat.crypto.Bytes
import com.enchat.crypto.Primitives
import java.security.GeneralSecurityException
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

    /**
     * 包装密钥缓存。
     *
     * ⚠️ 原来是 `by lazy`，一旦初始化就永久缓存。而 wipe() 里做了
     * `keyStore.deleteEntry(ALIAS)` —— 缓存的 SecretKey 会变成"指向已删除条目"的悬空引用，
     * 后续任何 seal/open 都会抛异常。更糟的是进程可能不重启（Application 存活），
     * 这个坏状态会一直持续，于是"重新打开 App 就闪退，只能卸载重装"。
     *
     * 改成可变字段，wipe 时可显式失效，让下次访问走 Keystore 重新获取/生成。
     */
    @Volatile
    private var cachedKey: SecretKey? = null

    private fun getOrCreateKey(): SecretKey {
        cachedKey?.let { return it }

        val existing = keyStore.getKey(ALIAS, null) as? SecretKey
        if (existing != null) {
            cachedKey = existing
            return existing
        }

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
        return generator.generateKey().also { cachedKey = it }
    }

    /**
     * 初始化 Cipher；若密钥已失效则丢弃缓存、重建后重试一次。
     *
     * ⚠️ 重试范围**只覆盖 init 阶段**，这是刻意的：
     * 密钥失效一定在 `cipher.init()` 暴露（InvalidKeyException 等）；
     * 而数据损坏/格式错误发生在 `doFinal()` 或参数校验阶段。
     * 若笼统地把整段加解密包进 try 里重试，一次"密文格式不对"就会误判成密钥失效，
     * 进而删掉一把**完全正常**的 Keystore 密钥 —— 那是不可逆的破坏，比崩溃更糟。
     */
    private fun initCipher(mode: Int, gcmParam: GCMParameterSpec?): Cipher {
        fun fresh(key: SecretKey): Cipher {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            if (gcmParam == null) cipher.init(mode, key) else cipher.init(mode, key, gcmParam)
            return cipher
        }
        return try {
            fresh(getOrCreateKey())
        } catch (e: GeneralSecurityException) {
            // 密钥被删除 / 设备凭据变更导致失效：缓存的 SecretKey 不会自己察觉，
            // 这里显式丢弃并重建。仍失败就让第二次的异常正常抛出。
            cachedKey = null
            runCatching { keyStore.deleteEntry(ALIAS) }
            fresh(getOrCreateKey())
        }
    }

    /** 加密字节序列，返回 base64( iv(12) || ciphertext || tag(16) )。 */
    fun seal(plaintext: ByteArray): String {
        val cipher = initCipher(Cipher.ENCRYPT_MODE, null)
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
        val cipher = initCipher(Cipher.DECRYPT_MODE, GCMParameterSpec(128, iv))
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

    /**
     * 退出登录/重置：清掉所有本地凭据。
     *
     * ⚠️ 这里**刻意不删除** Keystore 里的 ALIAS 条目，只清 SharedPreferences，理由：
     *
     * 1. 该 key 只是一把"包装密钥"，本身不含任何用户数据。用户数据都在被它加密的
     *    密文里（prefs 与 Room），密文清空即等于数据清空，删 key 没有额外安全收益。
     * 2. 删 key 反而会制造"缓存悬空"的状态不一致 —— 正是原来重装才能恢复的原因。
     * 3. 保留该 key 不影响换账号：新账号会重新生成身份密钥并用它重新封装。
     */
    fun wipe() {
        prefs.edit().clear().apply()
        cachedKey = null
    }

    /**
     * 启动自检：验证包装密钥确实可用；不可用则清空本地凭据。
     *
     * @return true 表示可用，false 表示已重置（调用方应引导用户重新登录）。
     *
     * 用途：旧版本退出登录会留下"Keystore 条目已删、但缓存仍指向它"的坏状态。
     * 用户装上新版本后，若不做处理仍会反复崩溃，只能卸载重装 —— 体验很差。
     * 这里让 App 自愈到"需要重新登录"的状态：本地密文已无意义，清掉即可，
     * 重新登录会生成全新的身份密钥。
     */
    fun recoverIfBroken(): Boolean {
        return try {
            val probe = ByteArray(16)
            val roundTrip = open(seal(probe))
            roundTrip.size == 16
        } catch (_: Exception) {
            wipe()
            false
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "enchat_local_vault_v1"
        private const val PREFS_NAME = "enchat_vault"

        /** 派生一个稳定的本地完整性校验值（仅用于自检，不承担安全职责）。 */
        fun fingerprint(data: ByteArray): String = Bytes.toHex(Primitives.sha256(data)).take(16)
    }
}
