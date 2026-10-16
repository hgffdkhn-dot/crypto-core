package com.enchat.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本地缓存库。
 *
 * 未使用 SQLCipher 加密整库 —— 这是已知风险（见 docs/04）。
 * 缓解措施：E2EE 会话在库里只存密文与链状态；退出登录会清空全部表（[clearAll]）。
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        FriendRequestEntity::class,
        E2eeSessionEntity::class
    ],
    version = 1,
    // 导出 schema 需要提供 room.schemaLocation 处理器参数，否则构建会失败/告警。
    // 本项目尚未到需要迁移脚本的阶段，直接关闭；进入正式迭代后再打开并纳入版本管理。
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao

    suspend fun clearAll() {
        with(chatDao()) {
            clearE2eeSessions()
            clearContacts()
        }
        clearAllTables()
    }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "enchat.db"
                )
                    .fallbackToDestructiveMigration() // 早期开发阶段；正式版应写 Migration
                    .build().also { instance = it }
            }
    }
}
