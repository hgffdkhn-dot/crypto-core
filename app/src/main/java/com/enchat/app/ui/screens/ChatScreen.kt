package com.enchat.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.data.local.MessageEntity
import com.enchat.app.ui.components.ConnectionBanner
import com.enchat.app.ui.components.E2eeBadge
import com.enchat.app.ui.components.E2eeIntroDialog
import com.enchat.app.ui.vm.ChatViewModel
import com.enchat.clientcore.ConnectionStateMachine
import com.enchat.protocol.Protocol

/**
 * 聊天页。
 *
 * 右上角「设置」菜单里的**启用高级加密聊天**开关，就是需求里开启端到端加密的入口：
 * 打开后会为此好友额外建立端到端会话（会话列表随之多出一条），
 * 首次进入该端到端会话时弹出带锁图标的说明弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    title: String,
    mode: Int,
    localId: Long,
    peerUid: Long,
    banner: ConnectionStateMachine.Display,
    onBack: () -> Unit
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val e2eeEnabled by vm.e2eeEnabled.collectAsStateWithLifecycle()
    val showIntro by vm.showIntro.collectAsStateWithLifecycle()
    val safetyNumber by vm.safetyNumber.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    LaunchedEffect(localId, peerUid, mode) { vm.bind(localId, peerUid, mode) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); vm.clearError() }
    }

    if (showIntro) {
        E2eeIntroDialog(
            peerName = title,
            onDismiss = { vm.dismissIntro() },
            onViewSafetyNumber = { vm.dismissIntro(); vm.loadSafetyNumber() }
        )
    }

    if (safetyNumber != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.clearSafetyNumber() },
            icon = { Icon(Icons.Default.Lock, contentDescription = null) },
            title = { Text("安全数字") },
            text = {
                Column {
                    Text("与对方当面核对下面这串数字，一致即说明没有被中间人替换密钥。")
                    Text(
                        text = safetyNumber ?: "",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.clearSafetyNumber() }) { Text("关闭") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title)
                            if (mode == Protocol.ENCRYPTION_MODE_E2EE) {
                                E2eeBadge(modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "聊天设置")
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("启用高级加密聊天") },
                                trailingIcon = {
                                    Switch(checked = e2eeEnabled, onCheckedChange = null)
                                },
                                onClick = {
                                    menuExpanded = false
                                    vm.setE2eeEnabled(!e2eeEnabled)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("核对安全数字") },
                                onClick = { menuExpanded = false; vm.loadSafetyNumber() }
                            )
                        }
                    }
                )
                ConnectionBanner(display = banner)
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (mode == Protocol.ENCRYPTION_MODE_E2EE) "端到端加密消息" else "输入消息")
                    },
                    maxLines = 4
                )
                IconButton(
                    onClick = { vm.send(draft); draft = "" },
                    enabled = draft.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "发送")
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { msg -> MessageBubble(msg) }
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val isOut = msg.outgoing
    val bubbleColor = if (isOut) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isOut) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .align(if (isOut) Alignment.CenterEnd else Alignment.CenterStart)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    // 端到端消息若解不开，text 为 null，显示占位而不是空白气泡
                    text = msg.text ?: "🔒 端到端加密消息（无法解密）",
                    color = if (msg.text == null) MaterialTheme.colorScheme.onSurfaceVariant else textColor
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (msg.ciphertextB64 != null) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "端到端加密",
                            modifier = Modifier.padding(end = 4.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = stateLabel(msg.state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun stateLabel(state: Int): String = when (state) {
    0 -> "发送中…"
    2 -> "发送失败"
    else -> ""
}
