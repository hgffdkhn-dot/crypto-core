package com.enchat.server

import com.enchat.protocol.MessageKind
import javax.sql.DataSource

/**
 * 数据访问层。全部使用 JDBC 直写 SQL（不引入 ORM，减少依赖与"魔法"）。
 *
 * 并发说明：涉及多表一致性（好友双向插入、OPK 消费）的地方显式走 [Db.inTx]；
 * uid 分配用 `MAX(uid)+1` 并在唯一约束冲突时重试 —— 这是为可移植性做的妥协，
 * 生产环境建议改用数据库序列（见 docs/03-服务端实现.md 的"已知妥协"）。
 */
class Repos(private val ds: DataSource) {

    private val now get() = System.currentTimeMillis()

    // ---------------- 用户 ----------------

    data class User(
        val id: Long,
        val uid: Long,
        val username: String,
        val nickname: String,
        val avatarUrl: String,
        val signature: String,
        val identityEd: String,
        val identityX: String
    )

    fun createUser(username: String, passwordHash: String, nickname: String): Long {
        repeat(5) {
            try {
                return ds.connection.use { c ->
                    val nextUid = (c.findLong("SELECT COALESCE(MAX(uid), 100000000) + 1 FROM users") ?: 100000001L)
                    c.insert(
                        "INSERT INTO users (uid, username, password_hash, nickname, created_at) VALUES (?,?,?,?,?)",
                        nextUid, username, passwordHash, nickname, now
                    )
                    nextUid
                }
            } catch (t: Throwable) {
                val msg = t.message.orEmpty()
                if (!msg.contains("unique", ignoreCase = true) && !msg.contains("duplicate", ignoreCase = true)) throw t
                Thread.sleep(20)
            }
        }
        throw IllegalStateException("无法分配 UID（唯一约束持续冲突）")
    }

    fun findByUsername(username: String): Map<String, Any?>? =
        ds.connection.use { it.findOne("SELECT * FROM users WHERE username = ?", username) }

    fun findByUid(uid: Long): Map<String, Any?>? =
        ds.connection.use { it.findOne("SELECT * FROM users WHERE uid = ?", uid) }

    fun findUserIdByUid(uid: Long): Long? =
        ds.connection.use { it.findLong("SELECT id FROM users WHERE uid = ?", uid) }

    fun userIdRow(userId: Long): Map<String, Any?>? =
        ds.connection.use { it.findOne("SELECT * FROM users WHERE id = ?", userId) }

    /** 通用写操作（供 HTTP 层做简单的 UPDATE）。 */
    fun execRaw(sql: String, vararg args: Any?): Int =
        ds.connection.use { it.exec(sql, *args) }

    fun updateProfile(userId: Long, nickname: String?, signature: String?, avatarUrl: String?) {
        ds.connection.use { c ->
            val sets = mutableListOf<String>()
            val args = mutableListOf<Any?>()
            nickname?.let { sets += "nickname = ?"; args += it }
            signature?.let { sets += "signature = ?"; args += it }
            avatarUrl?.let { sets += "avatar_url = ?"; args += it }
            if (sets.isEmpty()) return
            args += userId
            c.exec("UPDATE users SET ${sets.joinToString(", ")} WHERE id = ?", *args.toTypedArray())
        }
    }

    fun User.toJson(): org.json.JSONObject = org.json.JSONObject()
        .put("userId", id)
        .put("uid", uid)
        .put("uidDisplay", com.enchat.protocol.Protocol.formatUid(uid))
        .put("username", username)
        .put("nickname", nickname)
        .put("avatarUrl", avatarUrl)
        .put("signature", signature)

    fun toUser(row: Map<String, Any?>): User = User(
        id = (row["id"] as Number).toLong(),
        uid = (row["uid"] as Number).toLong(),
        username = row["username"] as String,
        nickname = (row["nickname"] as? String) ?: "",
        avatarUrl = (row["avatar_url"] as? String) ?: "",
        signature = (row["signature"] as? String) ?: "",
        identityEd = (row["identity_ed"] as? String) ?: "",
        identityX = (row["identity_x"] as? String) ?: ""
    )

    // ---------------- 会话令牌 ----------------

    fun createSession(tokenHash: String, userId: Long, deviceId: String, ttlMs: Long) {
        ds.connection.use {
            it.exec(
                "INSERT INTO sessions (token_hash, user_id, device_id, created_at, expires_at) VALUES (?,?,?,?,?)",
                tokenHash, userId, deviceId, now, now + ttlMs
            )
        }
    }

