package com.enchat.clientcore

/**
 * 连接状态机（纯 Kotlin，Android 与单元测试共用）。
 *
 * 需求映射：
 * - 网络不可用                → 顶部横幅显示「正在等待网络…」
 * - 网络可用但未连上服务器     → 顶部横幅显示「连接中…」
 * - 已连上服务器              → 不显示横幅
 *
 * 为什么把"网络"和"服务器链路"分成两个维度：
 * 这两件事的恢复手段完全不同（前者要用户开网，后者是自动重连），
 * UI 提示也必须区分，否则用户会对着"连接中"反复重启 App。
 */
object ConnectionStateMachine {

    enum class Network { AVAILABLE, UNAVAILABLE }

    enum class ServerLink { LINKED, CONNECTING }

    data class Input(
        val network: Network,
        val server: ServerLink
    )

    sealed class Display {
        /** 已连接：不显示任何横幅。 */
        object Hidden : Display()

        /** 网络可用、服务器未连通。 */
        object Connecting : Display()

        /** 网络不可用（此时服务器状态无意义，统一显示等待网络）。 */
        object WaitingForNetwork : Display()
    }

    fun reduce(input: Input): Display = when (input.network) {
        Network.UNAVAILABLE -> Display.WaitingForNetwork
        Network.AVAILABLE -> when (input.server) {
            ServerLink.LINKED -> Display.Hidden
            ServerLink.CONNECTING -> Display.Connecting
        }
    }

    fun bannerText(display: Display): String? = when (display) {
        Display.Hidden -> null
        Display.Connecting -> "连接中…"
        Display.WaitingForNetwork -> "正在等待网络…"
    }

    /**
     * 是否有待发送内容因离线而积压（用于横幅补充提示"N 条待发送"）。
     * 即使处于 WaitingForNetwork，只要队列非空也应让用户知道消息没丢。
     */
    fun badge(pendingOutbox: Int): String? =
        if (pendingOutbox > 0) "（$pendingOutbox 条待发送）" else null
}

/**
 * 重连退避策略：指数退避 + 抖动，封顶 [MAX_DELAY_MS]。
 *
 * 加抖动是必要的：服务器重启时所有客户端会在同一时刻重连，
 * 无抖动会形成重连风暴（thundering herd）。
 */
class ReconnectPolicy(
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val random: (Long) -> Long = { bound -> (Math.random() * bound).toLong() }
) {
    /** @param attempt 从 0 开始的连续失败次数 */
    fun nextDelayMs(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0)
        val exponential = baseDelayMs shl safeAttempt.coerceAtMost(20) // 防溢出
        val capped = minOf(exponential, maxDelayMs)
        // 抖动：取 [capped/2, capped]
        return capped / 2 + random(capped / 2 + 1)
    }

    companion object {
        const val MAX_DELAY_MS = 30_000L
    }
}
