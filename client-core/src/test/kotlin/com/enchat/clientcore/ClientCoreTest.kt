package com.enchat.clientcore

import com.enchat.protocol.Protocol
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConnectionStateMachineTest {

    @Test
    fun `no network always shows waiting-for-network`() {
        // 网络不可用时，服务器状态无意义 —— 无论是 LINKED 还是 CONNECTING 都显示等待网络
        assertEquals(
            ConnectionStateMachine.Display.WaitingForNetwork,
            ConnectionStateMachine.reduce(
                ConnectionStateMachine.Input(ConnectionStateMachine.Network.UNAVAILABLE, ConnectionStateMachine.ServerLink.LINKED)
            )
        )
        assertEquals(
            ConnectionStateMachine.Display.WaitingForNetwork,
            ConnectionStateMachine.reduce(
                ConnectionStateMachine.Input(ConnectionStateMachine.Network.UNAVAILABLE, ConnectionStateMachine.ServerLink.CONNECTING)
            )
        )
        assertEquals("正在等待网络…", ConnectionStateMachine.bannerText(ConnectionStateMachine.Display.WaitingForNetwork))
    }

    @Test
    fun `network available but server not linked shows connecting`() {
        assertEquals(
            ConnectionStateMachine.Display.Connecting,
            ConnectionStateMachine.reduce(
                ConnectionStateMachine.Input(ConnectionStateMachine.Network.AVAILABLE, ConnectionStateMachine.ServerLink.CONNECTING)
            )
        )
        assertEquals("连接中…", ConnectionStateMachine.bannerText(ConnectionStateMachine.Display.Connecting))
    }

    @Test
    fun `linked hides banner`() {
        assertEquals(
            ConnectionStateMachine.Display.Hidden,
            ConnectionStateMachine.reduce(
                ConnectionStateMachine.Input(ConnectionStateMachine.Network.AVAILABLE, ConnectionStateMachine.ServerLink.LINKED)
            )
        )
        assertNull(ConnectionStateMachine.bannerText(ConnectionStateMachine.Display.Hidden))
    }

    @Test
    fun `pending badge only when outbox non empty`() {
        assertNull(ConnectionStateMachine.badge(0))
        assertEquals("（3 条待发送）", ConnectionStateMachine.badge(3))
    }

    @Test
    fun `reconnect backoff grows and stays capped`() {
        val policy = ReconnectPolicy(random = { 0L }) // 关掉抖动，便于断言上界
        val d0 = policy.nextDelayMs(0)
        val d1 = policy.nextDelayMs(1)
        val d5 = policy.nextDelayMs(5)
        assertTrue(d0 <= ReconnectPolicy.MAX_DELAY_MS)
        assertTrue(d1 >= d0, "$d1 >= $d0")
        assertTrue(d5 >= d1)

        val huge = policy.nextDelayMs(40) // 不溢出、且被封顶
        assertTrue(huge <= ReconnectPolicy.MAX_DELAY_MS, "huge=$huge")
    }

    @Test
    fun `jitter stays within half to full window`() {
        val policy = ReconnectPolicy(baseDelayMs = 1_000, random = { bound -> bound - 1 })
        val d = policy.nextDelayMs(3) // capped = 8s → [4s, 8s]
        assertTrue(d in 4_000..8_000, "d=$d")
    }
}

class ConversationResolverTest {

    private val alice = ConversationResolver.Contact(100000001L, "Alice", "http://x/a.png")
    private val bob = ConversationResolver.Contact(100000002L, "Bob")
    private val carol = ConversationResolver.Contact(100000003L, "Carol")

    @Test
    fun `standard conversation exists even before enabling e2ee`() {
        val list = ConversationResolver.resolve(listOf(alice, bob), emptySet(), emptyMap())
        assertEquals(2, list.size)
        assertTrue(list.all { !it.isE2ee })
    }

    @Test
    fun `enabling advanced encryption adds one more conversation for that friend`() {
        val before = ConversationResolver.resolve(listOf(alice, bob), emptySet(), emptyMap())
        val after = ConversationResolver.resolve(listOf(alice, bob), setOf(alice.uid), emptyMap())

        assertEquals(2, before.size)
        assertEquals(3, after.size, "开启后应多出一条端到端会话")

        val aliceEntries = after.filter { it.peerUid == alice.uid }
        assertEquals(2, aliceEntries.size)
        assertEquals(1, aliceEntries.count { it.isE2ee })
        assertEquals(1, aliceEntries.count { !it.isE2ee })
        assertEquals(1, after.count { it.peerUid == bob.uid }, "未开启的好友不应多出会话")
    }

    @Test
    fun `entries are ordered by last message time descending`() {
        val sessions = mapOf(
            (alice.uid to Protocol.ENCRYPTION_MODE_STANDARD) to
                ConversationResolver.SessionSummary(300L, "最近", 0),
            (bob.uid to Protocol.ENCRYPTION_MODE_STANDARD) to
                ConversationResolver.SessionSummary(100L, "较早", 2),
            (carol.uid to Protocol.ENCRYPTION_MODE_STANDARD) to
                ConversationResolver.SessionSummary(200L, "中间", 1)
        )
        val list = ConversationResolver.resolve(listOf(alice, bob, carol), emptySet(), sessions)
        assertEquals(listOf(300L, 200L, 100L), list.map { it.lastMessageAt })
        assertEquals(listOf(alice.uid, carol.uid, bob.uid), list.map { it.peerUid })
    }

    @Test
    fun `title falls back to formatted uid when nickname blank`() {
        val anon = ConversationResolver.Contact(12345L, "")
        val list = ConversationResolver.resolve(listOf(anon), emptySet(), emptyMap())
        assertEquals(Protocol.formatUid(12345L), list.first().title)
    }

    @Test
    fun `unread counts are per mode`() {
        val sessions = mapOf(
            (alice.uid to Protocol.ENCRYPTION_MODE_STANDARD) to
                ConversationResolver.SessionSummary(10L, "明文", 1),
            (alice.uid to Protocol.ENCRYPTION_MODE_E2EE) to
                ConversationResolver.SessionSummary(20L, "密文", 5)
        )
        val list = ConversationResolver.resolve(listOf(alice), setOf(alice.uid), sessions)
        val standard = list.first { !it.isE2ee }
        val e2ee = list.first { it.isE2ee }
        assertEquals(1, standard.unread)
        assertEquals(5, e2ee.unread)
        assertNotEquals(standard.lastMessageAt, e2ee.lastMessageAt)
    }

    @Test
    fun `intro dialog shows only once per friend`() {
        val tracker = E2eeIntroTracker()
        assertTrue(tracker.shouldShow(alice.uid))
        assertFalse(tracker.shouldShow(alice.uid), "第二次进入不应再弹")
        assertTrue(tracker.shouldShow(bob.uid))
        assertEquals(setOf(alice.uid, bob.uid), tracker.snapshot())
    }

    @Test
    fun `intro text matches the required copy`() {
        assertEquals("你已开启端到端聊天", E2eeIntroText.TITLE)
        assertEquals("以下功能将会得到加强：", E2eeIntroText.LEAD)
        assertEquals(
            listOf("加密增强", "卸载软件后聊天将永远不复存在", "整体安全系数达到提升"),
            E2eeIntroText.BULLETS
        )
    }
}
