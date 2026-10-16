package com.enchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.ui.components.Avatar
import com.enchat.app.ui.components.ConnectionBanner
import com.enchat.app.ui.components.E2eeBadge
import com.enchat.app.ui.components.EmptyHint
import com.enchat.app.ui.vm.ConversationsViewModel
import com.enchat.clientcore.ConnectionStateMachine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    vm: ConversationsViewModel,
    banner: ConnectionStateMachine.Display,
    pendingOutbox: Int,
    onOpenChat: (localId: Long, peerUid: Long, mode: Int, title: String) -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("EnChat") },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "设置")
                        }
                    }
                )
                // 顶部连接状态条：连接中… / 正在等待网络…
                ConnectionBanner(display = banner, pendingOutbox = pendingOutbox)
            }
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = true, onClick = {}, icon = { Icon(Icons.Default.Chat, null) }, label = { Text("聊天") })
                NavigationBarItem(selected = false, onClick = onOpenContacts, icon = { Icon(Icons.Default.Person, null) }, label = { Text("联系人") })
                NavigationBarItem(selected = false, onClick = onOpenSettings, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("设置") })
            }
        }
    ) { padding ->
        val filtered = if (query.isBlank()) entries
        else entries.filter { it.title.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true) }

        if (filtered.isEmpty()) {
            EmptyHint(
                text = if (entries.isEmpty()) "还没有会话，去「联系人」添加好友吧" else "没有匹配的会话",
                modifier = Modifier.padding(padding).fillMaxSize()
            )
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            items(filtered, key = { "${it.peerUid}:${it.mode}" }) { entry ->
                ListItem(
                    modifier = Modifier.clickable {
                        scope.launch {
                            vm.open(entry) { localId ->
                                onOpenChat(localId, entry.peerUid, entry.mode, entry.title)
                            }
                        }
                    },
                    leadingContent = { Avatar(url = entry.avatarUrl, name = entry.title) },
                    headlineContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            if (entry.mode == com.enchat.protocol.Protocol.ENCRYPTION_MODE_E2EE) {
                                E2eeBadge(modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    },
                    supportingContent = { Text(entry.preview.ifBlank { "暂无消息" }, maxLines = 1) },
                    trailingContent = {
                        if (entry.unread > 0) {
                            Text(entry.unread.toString(), color = MaterialTheme.colorScheme.primary)
                        }
                    }
                )
                HorizontalDivider(thickness = 0.5.dp)
            }
        }
    }
}

/** 群聊入口占位（本轮群聊服务端已支持，客户端建群流程在下一轮补齐）。 */
@Composable
fun GroupEntryHint(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(20.dp))
        Text("群聊创建入口将在下一轮开放（服务端已支持）", modifier = Modifier.padding(start = 8.dp))
    }
}
