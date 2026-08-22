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
            context.startForegroundService(Intent(context, PetOverlayService::class.java))
            App.instance?.channel?.connect()
        }
    }
}
