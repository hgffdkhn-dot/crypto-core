package com.enchat.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.enchat.app.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "enchat_settings")

/**
 * 设置项（对标主流 IM 的设置页）。
 *
 * 只放"设置"本身；消息与会话缓存在 Room。
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val THEME_MODE = intPreferencesKey("theme_mode")          // 0 跟随系统 / 1 浅色 / 2 深色
        val NOTIFY_ENABLED = booleanPreferencesKey("notify_enabled")
        val NOTIFY_SOUND = booleanPreferencesKey("notify_sound")
        val NOTIFY_PREVIEW = booleanPreferencesKey("notify_preview")
        val READ_RECEIPT = booleanPreferencesKey("read_receipt")
        val FONT_SCALE = intPreferencesKey("font_scale")           // 100 = 标准
        val ENTER_TO_SEND = booleanPreferencesKey("enter_to_send")
        val AUTO_DOWNLOAD = booleanPreferencesKey("auto_download")
        val KEEP_DAYS = intPreferencesKey("keep_days")
        val E2EE_ENABLED_UIDS = stringSetPreferencesKey("e2ee_enabled_uids")
        val E2EE_INTRO_SEEN_UIDS = stringSetPreferencesKey("e2ee_intro_seen_uids")
        val LAST_SYNC_SEQ = longPreferencesKey("last_sync_seq")
        val SELF_UID = longPreferencesKey("self_uid")
        val SELF_USER_ID = longPreferencesKey("self_user_id")
        val NICKNAME = stringPreferencesKey("self_nickname")
        val AVATAR_URL = stringPreferencesKey("self_avatar_url")
        val SIGNATURE = stringPreferencesKey("self_signature")
    }

    val host: Flow<String> = read<String>(Keys.HOST, BuildConfig.DEFAULT_HOST)
    val themeMode: Flow<Int> = read<Int>(Keys.THEME_MODE, 0)
    val notifyEnabled: Flow<Boolean> = read<Boolean>(Keys.NOTIFY_ENABLED, true)
    val notifySound: Flow<Boolean> = read<Boolean>(Keys.NOTIFY_SOUND, true)
    val notifyPreview: Flow<Boolean> = read<Boolean>(Keys.NOTIFY_PREVIEW, true)
    val readReceipt: Flow<Boolean> = read<Boolean>(Keys.READ_RECEIPT, true)
    val fontScale: Flow<Int> = read<Int>(Keys.FONT_SCALE, 100)
    val enterToSend: Flow<Boolean> = read<Boolean>(Keys.ENTER_TO_SEND, false)
    val autoDownload: Flow<Boolean> = read<Boolean>(Keys.AUTO_DOWNLOAD, true)
    val keepDays: Flow<Int> = read<Int>(Keys.KEEP_DAYS, 30)
    val e2eeEnabledUids: Flow<Set<String>> = read<Set<String>>(Keys.E2EE_ENABLED_UIDS, emptySet<String>())
    val e2eeIntroSeenUids: Flow<Set<String>> = read<Set<String>>(Keys.E2EE_INTRO_SEEN_UIDS, emptySet<String>())
    val lastSyncSeq: Flow<Long> = read<Long>(Keys.LAST_SYNC_SEQ, 0L)
    val selfUid: Flow<Long> = read<Long>(Keys.SELF_UID, 0L)
    val nickname: Flow<String> = read<String>(Keys.NICKNAME, "")
    val avatarUrl: Flow<String> = read<String>(Keys.AVATAR_URL, "")
    val signature: Flow<String> = read<String>(Keys.SIGNATURE, "")

    suspend fun setHost(v: String) = write(Keys.HOST, v.trimEnd('/'))
    suspend fun setThemeMode(v: Int) = write<Int>(Keys.THEME_MODE, v)
    suspend fun setNotifyEnabled(v: Boolean) = write<Boolean>(Keys.NOTIFY_ENABLED, v)
    suspend fun setNotifySound(v: Boolean) = write<Boolean>(Keys.NOTIFY_SOUND, v)
    suspend fun setNotifyPreview(v: Boolean) = write<Boolean>(Keys.NOTIFY_PREVIEW, v)
    suspend fun setReadReceipt(v: Boolean) = write<Boolean>(Keys.READ_RECEIPT, v)
    suspend fun setFontScale(v: Int) = write<Int>(Keys.FONT_SCALE, v)
    suspend fun setEnterToSend(v: Boolean) = write<Boolean>(Keys.ENTER_TO_SEND, v)
    suspend fun setAutoDownload(v: Boolean) = write<Boolean>(Keys.AUTO_DOWNLOAD, v)
    suspend fun setKeepDays(v: Int) = write<Int>(Keys.KEEP_DAYS, v)
    suspend fun setLastSyncSeq(v: Long) = write<Long>(Keys.LAST_SYNC_SEQ, v)

    suspend fun setSelf(uid: Long, userId: Long, nickname: String, avatarUrl: String) {
        context.settingsDataStore.edit { p ->
            p[Keys.SELF_UID] = uid
            p[Keys.SELF_USER_ID] = userId
            p[Keys.NICKNAME] = nickname
            p[Keys.AVATAR_URL] = avatarUrl
        }
    }

    suspend fun setProfile(nickname: String, signature: String) {
        context.settingsDataStore.edit { p ->
            p[Keys.NICKNAME] = nickname
            p[Keys.SIGNATURE] = signature
        }
    }

    suspend fun setAvatarUrl(v: String) = write<String>(Keys.AVATAR_URL, v)

    suspend fun selfUserId(): Long = context.settingsDataStore.data.first()[Keys.SELF_USER_ID] ?: 0L

    /** 开启/关闭与某好友的高级加密（决定列表里是否多出那一条会话）。 */
    suspend fun setE2eeEnabled(uid: Long, enabled: Boolean) {
        context.settingsDataStore.edit { p ->
            val set = (p[Keys.E2EE_ENABLED_UIDS] ?: emptySet<String>()).toMutableSet()
            if (enabled) set.add(uid.toString()) else set.remove(uid.toString())
            p[Keys.E2EE_ENABLED_UIDS] = set
        }
    }

    suspend fun isE2eeEnabled(uid: Long): Boolean =
        (context.settingsDataStore.data.first()[Keys.E2EE_ENABLED_UIDS] ?: emptySet<String>()).contains(uid.toString())

    suspend fun markIntroSeen(uid: Long) {
        context.settingsDataStore.edit { p ->
            val set = (p[Keys.E2EE_INTRO_SEEN_UIDS] ?: emptySet<String>()).toMutableSet()
            set.add(uid.toString())
            p[Keys.E2EE_INTRO_SEEN_UIDS] = set
        }
    }

    suspend fun hasSeenIntro(uid: Long): Boolean =
        (context.settingsDataStore.data.first()[Keys.E2EE_INTRO_SEEN_UIDS] ?: emptySet<String>()).contains(uid.toString())

    suspend fun clearSession() {
        context.settingsDataStore.edit { p ->
            p.remove(Keys.SELF_UID)
            p.remove(Keys.SELF_USER_ID)
            p.remove(Keys.LAST_SYNC_SEQ)
        }
    }

    private fun <T> read(key: Preferences.Key<T>, default: T): Flow<T> =
        context.settingsDataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .map { it[key] ?: default }

    private suspend fun <T> write(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }
}