    fun sessionUser(tokenHash: String): Long? =
        ds.connection.use {
            it.findLong("SELECT user_id FROM sessions WHERE token_hash = ? AND expires_at > ?", tokenHash, now)
        }

    fun deleteSession(tokenHash: String) {
        ds.connection.use { it.exec("DELETE FROM sessions WHERE token_hash = ?", tokenHash) }
    }

    fun purgeExpiredSessions() {
        ds.connection.use { it.exec("DELETE FROM sessions WHERE expires_at <= ?", now) }
    }

    // ---------------- 预密钥 ----------------

    fun uploadIdentity(userId: Long, identityEd: String, identityX: String) {
        ds.connection.use {
            it.exec("UPDATE users SET identity_ed = ?, identity_x = ? WHERE id = ?", identityEd, identityX, userId)
        }
    }

    fun rotateSignedPreKey(userId: Long, spkId: Int, publicKey: String, signature: String) {
        Db.inTx(ds) { c ->
            c.exec("UPDATE signed_prekeys SET active = FALSE WHERE user_id = ?", userId)
            c.exec(
                "INSERT INTO signed_prekeys (user_id, spk_id, public_key, signature, active, created_at) VALUES (?,?,?,?,TRUE,?)",
                userId, spkId, publicKey, signature, now
            )
        }
    }

    fun addOneTimePreKeys(userId: Long, keys: List<Pair<Int, String>>) {
        ds.connection.use { c ->
            keys.forEach { (id, pub) ->
                // 已存在则忽略（客户端重复补货时不应报错）
                runCatching {
                    c.exec(
                        "INSERT INTO one_time_prekeys (user_id, opk_id, public_key, consumed, created_at) VALUES (?,?,?,FALSE,?)",
                        userId, id, pub, now
                    )
                }
            }
        }
    }

    fun activeSignedPreKey(userId: Long): Map<String, Any?>? =
        ds.connection.use {
            it.findOne(
                "SELECT * FROM signed_prekeys WHERE user_id = ? AND active = TRUE ORDER BY created_at DESC LIMIT 1",
                userId
            )
        }

    /** 原子地取出并消费一个 OPK；没有可用 OPK 时返回 null（协议允许不带 OPK 建立会话）。 */
    fun consumeOneTimePreKey(userId: Long): Map<String, Any?>? = Db.inTx(ds) { c ->
        val row = c.findOne(
            "SELECT * FROM one_time_prekeys WHERE user_id = ? AND consumed = FALSE ORDER BY id LIMIT 1",
            userId
        ) ?: return@inTx null
        val id = (row["id"] as Number).toLong()
        val updated = c.exec("UPDATE one_time_prekeys SET consumed = TRUE WHERE id = ? AND consumed = FALSE", id)
        if (updated == 1) row else null
    }

    fun remainingOneTimePreKeys(userId: Long): Long =
        ds.connection.use {
            it.findLong("SELECT COUNT(*) FROM one_time_prekeys WHERE user_id = ? AND consumed = FALSE", userId) ?: 0L
        }

    // ---------------- 好友 ----------------

    fun createFriendRequest(fromUserId: Long, toUserId: Long, message: String): Long =
        ds.connection.use {
            it.insert(
                "INSERT INTO friend_requests (from_user, to_user, message, state, created_at) VALUES (?,?,?,0,?)",
                fromUserId, toUserId, message.take(200), now
            )
        }

    fun friendRequest(id: Long): Map<String, Any?>? =
        ds.connection.use { it.findOne("SELECT * FROM friend_requests WHERE id = ?", id) }

    fun setFriendRequestState(id: Long, state: Int) {
        ds.connection.use {
            it.exec("UPDATE friend_requests SET state = ?, handled_at = ? WHERE id = ?", state, now, id)
        }
    }

    fun pendingRequestBetween(a: Long, b: Long): Boolean =
        ds.connection.use {
            (it.findLong(
                "SELECT COUNT(*) FROM friend_requests WHERE from_user = ? AND to_user = ? AND state = 0",
                a, b
            ) ?: 0L) > 0
        }

    fun areFriends(a: Long, b: Long): Boolean =
        ds.connection.use {
            (it.findLong("SELECT COUNT(*) FROM friendships WHERE user_id = ? AND friend_id = ?", a, b) ?: 0L) > 0
        }

