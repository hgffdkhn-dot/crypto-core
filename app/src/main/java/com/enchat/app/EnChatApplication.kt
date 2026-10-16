package com.enchat.app

import android.app.Application
import com.enchat.app.di.AppContainer

class EnChatApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
