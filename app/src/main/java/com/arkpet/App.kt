package com.arkpet

import android.app.Application
import com.arkpet.maa.MaaBridge
import com.arkpet.net.WsClient
import com.arkpet.updater.UpdateChecker
import com.arkpet.util.PetLog

class App : Application() {

    companion object {
        lateinit var instance: App
            private set
        private const val TAG = "App"
        private const val PREFS = "arkpet"
    }

    @Volatile
    private var wsClient: WsClient? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 第一件事：开日志 + 装崩溃钩子。后面任何一步炸了都得有痕迹。
        PetLog.init(this)
        PetLog.i(TAG, "onCreate: model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}")

        runCatching { MaaBridge.init(this) }
            .onFailure { PetLog.e(TAG, "MaaBridge.init 失败", it) }

        runCatching { checkUpdate() }
            .onFailure { PetLog.e(TAG, "checkUpdate 失败", it) }
    }

    fun getWsClient(): WsClient? = wsClient

    fun setWsClient(client: WsClient) {
        wsClient = client
        runCatching { MaaBridge.setWsClient(client) }
            .onFailure { PetLog.e(TAG, "MaaBridge.setWsClient 失败", it) }
    }

    fun getMaaBridge(): MaaBridge = MaaBridge

    /** 冷启动静默查更新：只在已配置服务器地址时进行 */
    private fun checkUpdate() {
        val serverUrl = getSharedPreferences(PREFS, MODE_PRIVATE).getString("server_url", "") ?: ""
        if (serverUrl.isBlank()) {
            PetLog.i(TAG, "跳过冷启动查更新：server_url 未配置")
            return
        }
        val checker = UpdateChecker(this)
        checker.check(serverUrl) { json ->
            val ver = json.optString("version")
            val url = json.optString("url")
            PetLog.i(TAG, "发现新版本 $ver url=$url")
            if (url.isNotBlank()) {
                checker.downloadAndInstall(url, {}) { ok ->
                    PetLog.i(TAG, "更新下载结果: $ok")
                }
            }
        }
    }
}
