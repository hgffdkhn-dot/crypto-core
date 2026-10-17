package com.enchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.di.AppContainer
import com.enchat.app.repo.SessionState
import com.enchat.app.ui.AppNav
import com.enchat.app.ui.theme.EnChatTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (applicationContext as EnChatApplication).container
        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle(initialValue = 0)
            EnChatTheme(themeMode = themeMode) {
                // ⚠️ 关键：启动目的地不能写死成登录页。
                // 本地会话仍然存在时（没退出过账号）应直接进主界面 ——
                // 这与主流 IM 一致：退出 App 不等于退出登录。
                // 读本地凭据是异步 I/O，所以先等 SessionState 变为确定值，
                // 否则会闪一下主界面再跳登录页，或反过来每次都要重新登录。
                val sessionState by container.repository.sessionState
                    .collectAsStateWithLifecycle()

                when (sessionState) {
                    SessionState.UNKNOWN -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        val banner by container.repository.banner.collectAsStateWithLifecycle()
                        val pending by container.repository.pendingOutbox.collectAsStateWithLifecycle()
                        AppNav(
                            factory = container.viewModelFactory,
                            banner = banner,
                            pendingOutbox = pending,
                            startLoggedIn = sessionState == SessionState.LOGGED_IN,
                            sessionState = sessionState
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 回到前台：重新探测网络，有网但未连上时立即重连
        container.repository.onNetworkBack()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { container.repository.close() }
    }
}
