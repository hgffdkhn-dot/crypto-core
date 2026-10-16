package com.enchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.ui.vm.AuthViewModel

/**
 * 登录页：用户名 + 口令。
 *
 * 口令已放宽为字母/数字/符号，因此键盘不做数字限制；
 * 注册页会实时显示强度条（由 PasswordPolicy 给出 0–4 分）。
 */
@Composable
fun LoginScreen(
    vm: AuthViewModel,
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit
) {
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(done) { if (done) onLoggedIn() }

    AuthScaffold(title = "登录 EnChat") {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; vm.clearError() },
            label = { Text("用户名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; vm.clearError() },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { vm.login(username.trim(), password) },
            enabled = !busy && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text("登录")
        }
        TextButton(onClick = onGoRegister) { Text("还没有账号？注册") }
    }
}

@Composable
fun RegisterScreen(
    vm: AuthViewModel,
    onRegistered: () -> Unit,
    onGoLogin: () -> Unit
) {
    val error by vm.error.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val done by vm.done.collectAsStateWithLifecycle()
    val policy by vm.passwordState.collectAsStateWithLifecycle()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    LaunchedEffect(done) { if (done) onRegistered() }

    AuthScaffold(title = "注册 EnChat") {
        OutlinedTextField(
            value = username,
            onValueChange = { username = it; vm.onPasswordChange(password, it.trim()) },
            label = { Text("用户名（字母开头，3–32 位）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("昵称（可留空）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                vm.onPasswordChange(it, username.trim().ifBlank { null })
            },
            label = { Text("密码（字母/数字/符号，8–64 位）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        // 强度条：分数来自 PasswordPolicy，纯逻辑，已在单元测试覆盖
        if (password.isNotEmpty()) {
            Column {
                LinearProgressIndicator(
                    progress = { (policy.score + 1) / 5f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = strengthLabel(policy.score),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        policy.problems.forEach { problem ->
            Text(
                text = "· $problem",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (error != null) {
            Text(text = error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { vm.register(username.trim(), password, nickname.trim()) },
            enabled = !busy && policy.accepted && username.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (busy) CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
            else Text("注册")
        }
        TextButton(onClick = onGoLogin) { Text("已有账号？登录") }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.height(14.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text(
                text = "注册后可在聊天设置中开启端到端加密",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun strengthLabel(score: Int): String = when (score) {
    0 -> "强度：不合格"
    1 -> "强度：弱"
    2 -> "强度：中"
    3 -> "强度：强"
    else -> "强度：很强"
}

@Composable
private fun AuthScaffold(title: String, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.headlineMedium)
            content()
        }
    }
}
