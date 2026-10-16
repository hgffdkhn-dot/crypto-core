package com.enchat.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ---------- 会话 ----------

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC, id DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE peerUid = :peerUid AND mode = :mode LIMIT 1")
    suspend fun conversation(peerUid: Long, mode: Int): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE peerUid = :peerUid ORDER BY mode")
    fun observeForPeer(peerUid: Long): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :localId LIMIT 1")
    suspend fun conversationByLocalId(localId: Long): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE serverConversationId = :serverId LIMIT 1")
    suspend fun conversationByServerId(serverId: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(c: ConversationEntity): Long

    @Update
    suspend fun updateConversation(c: ConversationEntity)

    @Query("UPDATE conversations SET unread = 0 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: Long)

    // ---------- 消息 ----------

    @Query("SELECT * FROM messages WHERE conversationLocalId = :convId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(convId: Long): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(m: MessageEntity): Long

    @Query("UPDATE messages SET state = :state WHERE clientMsgId = :clientMsgId")
    suspend fun updateStateByClientId(clientMsgId: String, state: Int)

    @Query("UPDATE messages SET serverMsgId = :serverMsgId, state = :state WHERE clientMsgId = :clientMsgId")
    suspend fun markSent(clientMsgId: String, serverMsgId: Long, state: Int)

    /** 断线重连用：避免同一条服务器消息重复插入本地。 */
    @Query("SELECT COUNT(*) FROM messages WHERE serverMsgId = :serverMsgId AND serverMsgId != 0")
    suspend fun countServerMsg(serverMsgId: Long): Int

    @Query("DELETE FROM messages WHERE conversationLocalId = :convId")
    suspend fun clearMessages(convId: Long)

    // ---------- 联系人 ----------

    @Query("SELECT * FROM contacts ORDER BY nickname")
    fun observeContacts(): Flow<List<ContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContact(c: ContactEntity)

    @Query("DELETE FROM contacts WHERE uid = :uid")
    suspend fun deleteContact(uid: Long)

    @Query("DELETE FROM contacts")
    suspend fun clearContacts()

    // ---------- 好友请求 ----------

    @Query("SELECT * FROM friend_requests WHERE state = 0 ORDER BY createdAt DESC")
    fun observePendingRequests(): Flow<List<FriendRequestEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRequest(r: FriendRequestEntity)

    @Query("DELETE FROM friend_requests WHERE requestId = :requestId")
    suspend fun deleteRequest(requestId: Long)

    // ---------- 端到端会话 ----------

    @Query("SELECT * FROM e2ee_sessions WHERE peerUid = :peerUid AND mode = :mode LIMIT 1")
    suspend fun e2eeSession(peerUid: Long, mode: Int): E2eeSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertE2eeSession(s: E2eeSessionEntity)

    @Query("DELETE FROM e2ee_sessions WHERE peerUid = :peerUid AND mode = :mode")
    suspend fun deleteE2eeSession(peerUid: Long, mode: Int)

    /** 清数据时一并抹掉：卸载/退出登录不应留下可恢复的链密钥。 */
    @Query("DELETE FROM e2ee_sessions")
    suspend fun clearE2eeSessions()
}
