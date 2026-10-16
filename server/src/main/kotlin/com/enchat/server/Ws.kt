package com.enchat.server

import com.enchat.crypto.TransportCrypto
import com.enchat.crypto.TransportSession
import com.enchat.protocol.ErrorCode
import com.enchat.protocol.FrameType
import com.enchat.protocol.MessageKind
import com.enchat.protocol.Protocol
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条 WebSocket 连接。
 *
 * 生命周期：
 * 1. 连接建立 → 客户端发 48B 的 clientHello（明文二进制，此时无加密通道可用）
 * 2. 服务端回 112B 的 serverHello（明文二进制）→ 双方得到 [TransportSession]
 * 3. 此后**只接受加密二进制帧**；收到 Text 帧一律断开，防止绕过加密层
 */
class Connection(val session: DefaultWebSocketServerSession) {
    @Volatile
    var transport: TransportSession? = null

    @Volatile
    var userId: Long? = null

    @Volatile
    var deviceId: String = ""

    /** 发送队列：写入侧协程串行 seal+send，规避并发写 WebSocket 的问题。 */
    val outbox: Channel<String> = Channel(Channel.UNLIMITED)
}

class WsHub(private val ctx: ServerContext) {

    /** userId -> 该用户的所有在线连接（多设备/多端）。 */
    private val byUser: ConcurrentHashMap<Long, MutableSet<Connection>> = ConcurrentHashMap()

    private val handshakeSlots = ConcurrentHashMap<Connection, Boolean>()

