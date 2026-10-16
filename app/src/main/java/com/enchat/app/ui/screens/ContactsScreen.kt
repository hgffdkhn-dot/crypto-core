package com.enchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.ui.components.Avatar
import com.enchat.app.ui.components.ConnectionBanner
import com.enchat.app.ui.components.EmptyHint
import com.enchat.app.ui.vm.ContactsViewModel
import com.enchat.clientcore.ConnectionStateMachine
import com.enchat.protocol.Protocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    vm: ContactsViewModel,
    banner: ConnectionStateMachine.Display,
    onOpenChat: (peerUid: Long, title: String, avatarUrl: String) -> Unit,
    onBack: () -> Unit
) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val requests by vm.requests.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("联系人") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Default.PersonAdd, contentDescription = null) }
                    },
                    actions = {
                        IconButton(onClick = { showAdd = true }) {
                            Icon(Icons.Default.Add, contentDescription = "添加好友")
                        }
                    }
                )
                ConnectionBanner(display = banner)
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (requests.isNotEmpty()) {
                item {
                    Text(
                        text = "好友请求（${requests.size}）",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                items(requests, key = { it.requestId }) { req ->
                    ListItem(
                        leadingContent = { Avatar(url = req.fromAvatarUrl, name = req.fromNickname, size = 40) },
                        headlineContent = { Text(req.fromNickname) },
                        supportingContent = { Text(req.message.ifBlank { "请求添加你为好友" }, maxLines = 1) },
                        trailingContent = {
                            IconButton(onClick = { vm.accept(req.requestId) }) {
                                Icon(Icons.Default.Check, contentDescription = "接受",
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = { vm.reject(req.requestId) }) {
                                Icon(Icons.Default.Close, contentDescription = "拒绝")
                            }
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }

            item {
                Text("好友（${contacts.size}）", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(16.dp))
            }

            if (contacts.isEmpty()) {
                item { EmptyHint("还没有好友，点右上角 + 按 UID 添加") }
            } else {
                items(contacts, key = { it.uid }) { c ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenChat(c.uid, c.nickname, c.avatarUrl) },
                        leadingContent = { Avatar(url = c.avatarUrl, name = c.nickname, size = 40) },
                        headlineContent = { Text(c.nickname.ifBlank { Protocol.formatUid(c.uid) }) },
                        supportingContent = {
                            Text(
                                buildString {
                                    append("UID ")
                                    append(Protocol.formatUid(c.uid))
                                    if (c.signature.isNotBlank()) append(" · ").append(c.signature)
                                },
                                maxLines = 1
                            )
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }

        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }

    if (showAdd) {
        AddFriendDialog(
            uidText = vm.searchUid.collectAsStateWithLifecycle().value,
            found = found,
            onQueryChange = vm::onSearchChange,
            onSearch = vm::search,
            onSend = { uid, msg -> vm.addFriend(uid, msg); showAdd = false },
            onDismiss = { showAdd = false }
        )
    }
}

@Composable
private fun AddFriendDialog(
    uidText: String,
    found: Pair<Long, String>?,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSend: (Long, String) -> Unit,
    onDismiss: () -> Unit
) {
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加好友") },
        text = {
            Column {
                OutlinedTextField(
                    value = uidText,
                    onValueChange = onQueryChange,
                    label = { Text("对方 UID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = onSearch) { Text("查找") }
                if (found != null) {
                    Text(
                        text = "找到用户：${found.second}（UID ${Protocol.formatUid(found.first)}）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("验证消息（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = found != null,
                onClick = { if (found != null) onSend(found.first, message) }
            ) { Text("发送好友请求") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
