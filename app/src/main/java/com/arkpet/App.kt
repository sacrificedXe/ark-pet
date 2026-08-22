package com.arkpet

import android.app.Application
import com.arkpet.net.WsClient

class App : Application() {

    companion object {
        var instance: App? = null
            private set
    }

    val channel: WsClient by lazy { WsClient(this, "ws://127.0.0.1:9100") }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}