    fun Application.installWebSocket() {
        routing {
            webSocket(Protocol.WS_PATH) {
                val conn = Connection(this)
                register(conn)
                val writer = launch {
                    for (payload in conn.outbox) {
                        val t = conn.transport ?: continue
                        val sent = runCatching {
                            conn.session.send(Frame.Binary(true, t.seal(payload.toByteArray(Charsets.UTF_8))))
                        }.isSuccess
                        if (!sent) break
                    }
                }
                try {
                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Binary -> {
                                val ok = onBinary(conn, frame.data)
                                if (!ok) break
                            }
                            is Frame.Text -> {
                                // 传输层建立后不允许明文帧；建立前也不接受（握手必须是二进制）
                                conn.session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "明文帧被拒绝"))
                                break
                            }
                            else -> Unit
                        }
                    }
                } catch (t: Throwable) {
                    if (t !is CancellationException) {
                        runCatching {
                            conn.session.close(CloseReason(CloseReason.Codes.INTERNAL_ERROR, "连接异常"))
                        }
                    }
                } finally {
                    writer.cancel()
                    unregister(conn)
                    conn.transport?.zeroize()
                }
            }
        }
    }

    private fun register(conn: Connection) {
        handshakeSlots[conn] = true
    }

    private fun unregister(conn: Connection) {
        handshakeSlots.remove(conn)
        conn.userId?.let { uid ->
            byUser[uid]?.let { set ->
                set.remove(conn)
                if (set.isEmpty()) byUser.remove(uid, set)
            }
        }
    }

    /** @return false 表示应关闭连接 */
    private fun onBinary(conn: Connection, data: ByteArray): Boolean {
        val transport = conn.transport
        if (transport == null) {
            if (data.size != TransportCrypto.CLIENT_HELLO_SIZE) {
                // 握手格式错误：直接断开，且不回任何信息（避免给攻击者反馈）
                return false
            }
            return runCatching {
                val (serverHello, serverSession) = TransportCrypto.respondAsServer(data, ctx.serverKeys.identity)
                conn.transport = serverSession
                conn.session.launch {
                    runCatching { conn.session.send(Frame.Binary(true, serverHello)) }
                }
                true
            }.getOrDefault(false)
        }

        return runCatching {
            val payload = transport.open(data)
            handle(conn, JSONObject(String(payload, Charsets.UTF_8)))
            true
        }.getOrElse { t ->
            // 解密失败/序号错乱：断开重连（客户端会重新握手）
            System.err.println("[ws] frame error: ${t.message}")
            false
        }
    }

    // ---------------- 业务帧处理 ----------------

    private fun handle(conn: Connection, frame: JSONObject) {
        val typeCode = frame.optInt("t", -1)
        val rid = frame.optString("rid", null)
        val payload = frame.optJSONObject("payload") ?: JSONObject()
        val type = FrameType.fromCode(typeCode)

        try {
            when (type) {
                FrameType.AUTH -> handleAuth(conn, rid, payload)
                FrameType.PING -> reply(conn, FrameType.PONG, rid, JSONObject().put("ts", System.currentTimeMillis()))
                FrameType.SEND_MESSAGE -> handleSendMessage(conn, rid, payload)
                FrameType.SYNC_REQUEST -> handleSync(conn, rid, payload)
                FrameType.FRIEND_REQUEST_SEND -> handleFriendRequest(conn, rid, payload)
                FrameType.FRIEND_ACCEPT -> handleFriendAccept(conn, rid, payload)
                FrameType.FRIEND_REJECT -> handleFriendReject(conn, rid, payload)
                FrameType.FRIEND_REMOVE -> handleFriendRemove(conn, rid, payload)
                FrameType.FRIEND_LIST -> handleFriendList(conn, rid)
                FrameType.GROUP_CREATE -> handleGroupCreate(conn, rid, payload)
                FrameType.GROUP_INVITE -> handleGroupInvite(conn, rid, payload)
                FrameType.GROUP_LEAVE -> handleGroupLeave(conn, rid, payload)
                FrameType.GROUP_LIST -> handleGroupList(conn, rid)
                else -> error(conn, rid, ErrorCode.BAD_REQUEST.code, "不支持的帧类型: $typeCode")
            }
        } catch (e: ApiError) {
            error(conn, rid, e.code.code, e.message)
        } catch (t: Throwable) {
            // 业务处理异常不应静默断连：回一个明确的错误帧，便于客户端提示
            System.err.println("[ws] handler error: ${t.message}")
            error(conn, rid, ErrorCode.INTERNAL.code, "处理失败")
        }
    }

    private fun requireAuth(conn: Connection): Long =
        conn.userId ?: throw ApiError(ErrorCode.UNAUTHORIZED)

    private fun handleAuth(conn: Connection, rid: String?, payload: JSONObject) {
        val token = payload.optString("token")
        val version = payload.optInt("protocolVersion", 0)
        if (version != Protocol.VERSION) throw ApiError(ErrorCode.VERSION_TOO_OLD)
        val userId = ctx.repos.sessionUser(SessionTokens.hashOf(token)) ?: throw ApiError(ErrorCode.UNAUTHORIZED)
        conn.userId = userId
        conn.deviceId = payload.optString("deviceId")

        val set = byUser.computeIfAbsent(userId) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
        set.add(conn)

        val latestSeq = ctx.repos.latestSeqFor(userId)
        reply(conn, FrameType.AUTH_RESULT, rid, JSONObject()
            .put("ok", true)
            .put("uid", ctx.repos.userIdRow(userId)?.get("uid"))
            .put("serverTime", System.currentTimeMillis())
            .put("latestSeq", latestSeq))
    }

    private fun handleSendMessage(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val conversationId = payload.optLong("conversationId", -1L)
        val kind = payload.optInt("kind", MessageKind.TEXT.code)
        val clientMsgId = payload.optString("clientMsgId", "")

        if (conversationId <= 0) throw ApiError(ErrorCode.BAD_REQUEST)
        if (!ctx.repos.isMember(conversationId, userId)) throw ApiError(ErrorCode.FORBIDDEN)
        if (!ctx.limiter.allow("send:$userId", ctx.limits.sendPerSecondPerUser, 1000)) throw ApiError(ErrorCode.RATE_LIMITED)

        val conv = ctx.repos.conversation(conversationId) ?: throw ApiError(ErrorCode.NOT_FOUND)
        val mode = (conv["encryption_mode"] as Number).toInt()
        val isGroup = (conv["type"] as Number).toInt() == 2

        val e2ee = payload.optJSONObject("e2ee")
        val text = payload.optString("text").takeIf { it.isNotBlank() }
        val ciphertext = e2ee?.optString("ciphertext")?.takeIf { it.isNotBlank() }

        // 安全边界：端到端会话不允许降级发送明文（服务器只存密文）
        val storedPlain: String?
        val storedCipher: String?
        if (mode == 2) {
            if (ciphertext == null) throw ApiError(ErrorCode.E2EE_REQUIRED)
            if (text != null) throw ApiError(ErrorCode.E2EE_REQUIRED, "端到端会话不接受明文字段")
            storedPlain = null
            storedCipher = ciphertext
        } else {
            if (kind == MessageKind.E2EE_INIT.code || kind == MessageKind.E2EE_SENDER_KEY.code) {
                if (ciphertext == null) throw ApiError(ErrorCode.BAD_REQUEST)
                storedPlain = null
                storedCipher = ciphertext
            } else {
                if (text == null) throw ApiError(ErrorCode.BAD_REQUEST, "缺少消息内容")
                if (text.toByteArray(Charsets.UTF_8).size > Protocol.MAX_TEXT_BYTES) {
                    throw ApiError(ErrorCode.MESSAGE_TOO_LARGE)
                }
                storedPlain = text
                storedCipher = null
            }
        }

        val seq = ctx.repos.insertMessage(
            conversationId = conversationId,
            senderId = userId,
            kind = kind,
            plaintext = storedPlain,
            ciphertext = storedCipher,
            e2eeSessionId = e2ee?.optString("sessionId")?.takeIf { it.isNotBlank() },
            e2eeIndex = e2ee?.opt("chainIndex")?.let { (it as? Number)?.toInt() },
            groupEpoch = e2ee?.opt("epoch")?.let { (it as? Number)?.toInt() }
                ?: (conv["group_epoch"] as? Number)?.toInt()?.takeIf { isGroup }
        )

        reply(conn, FrameType.MESSAGE_ACK, rid, JSONObject()
            .put("ok", true)
            .put("serverMsgId", seq)
            .put("serverSeq", seq)
            .put("clientMsgId", clientMsgId)
            .put("createdAt", System.currentTimeMillis()))

        // 投递给会话内其他成员（群聊不含自己）
        val recipients = ctx.repos.members(conversationId).filter { it != userId }
        val deliver = JSONObject()
            .put("t", FrameType.MESSAGE_DELIVER.code)
            .put("payload", JSONObject()
                .put("conversationId", conversationId)
                .put("serverMsgId", seq)
                .put("serverSeq", seq)
                .put("senderId", userId)
                .put("kind", kind)
                .put("createdAt", System.currentTimeMillis())
                .put("clientMsgId", clientMsgId)
                .apply { if (storedPlain != null) put("text", storedPlain) }
                .apply {
                    if (storedCipher != null) {
                        put("e2ee", JSONObject()
                            .put("ciphertext", storedCipher)
                            .put("sessionId", e2ee?.optString("sessionId") ?: "")
                            .apply { e2ee?.opt("chainIndex")?.let { put("chainIndex", it) } }
                            .apply { e2ee?.opt("epoch")?.let { put("epoch", it) } })
                    }
                })
        recipients.forEach { deliverTo(it, deliver.toString()) }
    }

    private fun handleSync(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val afterSeq = payload.optLong("afterSeq", 0L)
        val limit = payload.optInt("limit", 200).coerceIn(1, 1000)
        val rows = ctx.repos.listMessagesAfter(userId, afterSeq, limit)
        val arr = org.json.JSONArray()
        rows.forEach { r ->
            arr.put(JSONObject()
                .put("serverMsgId", r.seq)
                .put("serverSeq", r.seq)
                .put("conversationId", r.conversationId)
                .put("senderId", r.senderId)
                .put("kind", r.kind)
                .put("createdAt", r.createdAt)
                .apply { r.plaintext?.let { put("text", it) } }
                .apply {
                    r.ciphertext?.let { ct ->
                        put("e2ee", JSONObject()
                            .put("ciphertext", ct)
                            .put("sessionId", r.e2eeSessionId ?: "")
                            .apply { r.e2eeIndex?.let { put("chainIndex", it) } }
                            .apply { r.groupEpoch?.let { put("epoch", it) } })
                    }
                })
        }
        reply(conn, FrameType.SYNC_RESPONSE, rid, JSONObject()
            .put("messages", arr)
            .put("hasMore", rows.size == limit)
            .put("latestSeq", rows.lastOrNull()?.seq ?: afterSeq))
    }

    private fun handleFriendRequest(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val toUid = payload.optLong("toUid", -1L)
        val toId = ctx.repos.findUserIdByUid(toUid) ?: throw ApiError(ErrorCode.INVALID_UID)
        if (toId == userId) throw ApiError(ErrorCode.BAD_REQUEST, "不能添加自己")
        if (!ctx.limiter.allow("friend-req:$userId", ctx.limits.friendRequestPerDayPerUser, 24 * 3600_000)) throw ApiError(ErrorCode.RATE_LIMITED)
        if (ctx.repos.areFriends(userId, toId)) throw ApiError(ErrorCode.ALREADY_FRIENDS)
        if (ctx.repos.pendingRequestBetween(userId, toId)) throw ApiError(ErrorCode.FRIEND_REQUEST_PENDING)

        val requestId = ctx.repos.createFriendRequest(userId, toId, payload.optString("message"))
        reply(conn, FrameType.FRIEND_REQUEST_EVENT, rid, JSONObject().put("ok", true).put("requestId", requestId))

        val me = ctx.repos.userIdRow(userId)?.let { ctx.repos.toUser(it) }
        deliverTo(toId, JSONObject()
            .put("t", FrameType.FRIEND_REQUEST_EVENT.code)
            .put("payload", JSONObject()
                .put("requestId", requestId)
                .put("fromUid", me?.uid ?: 0L)
                .put("fromNickname", me?.nickname ?: "")
                .put("fromAvatarUrl", me?.avatarUrl ?: "")
                .put("message", payload.optString("message"))
                .put("state", 0))
            .toString())
    }

    private fun handleFriendAccept(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val requestId = payload.optLong("requestId", -1L)
        val req = ctx.repos.friendRequest(requestId) ?: throw ApiError(ErrorCode.NOT_FOUND)
        val toUser = (req["to_user"] as Number).toLong()
        val fromUser = (req["from_user"] as Number).toLong()
        if (toUser != userId) throw ApiError(ErrorCode.FORBIDDEN)
        if ((req["state"] as Number).toInt() != 0) throw ApiError(ErrorCode.CONFLICT, "该请求已处理")

        ctx.repos.setFriendRequestState(requestId, 1)
        ctx.repos.addFriendship(fromUser, toUser)

        reply(conn, FrameType.FRIEND_ACCEPT, rid, JSONObject().put("ok", true).put("friendUid", ctx.repos.userIdRow(fromUser)?.get("uid")))

        // 通知发起方：请求被接受
        val me = ctx.repos.userIdRow(userId)?.let { ctx.repos.toUser(it) }
        deliverTo(fromUser, JSONObject()
            .put("t", FrameType.FRIEND_REQUEST_EVENT.code)
            .put("payload", JSONObject()
                .put("requestId", requestId)
                .put("fromUid", me?.uid ?: 0L)
                .put("fromNickname", me?.nickname ?: "")
                .put("state", 1))
            .toString())
    }

    private fun handleFriendReject(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val requestId = payload.optLong("requestId", -1L)
        val req = ctx.repos.friendRequest(requestId) ?: throw ApiError(ErrorCode.NOT_FOUND)
        if ((req["to_user"] as Number).toLong() != userId) throw ApiError(ErrorCode.FORBIDDEN)
        ctx.repos.setFriendRequestState(requestId, 2)
        reply(conn, FrameType.FRIEND_REJECT, rid, JSONObject().put("ok", true))
        deliverTo((req["from_user"] as Number).toLong(), JSONObject()
            .put("t", FrameType.FRIEND_REQUEST_EVENT.code)
            .put("payload", JSONObject().put("requestId", requestId).put("state", 2))
            .toString())
    }

    private fun handleFriendRemove(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val friendId = ctx.repos.findUserIdByUid(payload.optLong("friendUid", -1L)) ?: throw ApiError(ErrorCode.INVALID_UID)
        ctx.repos.removeFriendship(userId, friendId)
        reply(conn, FrameType.FRIEND_REMOVE, rid, JSONObject().put("ok", true))
        deliverTo(friendId, JSONObject()
            .put("t", FrameType.FRIEND_LIST.code)
            .put("payload", JSONObject().put("removedByUid", ctx.repos.userIdRow(userId)?.get("uid")))
            .toString())
    }

    private fun handleFriendList(conn: Connection, rid: String?) {
        val userId = requireAuth(conn)
        val friends = ctx.repos.listFriendIds(userId).mapNotNull { id ->
            ctx.repos.userIdRow(id)?.let { row ->
                val u = ctx.repos.toUser(row)
                JSONObject()
                    .put("uid", u.uid)
                    .put("uidDisplay", Protocol.formatUid(u.uid))
                    .put("nickname", u.nickname)
                    .put("avatarUrl", u.avatarUrl)
                    .put("signature", u.signature)
                    .put("online", isOnline(id))
            }
        }
        reply(conn, FrameType.FRIEND_LIST, rid, JSONObject().put("friends", org.json.JSONArray(friends)))
    }

    private fun handleGroupCreate(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        if (!ctx.limiter.allow("group:$userId", ctx.limits.groupCreatePerDayPerUser, 24 * 3600_000)) throw ApiError(ErrorCode.RATE_LIMITED)
        val uidList = payload.optJSONArray("memberUids")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { v -> v > 0 } }
        } ?: emptyList()
        val memberIds = (uidList.mapNotNull { ctx.repos.findUserIdByUid(it) } + userId).distinct()
        if (memberIds.size > Protocol.MAX_GROUP_MEMBERS) throw ApiError(ErrorCode.GROUP_FULL)

        val mode = payload.optInt("encryptionMode", 1).let { if (it == 2) 2 else 1 }
        // 群需要 16 字节 groupId（参与群消息的 AAD 派生），随机生成后随会话下发
        val groupId = com.enchat.crypto.Bytes.toBase64(com.enchat.crypto.Bytes.random(com.enchat.crypto.GroupCrypto.GROUP_ID_SIZE))
        val id = ctx.repos.createConversation(
            type = 2, mode = mode, title = payload.optString("title").take(100),
            ownerUid = ctx.repos.userIdRow(userId)?.get("uid")?.let { (it as Number).toLong() } ?: 0L,
            memberIds = memberIds,
            groupId = groupId
        )
        reply(conn, FrameType.GROUP_CREATE, rid, JSONObject()
            .put("ok", true)
            .put("conversationId", id)
            .put("groupId", groupId)
            .put("epoch", 1)
            .put("encryptionMode", mode))

        val members = ctx.repos.members(id)
        members.filter { it != userId }.forEach { m ->
            deliverTo(m, JSONObject()
                .put("t", FrameType.GROUP_MEMBER_CHANGE.code)
                .put("payload", JSONObject()
                    .put("conversationId", id)
                    .put("epoch", 1)
                    .put("title", payload.optString("title"))
                    .put("encryptionMode", mode)
                    .put("action", "added"))
                .toString())
        }
    }

    private fun handleGroupInvite(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val conversationId = payload.optLong("conversationId", -1L)
        val conv = ctx.repos.conversation(conversationId) ?: throw ApiError(ErrorCode.NOT_FOUND)
        if ((conv["type"] as Number).toInt() != 2) throw ApiError(ErrorCode.BAD_REQUEST, "不是群会话")
        if (!ctx.repos.isMember(conversationId, userId)) throw ApiError(ErrorCode.FORBIDDEN)

        val current = ctx.repos.members(conversationId)
        val uids = payload.optJSONArray("uids")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optLong(it).takeIf { v -> v > 0 } }
        } ?: emptyList()
        val ids = uids.mapNotNull { ctx.repos.findUserIdByUid(it) }.filter { it !in current }
        if (current.size + ids.size > Protocol.MAX_GROUP_MEMBERS) throw ApiError(ErrorCode.GROUP_FULL)
        if (ids.isEmpty()) {
            reply(conn, FrameType.GROUP_INVITE, rid, JSONObject().put("ok", true).put("epoch", (conv["group_epoch"] as Number).toInt()))
            return
        }

        // 成员变动 → epoch+1，客户端必须轮换 sender key，否则离/入群者可以解密后续消息
        val epoch = ctx.repos.addMembers(conversationId, ids, bumpEpoch = true)
        reply(conn, FrameType.GROUP_INVITE, rid, JSONObject().put("ok", true).put("epoch", epoch))

        val notification = JSONObject()
            .put("t", FrameType.GROUP_MEMBER_CHANGE.code)
            .put("payload", JSONObject()
                .put("conversationId", conversationId)
                .put("epoch", epoch)
                .put("action", "invited")
                .put("uids", org.json.JSONArray(uids)))
            .toString()
        (current + ids).filter { it != userId }.forEach { deliverTo(it, notification) }
    }

    private fun handleGroupLeave(conn: Connection, rid: String?, payload: JSONObject) {
        val userId = requireAuth(conn)
        val conversationId = payload.optLong("conversationId", -1L)
        if (!ctx.repos.isMember(conversationId, userId)) throw ApiError(ErrorCode.FORBIDDEN)
        val epoch = ctx.repos.removeMember(conversationId, userId)
        reply(conn, FrameType.GROUP_LEAVE, rid, JSONObject().put("ok", true).put("epoch", epoch))

        val notification = JSONObject()
            .put("t", FrameType.GROUP_MEMBER_CHANGE.code)
            .put("payload", JSONObject()
                .put("conversationId", conversationId)
                .put("epoch", epoch)
                .put("action", "left")
                .put("uid", ctx.repos.userIdRow(userId)?.get("uid")))
            .toString()
        ctx.repos.members(conversationId).forEach { deliverTo(it, notification) }
    }

    private fun handleGroupList(conn: Connection, rid: String?) {
        val userId = requireAuth(conn)
        val arr = org.json.JSONArray()
        ctx.repos.listConversationIds(userId).forEach { cid ->
            val conv = ctx.repos.conversation(cid) ?: return@forEach
            arr.put(JSONObject()
                .put("conversationId", cid)
                .put("type", (conv["type"] as Number).toInt())
                .put("encryptionMode", (conv["encryption_mode"] as Number).toInt())
                .put("title", conv["title"] as? String ?: "")
                .put("avatarUrl", conv["avatar_url"] as? String ?: "")
                .put("epoch", (conv["group_epoch"] as Number).toInt())
                .put("memberCount", ctx.repos.members(cid).size))
        }
        reply(conn, FrameType.GROUP_LIST, rid, JSONObject().put("conversations", arr))
    }

    // ---------------- 输出 ----------------

    private fun reply(conn: Connection, type: FrameType, rid: String?, payload: JSONObject) {
        val frame = JSONObject().put("t", type.code).put("ok", true)
        if (rid != null) frame.put("rid", rid)
        frame.put("payload", payload)
        conn.outbox.trySend(frame.toString())
    }

    private fun error(conn: Connection, rid: String?, code: Int, message: String) {
        val frame = JSONObject().put("t", FrameType.ERROR.code).put("ok", false)
            .put("err", code).put("msg", message)
        if (rid != null) frame.put("rid", rid)
        conn.outbox.trySend(frame.toString())
    }

    private fun deliverTo(userId: Long, json: String) {
        byUser[userId]?.forEach { conn ->
            if (conn.transport != null && !conn.outbox.isClosedForSend) {
                conn.outbox.trySend(json)
            }
        }
    }

    fun isOnline(userId: Long): Boolean = byUser[userId]?.isNotEmpty() == true

    fun onlineCount(): Int = byUser.values.sumOf { it.size }
}
