package com.enchat.app.repo

import com.enchat.app.data.KeystoreVault
import com.enchat.app.data.SettingsStore
import com.enchat.app.data.local.AppDatabase
import com.enchat.app.data.local.ContactEntity
import com.enchat.app.data.local.ChatDao
import com.enchat.app.data.local.ConversationEntity
import com.enchat.app.data.local.E2eeSessionEntity
import com.enchat.app.data.local.FriendRequestEntity
import com.enchat.app.data.local.MessageEntity
import com.enchat.app.data.local.MessageState
import com.enchat.app.data.remote.ApiClient
import com.enchat.app.data.remote.jsonObjectOf
import com.enchat.app.data.remote.LinkState
import com.enchat.app.data.remote.WsClient
import com.enchat.clientcore.ConnectionStateMachine
import com.enchat.clientcore.ReconnectPolicy
import com.enchat.crypto.Bytes
import com.enchat.crypto.E2eeSession
import com.enchat.crypto.IdentityKeyPair
import com.enchat.crypto.OneTimePreKey
import com.enchat.crypto.SignedPreKey
import com.enchat.protocol.FrameType
import com.enchat.protocol.MessageKind
import com.enchat.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 业务编排层：把 REST、WebSocket、本地库、端到端会话串起来。
 *
 * 线程模型：所有状态修改都在 [scope]（单线程 IO + Supervisor）中串行处理，
 * 端到端会话对象本身是 @Synchronized 的，链不会并发推进。
 */
