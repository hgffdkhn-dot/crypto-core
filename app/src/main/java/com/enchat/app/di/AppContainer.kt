package com.enchat.app.di

import android.content.Context
import com.enchat.app.BuildConfig
import com.enchat.app.data.KeystoreVault
import com.enchat.app.data.SettingsStore
import com.enchat.app.data.local.AppDatabase
import com.enchat.app.data.remote.ApiClient
import com.enchat.app.data.remote.WsClient
import com.enchat.app.repo.ChatRepository
import com.enchat.app.repo.NetworkMonitor
import com.enchat.app.ui.vm.AppViewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 手写依赖容器（未引入 Koin/Hilt）。
 *
 * 目的很实际：本项目模块不多，Hilt 带来的 kapt/注解处理器会显著拖慢 Android 构建，
 * 而收益只是省掉十来行构造代码。等模块变多再引入 DI 框架不迟。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsStore(appContext)
    val vault = KeystoreVault(appContext)
    val database = AppDatabase.get(appContext)
    val networkMonitor = NetworkMonitor(appContext)

    /**
     * 服务器地址：默认取 BuildConfig，设置页修改后异步同步到这里。
     *
     * 用 volatile 字段缓存是刻意的：OkHttp 的 hostProvider 是同步回调，
     * 若在里面 runBlocking 读 DataStore，主线程上有概率被阻塞。
     */
    @Volatile
    private var currentHost: String = BuildConfig.DEFAULT_HOST

    private fun host(): String = currentHost

    init {
        // 用 Default 派发器在后台收集，避免主线程 I/O
        settings.host.launchInScope { currentHost = it }
    }

    val repository: ChatRepository by lazy {
        ChatRepository(
            settings = settings,
            vault = vault,
            db = database,
            networkMonitor = networkMonitor,
            wsFactory = {
                WsClient(
                    hostProvider = { host() },
                    tokenProvider = { vault.getString("access_token") },
                    staticKeysProvider = { WsClient.pinnedKeys() }
                )
            },
            apiFactory = {
                ApiClient(
                    baseUrl = host(),
                    tokenProvider = { vault.getString("access_token") }
                )
            },
            scope = appScope
        )
    }

    val viewModelFactory: AppViewModelFactory by lazy {
        AppViewModelFactory(repository, settings)
    }

    fun start() {
        repository.start()
    }

    /** 在指定作用域里收集一个 Flow（简化版，避免为一行代码引入额外依赖）。 */
    private fun <T> Flow<T>.launchInScope(scope: CoroutineScope = appScope, block: suspend (T) -> Unit) {
        this.onEach { block(it) }.launchIn(scope)
    }
}
