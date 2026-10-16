package com.enchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.enchat.clientcore.E2eeIntroText

/**
 * 高级加密开启后的说明弹窗。
 *
 * 需求原文：
 * 「点进去之后会显示弹窗，弹窗上面写着：你已开启端到端聊天（弹窗里还要加一把锁的图标，显得高级点）
 *   以下功能将会得到加强：（然后往下排序）
 *   加密增强 √ / 卸载软件后聊天将永远不复存在 √ / 整体安全系数达到提升 √」
 *
 * 文案统一取自 client-core 的 [E2eeIntroText]，避免与文档/其他端漂移。
 */
@Composable
fun E2eeIntroDialog(
    peerName: String,
    onDismiss: () -> Unit,
    onViewSafetyNumber: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "端到端加密",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        },
        title = {
            Text(
                text = E2eeIntroText.TITLE,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "与「$peerName」的对话已升级为端到端加密。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = E2eeIntroText.LEAD, style = MaterialTheme.typography.bodyMedium)
                E2eeIntroText.BULLETS.forEach { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "√",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(text = item, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(
                    text = "提示：卸载应用或更换设备后，本会话的历史消息将无法恢复。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("我知道了") }
        },
        dismissButton = {
            if (onViewSafetyNumber != null) {
                TextButton(onClick = onViewSafetyNumber) { Text("核对安全数字") }
            }
        }
    )
}
