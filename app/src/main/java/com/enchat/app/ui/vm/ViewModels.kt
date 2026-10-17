package com.enchat.app.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.enchat.app.data.SettingsStore
import com.enchat.app.data.local.ContactEntity
import com.enchat.app.data.local.ConversationEntity
import com.enchat.app.data.local.FriendRequestEntity
import com.enchat.app.data.local.MessageEntity
import com.enchat.app.repo.ChatRepository
import com.enchat.clientcore.ConversationResolver
import com.enchat.clientcore.E2eeIntroTracker
import com.enchat.crypto.PasswordPolicy

import com.enchat.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AuthViewModel(private val repo: ChatRepository) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    /** 注册页实时反馈：问题列表 + 强度分（0..4）。 */
    private val _passwordState = MutableStateFlow(PasswordPolicy.Result(emptyList(), 0))
    val passwordState: StateFlow<PasswordPolicy.Result> = _passwordState.asStateFlow()

    fun onPasswordChange(password: String, username: String?) {
        _passwordState.value = PasswordPolicy.validate(password, username)
    }

    fun clearError() { _error.value = null }

    fun register(username: String, password: String, nickname: String) {
        if (!Protocol.isValidUsername(username)) {
            _error.value = "用户名需字母开头，3–32 位字母/数字/下划线"
            return
        }
        val policy = PasswordPolicy.validate(password, username)
        if (!policy.accepted) {
            _error.value = policy.problems.first()
            return
        }
        viewModelScope.launch {
            _busy.value = true
            // ⚠️ 不能用 runCatching { suspendFun() }：runCatching 的 lambda 不是 suspend 的，
            // 编译期直接报 "suspend function should be called only from a coroutine"。
            try {
                repo.register(username, password, nickname.ifBlank { username })
                _done.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "注册失败"
            } finally {
                _busy.value = false
            }
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repo.login(username, password)
                _done.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: "登录失败"
            } finally {
                _busy.value = false
            }
        }
    }
}

/**
 * 会话列表：本地会话（Room）与好友列表合并展示。
 *
 * 之所以要合并：需求要求"未说过话的好友也要能从列表/好友页进入聊天页去开启高级加密"，
 * 而 Room 里只在真正创建会话后才有记录。合并逻辑委托给 client-core 的纯函数。
 */
class ConversationsViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore
) : ViewModel() {

    data class UiEntry(
        val peerUid: Long,
        val mode: Int,
        val title: String,
        val avatarUrl: String,
        val preview: String,
        val time: Long,
        val unread: Int,
        val localId: Long
    )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // 显式写出 combine 的泛型与 associate 的 Map 类型：
    // 一旦上游实体（ConversationEntity 等）解析失败，这里会直接报出**实体名**，
    // 而不是退化成难以定位的 "Not enough information to infer type variable K"。
    val entries: StateFlow<List<UiEntry>> = combine<
        List<ConversationEntity>, List<ContactEntity>, Set<Long>, List<UiEntry>
    >(
        repo.observeConversations(),
        repo.observeContacts(),
        repo.e2eeEnabledUids
    ) { conversations, contacts, enabled ->
        val sessions: Map<Pair<Long, Int>, ConversationResolver.SessionSummary> =
            conversations.associate { c ->
                (c.peerUid to c.mode) to ConversationResolver.SessionSummary(
                    lastMessageAt = c.lastMessageAt,
                    lastPreview = c.lastPreview,
                    unread = c.unread
                )
            }
        val list = ConversationResolver.resolve(
            contacts = contacts.map { ConversationResolver.Contact(it.uid, it.nickname, it.avatarUrl, it.signature) },
            e2eeEnabledUids = enabled,
            sessions = sessions
        )
        val localIdByKey: Map<Pair<Long, Int>, Long> =
            conversations.associate { (it.peerUid to it.mode) to it.id }
        list.map { e ->
            UiEntry(
                peerUid = e.peerUid,
                mode = e.mode,
                title = e.title,
                avatarUrl = e.avatarUrl,
                preview = e.lastPreview,
                time = e.lastMessageAt,
                unread = e.unread,
                localId = localIdByKey[e.peerUid to e.mode] ?: 0L
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(q: String) { _query.value = q }

    fun refresh() {
        viewModelScope.launch { repo.refreshFriends() }
    }

    /** 点击条目：若本地还没有会话则先创建（标准模式默认存在，端到端需已开启）。 */
    suspend fun open(entry: UiEntry, onReady: (Long) -> Unit) {
        if (entry.localId != 0L) {
            repo.markConversationRead(entry.localId)
            onReady(entry.localId)
            return
        }
        val contact = repo.observeContacts().first().firstOrNull { it.uid == entry.peerUid }
        val id = repo.openConversation(
            peerUid = entry.peerUid,
            mode = entry.mode,
            title = contact?.nickname ?: Protocol.formatUid(entry.peerUid),
            avatarUrl = contact?.avatarUrl ?: ""
        )
        onReady(id)
    }
}

class ChatViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore
) : ViewModel() {

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _e2eeEnabled = MutableStateFlow(false)
    val e2eeEnabled: StateFlow<Boolean> = _e2eeEnabled.asStateFlow()

    private val _showIntro = MutableStateFlow(false)
    val showIntro: StateFlow<Boolean> = _showIntro.asStateFlow()

    private val _safetyNumber = MutableStateFlow<String?>(null)
    val safetyNumber: StateFlow<String?> = _safetyNumber.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val introTracker = E2eeIntroTracker()

    private var peerUid: Long = 0
    private var localId: Long = 0
    private var mode: Int = Protocol.ENCRYPTION_MODE_STANDARD

    fun bind(localId: Long, peerUid: Long, mode: Int) {
        this.localId = localId
        this.peerUid = peerUid
        this.mode = mode
        viewModelScope.launch {
            repo.observeMessages(localId).collect { _messages.value = it }
        }
        viewModelScope.launch {
            _e2eeEnabled.value = settings.isE2eeEnabled(peerUid)
            if (mode == Protocol.ENCRYPTION_MODE_E2EE && !settings.hasSeenIntro(peerUid)) {
                _showIntro.value = true
            }
        }
        viewModelScope.launch { repo.markConversationRead(localId) }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val ok = repo.sendText(peerUid, localId, text, e2ee = mode == Protocol.ENCRYPTION_MODE_E2EE)
            if (!ok) _error.value = "消息已保存，将在恢复连接后发送"
        }
    }

    /** 聊天页右上角设置里的"启用高级加密聊天"开关。 */
    fun setE2eeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val ok = repo.enableE2ee(peerUid, localId)
                _e2eeEnabled.value = ok
                if (ok) {
                    settings.setE2eeEnabled(peerUid, true)
                    if (introTracker.shouldShow(peerUid)) _showIntro.value = true
                } else {
                    _error.value = "开启失败，请检查网络后重试"
                }
            } else {
                repo.disableE2ee(peerUid)
                _e2eeEnabled.value = false
            }
        }
    }

    fun dismissIntro() {
        _showIntro.value = false
        viewModelScope.launch { settings.markIntroSeen(peerUid) }
    }

    fun loadSafetyNumber() {
        viewModelScope.launch {
            _safetyNumber.value = repo.safetyNumber(peerUid) ?: "（尚未建立端到端会话）"
        }
    }

    fun clearSafetyNumber() { _safetyNumber.value = null }

    fun clearError() { _error.value = null }
}

class ContactsViewModel(private val repo: ChatRepository) : ViewModel() {

    val contacts: StateFlow<List<ContactEntity>> =
        repo.observeContacts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val requests: StateFlow<List<FriendRequestEntity>> =
        repo.observeRequests().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _searchUid = MutableStateFlow("")
    val searchUid: StateFlow<String> = _searchUid.asStateFlow()

    private val _found = MutableStateFlow<Pair<Long, String>?>(null)
    val found: StateFlow<Pair<Long, String>?> = _found.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun onSearchChange(v: String) { _searchUid.value = v }

    fun search() {
        val uid = _searchUid.value.trim().toLongOrNull()
        if (uid == null) {
            _error.value = "请输入数字 UID"
            return
        }
        viewModelScope.launch {
            try {
                _found.value = uid to repo.findUser(uid).optString("nickname")
            } catch (e: Exception) {
                _error.value = e.message ?: "未找到该用户"
            }
        }
    }

    fun addFriend(uid: Long, message: String) {
        viewModelScope.launch {
            repo.sendFriendRequest(uid, message)
            _found.value = null
        }
    }

