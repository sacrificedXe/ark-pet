package com.arkpet.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.arkpet.overlay.PetOverlayService
import com.arkpet.util.PetLog

/**
 * 开机自启。
 * 注意：SDK 26+ 必须用 startForegroundService，且服务要在 5s 内进前台；
 * 原实现无条件调 startForegroundService，在低版本上会 NoSuchMethodError。
 */
class BootReceiver : BroadcastReceiver() {

    companion object { private const val TAG = "BootReceiver" }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        PetLog.init(context.applicationContext)

        val autoStart = context.getSharedPreferences("arkpet", Context.MODE_PRIVATE)
            .getBoolean("auto_start", true)
        if (!autoStart) { PetLog.i(TAG, "auto_start=false，不自启"); return }

        if (!Settings.canDrawOverlays(context)) {
            PetLog.w(TAG, "开机自启跳过：无悬浮窗权限")
            return
        }
        runCatching {
            val svc = Intent(context, PetOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(svc)
            else context.startService(svc)
            PetLog.i(TAG, "开机自启已拉起服务")
        }.onFailure { PetLog.e(TAG, "开机自启失败", it) }
    }
}
