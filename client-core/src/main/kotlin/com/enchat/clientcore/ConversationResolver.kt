package com.enchat.clientcore

import com.enchat.protocol.Protocol

/**
 * 会话列表解析规则（纯 Kotlin，可单测）。
 *
 * 需求原文：「加完好友点击好友聊天页面后，在右上角设置里启用高级加密聊天，
 * 开启之后，好友列表会多一个好友对话」。
 *
 * 即：同一个好友在同一种列表里可能出现**两条**会话 —— 标准加密会话 + 端到端加密会话。
 * 这里把这条规则固化成纯函数，避免 UI 里散落 if/else 导致行为不一致。
 */
object ConversationResolver {

    /** 好友（联系人）最小信息。 */
    data class Contact(
        val uid: Long,
        val nickname: String,
        val avatarUrl: String = "",
        val signature: String = ""
    )

    /** 列表中的一条会话条目。 */
    data class Entry(
        val peerUid: Long,
        val mode: Int,
        val title: String,
        val avatarUrl: String,
        val lastMessageAt: Long,
        val lastPreview: String,
        val unread: Int
    ) {
        val isE2ee: Boolean get() = mode == Protocol.ENCRYPTION_MODE_E2EE
    }

    /**
     * @param contacts       好友列表
     * @param e2eeEnabledUids 已开启高级加密的好友 UID 集合
     * @param sessions       已有会话的最后一条消息信息；key 为 (uid, mode)
     */
    fun resolve(
        contacts: List<Contact>,
        e2eeEnabledUids: Set<Long>,
        sessions: Map<Pair<Long, Int>, SessionSummary>
    ): List<Entry> {
        val out = mutableListOf<Entry>()

        for (c in contacts) {
            // 标准会话：永远存在（即使从未说过话，也要能从好友进入聊天页去开启高级加密）
            val standard = sessions[c.uid to Protocol.ENCRYPTION_MODE_STANDARD]
            out += Entry(
                peerUid = c.uid,
                mode = Protocol.ENCRYPTION_MODE_STANDARD,
                title = c.nickname.ifBlank { Protocol.formatUid(c.uid) },
                avatarUrl = c.avatarUrl,
                lastMessageAt = standard?.lastMessageAt ?: 0L,
                lastPreview = standard?.lastPreview ?: "",
                unread = standard?.unread ?: 0
            )

            // 端到端会话：只有开启过高级加密才出现，这正是需求里"多出来的那一条"
            if (c.uid in e2eeEnabledUids) {
                val e2ee = sessions[c.uid to Protocol.ENCRYPTION_MODE_E2EE]
                out += Entry(
                    peerUid = c.uid,
                    mode = Protocol.ENCRYPTION_MODE_E2EE,
                    title = c.nickname.ifBlank { Protocol.formatUid(c.uid) },
                    avatarUrl = c.avatarUrl,
                    lastMessageAt = e2ee?.lastMessageAt ?: System.currentTimeMillis(),
                    lastPreview = e2ee?.lastPreview ?: "端到端加密会话已开启",
                    unread = e2ee?.unread ?: 0
                )
            }
        }

        // 最近有消息的排前面；时间相同用 UID 兜底，保证顺序稳定（避免列表乱跳）
        return out.sortedWith(compareByDescending<Entry> { it.lastMessageAt }.thenBy { it.peerUid }.thenBy { it.mode })
    }

    /** 会话摘要（由本地库统计得出）。 */
    data class SessionSummary(
        val lastMessageAt: Long,
        val lastPreview: String,
        val unread: Int
    )
}

/**
 * 高级加密说明弹窗的展示时机：每个好友的端到端会话**首次进入**时弹一次。
 *
 * 放在纯逻辑层是为了可测：UI 只负责渲染，是否弹过由 [E2eeIntroTracker] 决定。
 */
class E2eeIntroTracker(
    private val seen: MutableSet<Long> = mutableSetOf()
) {
    /** @return true 表示这次应该弹窗（并把该好友标记为已看过） */
    fun shouldShow(uid: Long): Boolean = seen.add(uid)

    fun markSeen(uid: Long) { seen.add(uid) }

    fun hasSeen(uid: Long): Boolean = uid in seen

    fun snapshot(): Set<Long> = seen.toSet()
}

/** 弹窗文案：需求指定内容，集中放一处，避免多端各写一份导致文案漂移。 */
object E2eeIntroText {
    const val TITLE = "你已开启端到端聊天"
    const val LEAD = "以下功能将会得到加强："
    val BULLETS: List<String> = listOf(
        "加密增强",
        "卸载软件后聊天将永远不复存在",
        "整体安全系数达到提升"
    )
}