    fun accept(requestId: Long) = viewModelScope.launch { repo.acceptFriendRequest(requestId) }
    fun reject(requestId: Long) = viewModelScope.launch { repo.rejectFriendRequest(requestId) }
    fun remove(uid: Long) = viewModelScope.launch { repo.removeFriend(uid) }
    fun refresh() = viewModelScope.launch { repo.refreshFriends() }
}

class SettingsViewModel(
    private val repo: ChatRepository,
    private val settings: SettingsStore
) : ViewModel() {

    val nickname = settings.nickname.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val signature = settings.signature.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val avatarUrl = settings.avatarUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /**
     * 自己的 UID（9 位补零展示）。
     *
     * 用 map + stateIn 而不是在 UI 里直接 format：格式化规则属于数据层约定，
     * 且这里是 Flow 到 StateFlow 的转换，放 VM 里能让 UI 只管渲染。
     */
    val uidDisplay: StateFlow<String> = settings.selfUid
        .map { if (it == 0L) "" else Protocol.formatUid(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    val themeMode = settings.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val notifyEnabled = settings.notifyEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifySound = settings.notifySound.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifyPreview = settings.notifyPreview.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val readReceipt = settings.readReceipt.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val fontScale = settings.fontScale.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 100)
    val enterToSend = settings.enterToSend.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoDownload = settings.autoDownload.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val keepDays = settings.keepDays.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)
    val host = settings.host.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun saveProfile(nickname: String, signature: String) {
        viewModelScope.launch {
            _message.value = try {
                repo.updateProfile(nickname, signature)
                "已保存"
            } catch (e: Exception) {
                e.message ?: "保存失败"
            }
        }
    }

    fun setThemeMode(v: Int) = viewModelScope.launch { settings.setThemeMode(v) }
    fun setNotifyEnabled(v: Boolean) = viewModelScope.launch { settings.setNotifyEnabled(v) }
    fun setNotifySound(v: Boolean) = viewModelScope.launch { settings.setNotifySound(v) }
    fun setNotifyPreview(v: Boolean) = viewModelScope.launch { settings.setNotifyPreview(v) }
    fun setReadReceipt(v: Boolean) = viewModelScope.launch { settings.setReadReceipt(v) }
    fun setFontScale(v: Int) = viewModelScope.launch { settings.setFontScale(v) }
    fun setEnterToSend(v: Boolean) = viewModelScope.launch { settings.setEnterToSend(v) }
    fun setAutoDownload(v: Boolean) = viewModelScope.launch { settings.setAutoDownload(v) }
    fun setKeepDays(v: Int) = viewModelScope.launch { settings.setKeepDays(v) }
    fun setHost(v: String) = viewModelScope.launch { settings.setHost(v) }

    /**
     * 退出登录。
     *
     * ⚠️ 必须 try/catch：repo.logout() 里涉及网络、Keystore、Room 清库，
     * 任一环节抛异常若未捕获会直接冒泡到主线程导致进程崩溃 ——
     * 原来的写法正是这样，表现为"点了退出登录 App 直接关闭"。
     *
     * 失败时保留在设置页并提示，用户可重试；不会因为清了一半而卡死。
     */
    fun logout(onDone: () -> Unit) {
        viewModelScope.launch {
            _message.value = null
            try {
                repo.logout()
                onDone()
            } catch (e: Exception) {
                _message.value = "退出登录失败：${e.message ?: "未知错误"}"
            }
        }
    }
}

/** 极简工厂：手动 DI，未引入 Koin/Hilt，减少构建复杂度。 */
@Suppress("UNCHECKED_CAST")
class AppViewModelFactory(
    private val repo: ChatRepository,
    private val settings: SettingsStore
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        AuthViewModel::class.java -> AuthViewModel(repo) as T
        ConversationsViewModel::class.java -> ConversationsViewModel(repo, settings) as T
        ChatViewModel::class.java -> ChatViewModel(repo, settings) as T
        ContactsViewModel::class.java -> ContactsViewModel(repo) as T
        SettingsViewModel::class.java -> SettingsViewModel(repo, settings) as T
        else -> throw IllegalArgumentException("未知 ViewModel: ${modelClass.name}")
    }
}
