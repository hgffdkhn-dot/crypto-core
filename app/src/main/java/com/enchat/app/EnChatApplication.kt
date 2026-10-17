package com.enchat.app

import android.app.Application
import com.enchat.app.di.AppContainer

class EnChatApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()

        container = AppContainer(this)

        // 启动自检：本地密钥存储若处于不可恢复状态，先清掉再启动。
        // 旧版本退出登录会留下坏状态（Keystore 条目已删但缓存仍指向它），
        // 不处理的话装上新版本照样闪退，只能卸载重装。这里让它自愈到"需重新登录"。
        container.vault.recoverIfBroken()

        container.start()
    }
}
