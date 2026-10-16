package com.enchat.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.enchat.clientcore.ConnectionStateMachine

/**
 * 顶部连接状态条。
 *
 * 文案严格按需求：
 * - 网络不可用            → 「正在等待网络…」
 * - 有网但未连上服务器     → 「连接中…」
 * - 已连接                → 不显示（收起动画）
 *
 * 文案来自 client-core 的 [ConnectionStateMachine.bannerText]，避免 UI 里硬编码字符串。
 */
@Composable
fun ConnectionBanner(
    display: ConnectionStateMachine.Display,
    pendingOutbox: Int = 0,
    modifier: Modifier = Modifier
) {
    val text = ConnectionStateMachine.bannerText(display)
    val badge = ConnectionStateMachine.badge(pendingOutbox)

    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        modifier = modifier
    ) {
        if (text == null) return@AnimatedVisibility
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (display == ConnectionStateMachine.Display.WaitingForNetwork)
                        Icons.Default.CloudOff else Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = buildString {
                        append(text)
                        if (badge != null && display != ConnectionStateMachine.Display.WaitingForNetwork) {
                            append(' ').append(badge)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
