package com.enchat.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enchat.app.di.AppContainer
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
                val banner by container.repository.banner.collectAsStateWithLifecycle()
                val pending by container.repository.pendingOutbox.collectAsStateWithLifecycle()
                AppNav(factory = container.viewModelFactory, banner = banner, pendingOutbox = pending)
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
