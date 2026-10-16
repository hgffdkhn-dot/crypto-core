package com.enchat.protocol

/**
 * 协议层常量与枚举：客户端（Android）与服务端（Ktor）共享，避免两端各写一份导致漂移。
 * 这里只放"协议契约"，不放任何实现/存储细节。
 */
object Protocol {
    /** 线协议版本；不兼容变更时递增，服务端据此拒绝过旧客户端。 */
    const val VERSION = 1

    /** 会话加密模式取值（与 [ChatEncryptionMode.code] 一致，供不便引用枚举的场景使用）。 */
    const val ENCRYPTION_MODE_STANDARD = 1
    const val ENCRYPTION_MODE_E2EE = 2

    const val WS_PATH = "/ws"
    const val API_PREFIX = "/api/v1"

    /** 单帧上限（含传输层封装后的字节数），超出直接断开，防内存打爆。 */
    const val MAX_FRAME_BYTES = 1024 * 1024

    /** 单条消息明文上限（UTF-8 字节）。 */
    const val MAX_TEXT_BYTES = 8000

    /** 头像上限：先压缩再上传。 */
    const val MAX_AVATAR_BYTES = 2 * 1024 * 1024

    const val MAX_GROUP_MEMBERS = 500

    /** 昵称长度（按 Unicode 码点计）。 */
    const val NICKNAME_MIN = 1
    const val NICKNAME_MAX = 32

    /** 用户名规则：字母开头，允许字母数字下划线，长度 3~32。 */
    private val USERNAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]{2,31}$")

    fun isValidUsername(name: String): Boolean = USERNAME_REGEX.matches(name)

    /** UID 展示形态：内部 64 位，对外展示补足 9 位，例如 000123456。 */
    fun formatUid(uid: Long): String = uid.toString().padStart(9, '0')
}

/** 连接状态：对应需求里的顶部状态条。 */
enum class ConnectionState(val displayText: String) {
    /** 网络可用，正在与服务器建立/重连 WebSocket：显示"连接中…" */
    CONNECTING("连接中…"),

    /** 设备无网络（无 Wi-Fi/蜂窝或飞行模式）：显示"正在等待网络…" */
    WAITING_FOR_NETWORK("正在等待网络…"),

    /** 已连接并完成握手：不显示状态条 */
    CONNECTED(""),

    /** 已认证但 WebSocket 断开、正在退避重连：显示"连接中…" */
    RECONNECTING("连接中…"),

    /** 明确失败（如被踢下线、版本过旧、鉴权失效）：显示具体原因 */
    FAILED("连接失败");

    fun showBanner(): Boolean = this != CONNECTED
}

/** WebSocket 帧类型（业务层，承载在传输层加密帧内）。 */
enum class FrameType(val code: Int) {
    // 连接与鉴权
    HELLO(1),
    AUTH(2),
    AUTH_RESULT(3),
    PING(4),
    PONG(5),

    // 消息
    SEND_MESSAGE(10),
    MESSAGE_DELIVER(11),
    MESSAGE_ACK(12),
    MESSAGE_RECEIPT(13),
    SYNC_REQUEST(14),
    SYNC_RESPONSE(15),

    // 好友
    FRIEND_REQUEST_SEND(20),
    FRIEND_REQUEST_EVENT(21),
    FRIEND_ACCEPT(22),
    FRIEND_REJECT(23),
    FRIEND_REMOVE(24),
    FRIEND_LIST(25),

    // 群
    GROUP_CREATE(30),
    GROUP_INVITE(31),
    GROUP_JOIN(32),
    GROUP_LEAVE(33),
    GROUP_MEMBER_CHANGE(34),
    GROUP_LIST(35),

    // 端到端加密
    PREKEY_UPLOAD(40),
    PREKEY_FETCH(41),
    PREKEY_FETCH_RESULT(42),
    E2EE_INIT(43),
    E2EE_SENDERKEY_DISTRIBUTE(44),
    E2EE_ENABLED_EVENT(45),

    // 错误
    ERROR(99);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): FrameType? = byCode[code]
    }
}

/** 错误码：服务端返回，客户端据此决定重试/提示/登出。 */
enum class ErrorCode(val code: Int, val message: String) {
    BAD_REQUEST(400, "请求格式错误"),
    UNAUTHORIZED(401, "未登录或登录已失效"),
    FORBIDDEN(403, "没有权限"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突"),
    RATE_LIMITED(429, "操作过于频繁，请稍后再试"),
    INTERNAL(500, "服务器内部错误"),

    VERSION_TOO_OLD(1001, "客户端版本过旧，请更新"),
    USERNAME_TAKEN(1002, "用户名已被占用"),
    WEAK_PASSWORD(1003, "密码强度不足"),
    WRONG_CREDENTIALS(1004, "用户名或密码错误"),
    INVALID_UID(1005, "UID 不存在"),
    ALREADY_FRIENDS(1006, "已经是好友"),
    NOT_FRIENDS(1007, "还不是好友"),
    FRIEND_REQUEST_PENDING(1008, "好友请求已发送，等待对方处理"),
    GROUP_FULL(1009, "群成员已满"),
    PREKEY_EXHAUSTED(1010, "对方预密钥已用完，请稍后重试"),
    E2EE_REQUIRED(1011, "该会话已启用端到端加密，请升级后再发送"),
    MESSAGE_TOO_LARGE(1012, "消息过长"),
    TRANSPORT_DECRYPT_FAILED(1013, "传输层解密失败，连接将被重置"),
}

/** 消息内容类型。 */
enum class MessageKind(val code: Int) {
    TEXT(1),
    IMAGE(2),
    VOICE(3),
    FILE(4),
    SYSTEM(5),

    /** E2EE 会话初始化（不展示为普通消息）。 */
    E2EE_INIT(10),

    /** Sender key 分发（不展示为普通消息）。 */
    E2EE_SENDER_KEY(11);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun fromCode(code: Int): MessageKind? = byCode[code]
    }
}

/** 加密模式：普通聊天（传输加密）与高级加密（端到端）。 */
enum class ChatEncryptionMode(val code: Int, val displayName: String) {
    /** 默认：客户端↔服务器传输层加密，服务器可见明文并落库。 */
    TRANSPORT(1, "标准加密"),

    /** 高级加密聊天：端到端加密，服务器只中转密文。 */
    E2EE(2, "端到端加密");
}