    fun addFriendship(a: Long, b: Long) {
        Db.inTx(ds) { c ->
            runCatching {
                c.exec("INSERT INTO friendships (user_id, friend_id, created_at) VALUES (?,?,?)", a, b, now)
            }
            runCatching {
                c.exec("INSERT INTO friendships (user_id, friend_id, created_at) VALUES (?,?,?)", b, a, now)
            }
        }
    }

    fun removeFriendship(a: Long, b: Long) {
        Db.inTx(ds) { c ->
            c.exec("DELETE FROM friendships WHERE user_id = ? AND friend_id = ?", a, b)
            c.exec("DELETE FROM friendships WHERE user_id = ? AND friend_id = ?", b, a)
        }
    }

    fun listFriendIds(userId: Long): List<Long> {
        val out = mutableListOf<Long>()
        ds.connection.use { c ->
            c.query("SELECT friend_id FROM friendships WHERE user_id = ?", userId) { rs ->
                out += rs.getLong(1)
            }
        }
        return out
    }

    fun listIncomingRequests(userId: Long): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        ds.connection.use { c ->
            c.query("SELECT * FROM friend_requests WHERE to_user = ? AND state = 0 ORDER BY id", userId) { rs ->
                val meta = rs.metaData
                val map = linkedMapOf<String, Any?>()
                for (i in 1..meta.columnCount) map[meta.getColumnLabel(i).lowercase()] = rs.getObject(i)
                out += map
            }
        }
        return out
    }

    // ---------------- 会话 ----------------

    fun createConversation(
        type: Int,
        mode: Int,
        title: String,
        ownerUid: Long,
        memberIds: List<Long>,
        groupId: String = ""
    ): Long = Db.inTx(ds) { c ->
        val id = c.insert(
            "INSERT INTO conversations (type, encryption_mode, owner_uid, group_id, title, group_epoch, created_at) VALUES (?,?,?,?,?,1,?)",
            type, mode, ownerUid, groupId, title.take(100), now
        )
        memberIds.distinct().forEach { uid ->
            runCatching {
                c.exec(
                    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?,?,?,?)",
                    id, uid, if (uid == memberIds.firstOrNull()) 1 else 0, now
                )
            }
        }
        id
    }

    /** 查找（或创建）两个用户之间的单聊会话；每种加密模式各一条。 */
    fun findOrCreateDirect(a: Long, b: Long, mode: Int): Long {
        val existing = ds.connection.use { c ->
            c.findLong(
                """SELECT cm1.conversation_id FROM conversation_members cm1
                   JOIN conversation_members cm2 ON cm1.conversation_id = cm2.conversation_id
                   JOIN conversations cv ON cv.id = cm1.conversation_id
                   WHERE cm1.user_id = ? AND cm2.user_id = ? AND cv.type = 1 AND cv.encryption_mode = ?""",
                a, b, mode
            )
        }
        if (existing != null) return existing
        return createConversation(type = 1, mode = mode, title = "", ownerUid = 0, memberIds = listOf(a, b))
    }

    fun conversation(id: Long): Map<String, Any?>? =
        ds.connection.use { it.findOne("SELECT * FROM conversations WHERE id = ?", id) }

    fun isMember(conversationId: Long, userId: Long): Boolean =
        ds.connection.use {
            (it.findLong(
                "SELECT COUNT(*) FROM conversation_members WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
                conversationId, userId
            ) ?: 0L) > 0
        }

    fun members(conversationId: Long): List<Long> {
        val out = mutableListOf<Long>()
        ds.connection.use { c ->
            c.query(
                "SELECT user_id FROM conversation_members WHERE conversation_id = ? AND left_at IS NULL",
                conversationId
            ) { rs -> out += rs.getLong(1) }
        }
        return out
    }

    fun addMembers(conversationId: Long, userIds: List<Long>, bumpEpoch: Boolean): Int {
        var epoch = 0
        Db.inTx(ds) { c ->
            userIds.distinct().forEach { uid ->
                runCatching {
                    c.exec(
                        "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?,?,0,?)",
                        conversationId, uid, now
                    )
                }
            }
            if (bumpEpoch) {
                c.exec("UPDATE conversations SET group_epoch = group_epoch + 1 WHERE id = ?", conversationId)
            }
            epoch = (c.findLong("SELECT group_epoch FROM conversations WHERE id = ?", conversationId) ?: 1).toInt()
        }
        return epoch
    }

    fun removeMember(conversationId: Long, userId: Long): Int {
        var epoch = 0
        Db.inTx(ds) { c ->
            c.exec("UPDATE conversation_members SET left_at = ? WHERE conversation_id = ? AND user_id = ?", now, conversationId, userId)
            c.exec("UPDATE conversations SET group_epoch = group_epoch + 1 WHERE id = ?", conversationId)
            epoch = (c.findLong("SELECT group_epoch FROM conversations WHERE id = ?", conversationId) ?: 1).toInt()
        }
        return epoch
    }

    fun listConversationIds(userId: Long): List<Long> {
        val out = mutableListOf<Long>()
        ds.connection.use { c ->
            c.query(
                "SELECT conversation_id FROM conversation_members WHERE user_id = ? AND left_at IS NULL ORDER BY conversation_id",
                userId
            ) { rs -> out += rs.getLong(1) }
        }
        return out
    }

    // ---------------- 消息 ----------------

    fun insertMessage(
        conversationId: Long,
        senderId: Long,
        kind: Int,
        plaintext: String?,
        ciphertext: String?,
        e2eeSessionId: String?,
        e2eeIndex: Int?,
        groupEpoch: Int?
    ): Long =
        ds.connection.use {
            it.insert(
                """INSERT INTO messages (conversation_id, sender_id, kind, plaintext, ciphertext,
                     e2ee_session_id, e2ee_index, group_epoch, created_at) VALUES (?,?,?,?,?,?,?,?,?)""".trimIndent(),
                conversationId, senderId, kind, plaintext, ciphertext, e2eeSessionId, e2eeIndex, groupEpoch, now
            )
        }

    data class MessageRow(
        val seq: Long,
        val conversationId: Long,
        val senderId: Long,
        val kind: Int,
        val plaintext: String?,
        val ciphertext: String?,
        val e2eeSessionId: String?,
        val e2eeIndex: Int?,
        val groupEpoch: Int?,
        val createdAt: Long
    )

    /** 增量同步：拉取用户参与会话中 seq > afterSeq 的消息。 */
    fun listMessagesAfter(userId: Long, afterSeq: Long, limit: Int): List<MessageRow> {
        val out = mutableListOf<MessageRow>()
        ds.connection.use { c ->
            c.query(
                """SELECT m.* FROM messages m
                   JOIN conversation_members cm ON cm.conversation_id = m.conversation_id
                   WHERE cm.user_id = ? AND cm.left_at IS NULL AND m.id > ?
                   ORDER BY m.id LIMIT ?""".trimIndent(),
                userId, afterSeq, limit
            ) { rs ->
                out += MessageRow(
                    seq = rs.getLong("id"),
                    conversationId = rs.getLong("conversation_id"),
                    senderId = rs.getLong("sender_id"),
                    kind = rs.getInt("kind"),
                    plaintext = rs.getString("plaintext"),
                    ciphertext = rs.getString("ciphertext"),
                    e2eeSessionId = rs.getString("e2ee_session_id"),
                    e2eeIndex = rs.getObject("e2ee_index")?.let { (it as Number).toInt() },
                    groupEpoch = rs.getObject("group_epoch")?.let { (it as Number).toInt() },
                    createdAt = rs.getLong("created_at")
                )
            }
        }
        return out
    }

    /** 用户可见的最新消息序号（供断线重连增量同步）。 */
    fun latestSeqFor(userId: Long): Long =
        ds.connection.use {
            it.findLong(
                """SELECT COALESCE(MAX(m.id), 0) FROM messages m
                   JOIN conversation_members cm ON cm.conversation_id = m.conversation_id
                   WHERE cm.user_id = ?""".trimIndent(),
                userId
            )
        } ?: 0L

    /** 清理过期 E2EE 密文（未拉取即删除；标准模式明文按保留策略另算）。 */
    fun purgeExpiredE2ee(retentionDays: Int): Int {
        val cutoff = now - retentionDays.toLong() * 24 * 3600 * 1000
        return ds.connection.use {
            it.exec("DELETE FROM messages WHERE ciphertext IS NOT NULL AND created_at < ?", cutoff)
        }
    }

    /** 判断该 kind 是否需要计入"用户可见消息"（用于未读数等，当前仅做过滤）。 */
    fun isVisibleMessage(kind: Int): Boolean =
        kind != MessageKind.E2EE_INIT.code && kind != MessageKind.E2EE_SENDER_KEY.code
}
