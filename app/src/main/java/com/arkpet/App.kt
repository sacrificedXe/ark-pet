package com.arkpet

import android.app.Application
import android.content.SharedPreferences
import android.util.Log
import com.arkpet.net.WsClient
import com.arkpet.maa.MaaBridge
import com.arkpet.updater.UpdateChecker

class App : Application() {

    companion object {
        lateinit var instance: App
        private const val TAG = "App"
        private const val PREFS = "arkpet"
    }

    // 全局单例
    private var wsClient: WsClient? = null
    private var maaBridge: MaaBridge? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        initMaaBridge()
        checkUpdate()
    }

    private fun initMaaBridge() {
        MaaBridge.init(this); maaBridge = MaaBridge
        // WsClient 将在 PetOverlayService 中创建并注入
    }

    /** 获取 WsClient（由 PetOverlayService 创建后注入） */
    fun getWsClient(): WsClient? = wsClient

    fun setWsClient(client: WsClient) {
        wsClient = client
        maaBridge?.setWsClient(client)
    }

    /** 获取 MaaBridge（已注入 WsClient） */
    fun getMaaBridge(): MaaBridge? = maaBridge

    private fun checkUpdate() {
        val sp = getSharedPreferences(PREFS, MODE_PRIVATE)
        val serverUrl = sp.getString("server_url", "") ?: return
        if (serverUrl.isBlank()) return
        UpdateChecker(this).check(serverUrl) { json ->
            Log.i(TAG, "Update available: ${json.optString("version")}")
            val url = json.optString("url")
            if (url.isNotBlank()) {
                UpdateChecker(this).downloadAndInstall(url, {}, { success ->
                    Log.i(TAG, "Update install result: $success")
                })
            }
        }
    }
}
