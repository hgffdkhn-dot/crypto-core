package com.enchat.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地会话表。
 *
 * 注意：本表**不加密**（Room + 普通 SQLite）。这是已知取舍，见 docs/04-客户端实现.md：
 * - 标准（传输层加密）会话的消息明文存本地，与"服务器存明文"的设定一致；
 * - 端到端会话在本表只存**密文**，明文只在内存与 UI 中存在；
 * - 卸载 App 会连同本库一起删除 —— 这正是弹窗里"卸载后聊天将永远不复存在"的实现基础。
 *
 * 若设备已 root 或备份被导出，本地明文仍有泄露风险；后续可用 SQLCipher 加密整库。
 */
@Entity(
    tableName = "conversations",
    indices = [Index(value = ["peerUid", "mode"], unique = true)]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverConversationId: Long = 0,
    val peerUid: Long,
    val mode: Int,               // Protocol.ENCRYPTION_MODE_STANDARD / _E2EE
    val title: String,
    val avatarUrl: String,
    val lastMessageAt: Long = 0,
    val lastPreview: String = "",
    val unread: Int = 0,
    val groupId: String = "",    // 群聊的 16 字节 groupId（base64）；单聊为空
    val groupEpoch: Int = 1,
    val isGroup: Boolean = false
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationLocalId"), Index("serverMsgId")]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationLocalId: Long,
    val serverMsgId: Long = 0,
    val senderUid: Long,
    val outgoing: Boolean,
    val kind: Int,
    /** 标准模式：UTF-8 明文；端到端模式：null（只有 [ciphertextB64]） */
    val text: String?,
    /** 端到端模式：base64 密文；标准模式：null */
    val ciphertextB64: String?,
    val createdAt: Long,
    val state: Int = MessageState.SENT,   // 见 MessageState
    val clientMsgId: String = ""
)

object MessageState {
    const val SENDING = 0
    const val SENT = 1
    const val FAILED = 2
    const val RECEIVED = 3
}

@Entity(tableName = "contacts", indices = [Index("uid")])
data class ContactEntity(
    @PrimaryKey val uid: Long,
    val nickname: String,
    val username: String = "",
    val avatarUrl: String = "",
    val signature: String = "",
    val online: Boolean = false
)

@Entity(tableName = "friend_requests")
data class FriendRequestEntity(
    @PrimaryKey val requestId: Long,
    val fromUid: Long,
    val fromNickname: String,
    val fromAvatarUrl: String = "",
    val message: String = "",
    val state: Int = 0,      // 0 待处理 / 1 已接受 / 2 已拒绝
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 端到端会话状态：存 [E2eeSession.serialize] 的输出（已用 Keystore 密钥加密后的 base64）。
 * 不存就丢了会话 → 卸载/清数据后确实无法恢复历史消息。
 */
@Entity(tableName = "e2ee_sessions")
data class E2eeSessionEntity(
    @PrimaryKey val peerUid: Long,
    val mode: Int,
    val stateB64: String,
    val sessionIdB64: String,
    val updatedAt: Long = System.currentTimeMillis()
)
