package com.arkpet.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.arkpet.App
import com.arkpet.overlay.PetOverlayService

/** 开机自启：拉起悬浮窗 + 重连通道 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.canDrawOverlays(context)) {
            // 启动服务（WsClient 会在 PetOverlayService.onCreate 中创建）
            context.startForegroundService(Intent(context, PetOverlayService::class.java))
        }
    }
}
