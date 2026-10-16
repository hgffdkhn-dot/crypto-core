package com.enchat.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.enchat.app.ui.components.Avatar
import com.enchat.app.ui.vm.SettingsViewModel

/**
 * 设置页：对标主流 IM 的常用分组（账号 / 消息通知 / 聊天 / 隐私与安全 / 存储 / 服务器 / 关于）。
 *
 * 注：头像上传入口已预留（选择图片 → 压缩 → base64 上传），
 * 具体的图片选择器（Photo Picker）接入在下一轮补齐。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val nickname by vm.nickname.collectAsStateWithLifecycle()
    val signature by vm.signature.collectAsStateWithLifecycle()
    val avatarUrl by vm.avatarUrl.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val notifyEnabled by vm.notifyEnabled.collectAsStateWithLifecycle()
    val notifySound by vm.notifySound.collectAsStateWithLifecycle()
    val notifyPreview by vm.notifyPreview.collectAsStateWithLifecycle()
    val readReceipt by vm.readReceipt.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val enterToSend by vm.enterToSend.collectAsStateWithLifecycle()
    val autoDownload by vm.autoDownload.collectAsStateWithLifecycle()
    val keepDays by vm.keepDays.collectAsStateWithLifecycle()
    val host by vm.host.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var showProfile by remember { mutableStateOf(false) }
    var showTheme by remember { mutableStateOf(false) }
    var showHost by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // ---- 账号 ----
            SectionTitle("账号")
            ListItem(
                modifier = Modifier.clickable { showProfile = true },
                leadingContent = { Avatar(url = avatarUrl, name = nickname, size = 44) },
                headlineContent = { Text(nickname.ifBlank { "未设置昵称" }) },
                supportingContent = { Text(signature.ifBlank { "点击修改昵称、签名与头像" }, maxLines = 1) }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 消息通知 ----
            SectionTitle("消息通知")
            SwitchItem("接收新消息通知", notifyEnabled, vm::setNotifyEnabled)
            SwitchItem("通知声音", notifySound, vm::setNotifySound)
            SwitchItem("通知显示内容预览", notifyPreview, vm::setNotifyPreview)

            // ---- 聊天 ----
            SectionTitle("聊天")
            SwitchItem("发送已读回执", readReceipt, vm::setReadReceipt)
            SwitchItem("回车键发送消息", enterToSend, vm::setEnterToSend)
            ListItem(
                modifier = Modifier.clickable { vm.setFontScale(if (fontScale >= 130) 85 else fontScale + 15) },
                headlineContent = { Text("字体大小") },
                supportingContent = { Text("当前 $fontScale%") }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 隐私与安全 ----
            SectionTitle("隐私与安全")
            ListItem(
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary)
                        Text("端到端加密", modifier = Modifier.padding(start = 6.dp))
                    }
                },
                supportingContent = { Text("在好友聊天页右上角设置中单独开启；开启后服务器只中转密文") }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 存储 ----
            SectionTitle("存储与数据")
            SwitchItem("自动下载图片", autoDownload, vm::setAutoDownload)
            ListItem(
                modifier = Modifier.clickable { vm.setKeepDays(if (keepDays >= 365) 7 else keepDays * 2 + 1) },
                headlineContent = { Text("本地消息保留") },
                supportingContent = { Text("当前 $keepDays 天（到期自动清理）") }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 外观 ----
            SectionTitle("外观")
            ListItem(
                modifier = Modifier.clickable { showTheme = true },
                headlineContent = { Text("主题") },
                supportingContent = { Text(themeLabel(themeMode)) }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 服务器 ----
            SectionTitle("服务器")
            ListItem(
                modifier = Modifier.clickable { showHost = true },
                headlineContent = { Text("服务器地址") },
                supportingContent = { Text(host.ifBlank { "未设置" }, maxLines = 1) }
            )
            HorizontalDivider(thickness = 0.5.dp)

            // ---- 关于 ----
            SectionTitle("关于")
            ListItem(modifier = Modifier.clickable { showAbout = true }, headlineContent = { Text("关于 EnChat") })
            ListItem(
                modifier = Modifier.clickable { vm.logout(onLoggedOut) },
                headlineContent = { Text("退出登录", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("退出将清除本设备上的端到端会话密钥") }
            )
        }
    }

    if (showProfile) {
        ProfileDialog(
            nickname = nickname,
            signature = signature,
            onSave = { n, s -> vm.saveProfile(n, s); showProfile = false },
            onDismiss = { showProfile = false }
        )
    }
    if (showTheme) {
        ThemeDialog(current = themeMode, onPick = { vm.setThemeMode(it); showTheme = false }, onDismiss = { showTheme = false })
    }
    if (showHost) {
        HostDialog(current = host, onSave = { vm.setHost(it); showHost = false }, onDismiss = { showHost = false })
    }
    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("关于 EnChat") },
            text = {
                Text(
                    "EnChat 0.2.0\n" +
                        "默认采用「客户端↔服务器」传输层加密；\n" +
                        "可在好友聊天设置中单独开启端到端加密，届时服务器只中转密文。"
                )
            },
            confirmButton = { TextButton(onClick = { showAbout = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChanged) }
    )
    HorizontalDivider(thickness = 0.5.dp)
}

@Composable
private fun ProfileDialog(
    nickname: String,
    signature: String,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var n by remember { mutableStateOf(nickname) }
    var s by remember { mutableStateOf(signature) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑资料") },
        text = {
            Column {
                OutlinedTextField(value = n, onValueChange = { n = it }, label = { Text("昵称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = s, onValueChange = { s = it }, label = { Text("个性签名") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(n, s) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun ThemeDialog(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题") },
        text = {
            Column {
                listOf(0 to "跟随系统", 1 to "浅色", 2 to "深色").forEach { (value, label) ->
                    ListItem(
                        modifier = Modifier.clickable { onPick(value) },
                        headlineContent = { Text(label) },
                        trailingContent = { if (current == value) Text("✓", color = MaterialTheme.colorScheme.primary) }
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

@Composable
private fun HostDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务器地址") },
        text = {
            Column {
                OutlinedTextField(value = v, onValueChange = { v = it }, label = { Text("例如 http://10.0.2.2:8080") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "⚠️ 服务器更换后，客户端内置的公钥固定（pinning）必须同步更新，否则无法完成握手。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(v) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun themeLabel(mode: Int): String = when (mode) {
    1 -> "浅色"
    2 -> "深色"
    else -> "跟随系统"
}