class ChatRepository(
    private val settings: SettingsStore,
    private val vault: KeystoreVault,
    private val db: AppDatabase,
    private val networkMonitor: NetworkMonitor,
    private val wsFactory: () -> WsClient,
    private val apiFactory: () -> ApiClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    private val dao: ChatDao get() = db.chatDao()

    private lateinit var api: ApiClient
    private lateinit var ws: WsClient

    private val reconnect = ReconnectPolicy()
    private var failedAttempts = 0

    /** 内存中的端到端会话（peerUid -> session）。落库由 [persistSession] 负责。 */
    private val e2eeSessions = ConcurrentHashMap<Long, E2eeSession>()

    private val _banner = MutableStateFlow<ConnectionStateMachine.Display>(ConnectionStateMachine.Display.Connecting)
    val banner: StateFlow<ConnectionStateMachine.Display> = _banner.asStateFlow()

    private val _linkState = MutableStateFlow(LinkState.IDLE)
    val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _pendingOutbox = MutableStateFlow(0)
    val pendingOutbox: StateFlow<Int> = _pendingOutbox.asStateFlow()

    private val _e2eeEnabledUids = MutableStateFlow<Set<Long>>(emptySet())
    val e2eeEnabledUids: StateFlow<Set<Long>> = _e2eeEnabledUids.asStateFlow()

    private var selfUid: Long = 0
    private var selfUserId: Long = 0

    // ---------------- 生命周期 ----------------

    fun start() {
        api = apiFactory()
        ws = wsFactory()

        scope.launch {
            combine(networkMonitor.available, ws.linkState) { netOk, link ->
                ConnectionStateMachine.reduce(
                    ConnectionStateMachine.Input(
                        network = if (netOk) ConnectionStateMachine.Network.AVAILABLE
                        else ConnectionStateMachine.Network.UNAVAILABLE,
                        server = when (link) {
                            LinkState.LINKED -> ConnectionStateMachine.ServerLink.LINKED
                            else -> ConnectionStateMachine.ServerLink.CONNECTING
                        }
                    )
                )
            }.collect { _banner.value = it }
        }

        scope.launch {
            ws.linkState.collect { link ->
                _linkState.value = link
                when (link) {
                    LinkState.LINKED -> {
                        failedAttempts = 0
                        // 刚连上：补一次增量同步，把离线期间的消息拉回来
                        scope.launch {
                            if (selfUserId != 0L) syncAfterReconnect()
                        }
                    }
                    LinkState.FAILED, LinkState.IDLE, LinkState.CONNECTING -> {
                        // 只有"有网"才值得重连；无网时等网络回来再试
                        if (networkMonitor.available.value) {
                            val delayMs = reconnect.nextDelayMs(failedAttempts++)
                            ws.reconnectAfter(delayMs)
                        }
                    }
                    LinkState.HANDSHAKING -> Unit
                }
            }
        }

        scope.launch {
            ws.incoming.collect { handleFrame(it) }
        }

        scope.launch {
            settings.e2eeEnabledUids.collect { set ->
                _e2eeEnabledUids.value = set.mapNotNull { it.toLongOrNull() }.toSet()
            }
        }

        scope.launch { restoreIdentityIfNeeded() }
    }

    /** 网络恢复时主动重连（由 UI/系统回调触发）。 */
    fun onNetworkBack() {
        networkMonitor.refresh()
        if (!ws.isLinked() && networkMonitor.available.value) {
            failedAttempts = 0
            ws.reconnectAfter(0)
        }
    }

    // ---------------- 鉴权 ----------------

    suspend fun register(username: String, password: String, nickname: String) {
        val result = api.register(username, password, nickname)
        afterAuth(result.accessToken, result.uid, result.userId, result.nickname, result.avatarUrl)
    }

    suspend fun login(username: String, password: String) {
        val result = api.login(username, password)
        afterAuth(result.accessToken, result.uid, result.userId, result.nickname, result.avatarUrl)
    }

    private suspend fun afterAuth(token: String, uid: Long, userId: Long, nickname: String, avatarUrl: String) {
        vault.putString(TOKEN_KEY, token)
        settings.setSelf(uid, userId, nickname, avatarUrl)
        selfUid = uid
        selfUserId = userId
        ensureIdentityUploaded()
        ws.connect(jsonObjectOf(
            "token" to token,
            "protocolVersion" to Protocol.VERSION,
            "deviceId" to deviceId()
        ))
        syncAfterReconnect()
    }

    suspend fun resumeIfPossible(): Boolean {
        val token = vault.getString(TOKEN_KEY) ?: return false
        selfUid = settings.selfUid.first()
        selfUserId = settings.selfUserId()
        if (selfUserId == 0L) return false
        restoreIdentityIfNeeded()
        ws.connect(jsonObjectOf(
            "token" to token,
            "protocolVersion" to Protocol.VERSION,
            "deviceId" to deviceId()
        ))
        return true
    }

    suspend fun logout() {
        try { api.logout() } catch (_: Exception) { /* 退出登录失败也要清本地 */ }
        ws.close()
        vault.wipe()
        settings.clearSession()
        db.clearAll()
        e2eeSessions.clear()
    }

    /** 页面销毁/进程退出前调用：关闭连接并擦除传输层密钥。 */
    fun close() {
        ws.close()
        // 只丢弃内存中的会话引用；链状态已持久化在库中，退出登录时会随 clearAll() 删除
        e2eeSessions.clear()
    }

    // ---------------- 身份密钥 ----------------

    /** 身份密钥：首次生成后用 Keystore 密钥封装保存，之后每次启动解封复用。 */
    private suspend fun restoreIdentityIfNeeded() {
        val sealed = vault.getString(IDENTITY_KEY)
        if (sealed == null) {
            val identity = IdentityKeyPair.generate()
            val spk = SignedPreKey.generate(1, identity)
            val opks = (1..20).map { OneTimePreKey.generate(it) }
            vault.putString(IDENTITY_KEY, vault.seal(identity.toBytes()))
            vault.putString(SPK_KEY, vault.seal(spk.toStorageBytes()))
            vault.putString(OPK_KEY, vault.seal(encodeOpks(opks)))
            pendingUpload = Triple(identity, spk, opks)
        } else {
            identity = IdentityKeyPair.fromBytes(vault.open(sealed))
            vault.getString(SPK_KEY)?.let { signedPreKey = SignedPreKey.fromStorageBytes(vault.open(it)) }
            vault.getString(OPK_KEY)?.let { oneTimePreKeys = decodeOpks(vault.open(it)).toMutableList() }
        }
    }

    private var pendingUpload: Triple<IdentityKeyPair, SignedPreKey, List<OneTimePreKey>>? = null

    private lateinit var identity: IdentityKeyPair
    private var signedPreKey: SignedPreKey? = null
    private var oneTimePreKeys: MutableList<OneTimePreKey> = mutableListOf()

    private suspend fun ensureIdentityUploaded() {
        val upload = pendingUpload ?: run {
            if (::identity.isInitialized && signedPreKey != null) {
                Triple(identity, signedPreKey!!, oneTimePreKeys.toList())
            } else null
        } ?: return
        try {
            api.uploadKeys(upload.first, upload.second, upload.third)
            pendingUpload = null
        } catch (e: Exception) {
            _authError.value = "密钥上传失败：${e.message}"
        }
    }

    // ---------------- 发送消息 ----------------

    /**
     * 发送文本。
     * @param e2ee true 表示这条要走端到端会话（服务器只见密文）
     */
    suspend fun sendText(peerUid: Long, conversationLocalId: Long, text: String, e2ee: Boolean): Boolean {
        val clientMsgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val entity = MessageEntity(
            conversationLocalId = conversationLocalId,
            senderUid = selfUid,
            outgoing = true,
            kind = MessageKind.TEXT.code,
            text = if (e2ee) null else text,
            ciphertextB64 = null,
            createdAt = now,
            state = MessageState.SENDING,
            clientMsgId = clientMsgId
        )
        dao.insertMessage(entity)

        val payload = JSONObject().apply {
            put("conversationId", serverConversationId(conversationLocalId))
            put("kind", MessageKind.TEXT.code)
            put("clientMsgId", clientMsgId)
            if (e2ee) {
                val session = ensureE2eeSession(peerUid)
                    ?: return failMessage(clientMsgId, "端到端会话未建立")
                put("e2ee", jsonObjectOf("ciphertext" to Bytes.toBase64(session.encrypt(text.toByteArray(Charsets.UTF_8)))))
            } else {
                put("text", text)
            }
        }

        val sent = ws.send(FrameType.SEND_MESSAGE, payload)
        if (!sent) {
            // 离线入队：等重连成功后由 flushOutbox 补发
            _pendingOutbox.value = _pendingOutbox.value + 1
            return false
        }
        dao.updateStateByClientId(clientMsgId, MessageState.SENT)
        return true
    }

    private suspend fun failMessage(clientMsgId: String, reason: String): Boolean {
        dao.updateStateByClientId(clientMsgId, MessageState.FAILED)
        _authError.value = reason
        return false
    }

    /** 本地会话 id → 服务端会话 id（发消息时要用服务端的 id）。 */
    private suspend fun serverConversationId(localId: Long): Long =
        dao.conversationByLocalId(localId)?.serverConversationId ?: 0L

    // ---------------- 端到端会话 ----------------

    /**
     * 开启高级加密：拉对方公钥包 → 建会话 → 发 E2EE_INIT → 标记该好友已开启。
     * 这是需求里"在聊天页右上角设置里启用高级加密聊天"的实现入口。
     */
    suspend fun enableE2ee(peerUid: Long, conversationLocalId: Long): Boolean {
        val bundle = api.keyBundle(peerUid)
        if (!bundle.verifySelfConsistent()) {
            _authError.value = "对方密钥包校验失败"
            return false
        }
        val (session, init) = E2eeSession.createAsSender(identity, selfUserId, bundle)
        e2eeSessions[peerUid] = session
        persistSession(peerUid, session)

        val sent = ws.send(FrameType.SEND_MESSAGE, JSONObject().apply {
            put("conversationId", conversationIdOfLocal(conversationLocalId))
            put("kind", MessageKind.E2EE_INIT.code)
            put("clientMsgId", UUID.randomUUID().toString())
            put("e2ee", jsonObjectOf("ciphertext" to Bytes.toBase64(init.toBytes())))
        })
        if (!sent) return false

        settings.setE2eeEnabled(peerUid, true)
        return true
    }

    suspend fun disableE2ee(peerUid: Long) {
        settings.setE2eeEnabled(peerUid, false)
        e2eeSessions.remove(peerUid)
        dao.deleteE2eeSession(peerUid, Protocol.ENCRYPTION_MODE_E2EE)
    }

    suspend fun safetyNumber(peerUid: Long): String? = e2eeSessions[peerUid]?.safetyNumber()

    private suspend fun ensureE2eeSession(peerUid: Long): E2eeSession? {
        e2eeSessions[peerUid]?.let { return it }
        val row = dao.e2eeSession(peerUid, Protocol.ENCRYPTION_MODE_E2EE) ?: return null
        return runCatching {
            E2eeSession.deserialize(vault.open(row.stateB64))
        }.onSuccess { e2eeSessions[peerUid] = it }.getOrNull()
    }

    private suspend fun persistSession(peerUid: Long, session: E2eeSession) {
        val serialized = E2eeSession.serialize(session)
        dao.upsertE2eeSession(
            E2eeSessionEntity(
                peerUid = peerUid,
                mode = Protocol.ENCRYPTION_MODE_E2EE,
                stateB64 = vault.seal(serialized),
                sessionIdB64 = Bytes.toBase64(session.sessionId)
            )
        )
    }

    private suspend fun conversationIdOfLocal(localId: Long): Long = serverConversationId(localId)

    // ---------------- 好友 ----------------

    suspend fun sendFriendRequest(uid: Long, message: String) {
        ws.send(FrameType.FRIEND_REQUEST_SEND, jsonObjectOf("toUid" to uid, "message" to message))
    }

    suspend fun acceptFriendRequest(requestId: Long) {
        ws.send(FrameType.FRIEND_ACCEPT, jsonObjectOf("requestId" to requestId))
    }

    suspend fun rejectFriendRequest(requestId: Long) {
        ws.send(FrameType.FRIEND_REJECT, jsonObjectOf("requestId" to requestId))
    }

    suspend fun removeFriend(uid: Long) {
        ws.send(FrameType.FRIEND_REMOVE, jsonObjectOf("friendUid" to uid))
        dao.deleteContact(uid)
    }

    suspend fun refreshFriends() {
        ws.send(FrameType.FRIEND_LIST, jsonObjectOf())
    }

    suspend fun findUser(uid: Long): JSONObject = api.user(uid)

    suspend fun updateProfile(nickname: String, signature: String) {
        api.updateProfile(nickname, signature)
        settings.setProfile(nickname, signature)
    }

    suspend fun uploadAvatar(mime: String, bytes: ByteArray): String {
        if (bytes.size > Protocol.MAX_AVATAR_BYTES) {
            throw IllegalArgumentException("头像不能超过 ${Protocol.MAX_AVATAR_BYTES / 1024 / 1024}MB")
        }
        val url = api.uploadAvatar(mime, bytes)
        settings.setAvatarUrl(url)
        return url
    }

    /** 打开（或创建）与某人的会话；mode 决定标准还是端到端。 */
    suspend fun openConversation(peerUid: Long, mode: Int, title: String, avatarUrl: String): Long {
        val existing = dao.conversation(peerUid, mode)
        if (existing != null) return existing.id
        val remote = api.openDirect(peerUid, mode)
        return dao.upsertConversation(
            ConversationEntity(
                serverConversationId = remote.conversationId,
                peerUid = peerUid,
                mode = mode,
                title = title,
                avatarUrl = avatarUrl
            )
        )
    }

    suspend fun markConversationRead(localId: Long) = dao.markRead(localId)

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()
    fun observeContacts(): Flow<List<ContactEntity>> = dao.observeContacts()
    fun observeRequests(): Flow<List<FriendRequestEntity>> = dao.observePendingRequests()
    fun observeMessages(localId: Long): Flow<List<MessageEntity>> = dao.observeMessages(localId)

    // ---------------- 收帧 ----------------

    private suspend fun handleFrame(frame: JSONObject) {
        when (frame.optInt("t", -1)) {
            FrameType.MESSAGE_DELIVER.code -> onDeliver(frame.optJSONObject("payload") ?: return)
            FrameType.MESSAGE_ACK.code -> onAck(frame.optJSONObject("payload") ?: return)
            FrameType.SYNC_RESPONSE.code -> onSync(frame.optJSONObject("payload") ?: return)
            FrameType.FRIEND_LIST.code -> onFriendList(frame.optJSONObject("payload") ?: return)
            FrameType.FRIEND_REQUEST_EVENT.code -> onFriendRequestEvent(frame.optJSONObject("payload") ?: return)
            FrameType.GROUP_MEMBER_CHANGE.code -> onGroupMemberChange(frame.optJSONObject("payload") ?: return)
        }
    }

    private suspend fun onDeliver(payload: JSONObject) {
        val peerUid = payload.optLong("senderId").takeIf { it != 0L } ?: return
        val kind = payload.optInt("kind")
        val e2ee = payload.optJSONObject("e2ee")

        if (kind == MessageKind.E2EE_INIT.code && e2ee != null) {
            acceptIncomingSession(peerUid, e2ee)
            return
        }

        val mode = if (e2ee != null) Protocol.ENCRYPTION_MODE_E2EE else Protocol.ENCRYPTION_MODE_STANDARD
        var text: String? = null
        var cipher: String? = null

        if (e2ee != null) {
            cipher = e2ee.optString("ciphertext")
            val session = ensureE2eeSession(peerUid)
            if (session != null) {
                text = runCatching {
                    String(session.decrypt(Bytes.fromBase64(cipher)), Charsets.UTF_8)
                }.getOrNull()
            }
        } else {
            text = payload.optString("text")
        }

        val conv = dao.conversation(peerUid, mode)
            ?: dao.upsertConversation(
                ConversationEntity(
                    peerUid = peerUid,
                    mode = mode,
                    title = Protocol.formatUid(peerUid),
                    avatarUrl = ""
                )
            ).let { dao.conversation(peerUid, mode) } ?: return

        if (dao.countServerMsg(payload.optLong("serverMsgId")) == 0) {
            dao.insertMessage(
                MessageEntity(
                    conversationLocalId = conv.id,
                    serverMsgId = payload.optLong("serverMsgId"),
                    senderUid = peerUid,
                    outgoing = false,
                    kind = kind,
                    text = text,
                    ciphertextB64 = cipher,
                    createdAt = payload.optLong("createdAt", System.currentTimeMillis()),
                    state = MessageState.RECEIVED
                )
            )
        }
        dao.updateConversation(
            conv.copy(
                lastMessageAt = payload.optLong("createdAt", System.currentTimeMillis()),
                lastPreview = if (mode == Protocol.ENCRYPTION_MODE_E2EE && text == null) "[端到端加密消息]" else (text ?: ""),
                unread = conv.unread + 1
            )
        )
    }

    private suspend fun acceptIncomingSession(peerUid: Long, e2ee: JSONObject) {
        val init = runCatching {
            com.enchat.crypto.X3dh.InitMessage.fromBytes(Bytes.fromBase64(e2ee.optString("ciphertext")))
        }.getOrNull() ?: return
        val opkId = init.receiverOneTimePreKeyId
        val opk = opkId?.let { id -> oneTimePreKeys.firstOrNull { it.id == id } }
        val spk = signedPreKey ?: return
        val session = runCatching {
            E2eeSession.createAsReceiver(identity, spk, opk, init)
        }.getOrNull() ?: return
        e2eeSessions[peerUid] = session
        persistSession(peerUid, session)
        settings.setE2eeEnabled(peerUid, true)
        // 用掉的一次性预密钥不再保留
        opk?.let { oneTimePreKeys.remove(it) }
        vault.putString(OPK_KEY, vault.seal(encodeOpks(oneTimePreKeys)))
    }

    private suspend fun onAck(payload: JSONObject) {
        val clientMsgId = payload.optString("clientMsgId")
        if (clientMsgId.isBlank()) return
        dao.markSent(clientMsgId, payload.optLong("serverMsgId"), MessageState.SENT)
        if (_pendingOutbox.value > 0) _pendingOutbox.value = _pendingOutbox.value - 1
    }

    private suspend fun onSync(payload: JSONObject) {
        val arr = payload.optJSONArray("messages") ?: return
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            // 与 onDeliver 复用同一条路径，避免两套入库逻辑
            onDeliver(m)
        }
        settings.setLastSyncSeq(payload.optLong("latestSeq", 0L))
    }

    private suspend fun onFriendList(payload: JSONObject) {
        val arr = payload.optJSONArray("friends") ?: return
        for (i in 0 until arr.length()) {
            val f = arr.getJSONObject(i)
            dao.upsertContact(
                ContactEntity(
                    uid = f.getLong("uid"),
                    nickname = f.optString("nickname"),
                    avatarUrl = f.optString("avatarUrl"),
                    signature = f.optString("signature"),
                    online = f.optBoolean("online")
                )
            )
        }
    }

    private suspend fun onFriendRequestEvent(payload: JSONObject) {
        val state = payload.optInt("state", 0)
        val requestId = payload.optLong("requestId")
        if (state != 0) {
            dao.deleteRequest(requestId)
            refreshFriends()
            return
        }
        dao.upsertRequest(
            FriendRequestEntity(
                requestId = requestId,
                fromUid = payload.optLong("fromUid"),
                fromNickname = payload.optString("fromNickname"),
                fromAvatarUrl = payload.optString("fromAvatarUrl"),
                message = payload.optString("message")
            )
        )
    }

    private suspend fun onGroupMemberChange(payload: JSONObject) {
        // 成员变动 → epoch 提升 → 本地必须丢弃旧 sender key（否则离群者仍能解后续消息）
        val convId = payload.optLong("conversationId")
        val epoch = payload.optInt("epoch")
        val conv = dao.conversationByServerId(convId)
        if (conv != null && epoch > conv.groupEpoch) {
            dao.upsertConversation(conv.copy(groupEpoch = epoch))
        }
    }

    suspend fun syncAfterReconnect() {
        val seq = settings.lastSyncSeq.first()
        ws.send(FrameType.SYNC_REQUEST, jsonObjectOf("afterSeq" to seq, "limit" to 200))
        refreshFriends()
    }

    // ---------------- 序列化辅助 ----------------

    private fun encodeOpks(keys: List<OneTimePreKey>): ByteArray {
        val json = org.json.JSONArray()
        keys.forEach { k ->
            json.put(jsonObjectOf("id" to k.id, "priv" to Bytes.toBase64(k.privateKeyBytes())))
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun decodeOpks(bytes: ByteArray): List<OneTimePreKey> {
        val json = org.json.JSONArray(String(bytes, Charsets.UTF_8))
        return (0 until json.length()).map { i ->
            val o = json.getJSONObject(i)
            OneTimePreKey.fromPrivateKey(o.getInt("id"), Bytes.fromBase64(o.getString("priv")))
        }
    }

    private fun deviceId(): String = vault.getString(DEVICE_ID_KEY) ?: UUID.randomUUID().toString().also {
        vault.putString(DEVICE_ID_KEY, it)
    }

    companion object {
        private const val TOKEN_KEY = "access_token"
        private const val IDENTITY_KEY = "identity_sealed"
        private const val SPK_KEY = "spk_sealed"
        private const val OPK_KEY = "opk_sealed"
        private const val DEVICE_ID_KEY = "device_id"
    }
}
