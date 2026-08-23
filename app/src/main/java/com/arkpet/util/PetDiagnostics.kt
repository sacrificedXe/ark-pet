package com.arkpet.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.arkpet.accessibility.PetAccessibilityService

/**
 * 自检：把「桌宠打不开」的所有可能原因一次性列清楚，直接显示在主界面。
 * 之前排查两小时全花在猜权限，因为 App 自己不说话。
 */
object PetDiagnostics {

    data class Item(val name: String, val ok: Boolean, val detail: String)

    fun run(ctx: Context): List<Item> {
        val list = mutableListOf<Item>()

        list.add(
            Item(
                "悬浮窗权限",
                Settings.canDrawOverlays(ctx),
                if (Settings.canDrawOverlays(ctx)) "已授予" else "未授予 → 点「授予权限」"
            )
        )

        val accOn = try {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (_: Exception) { false }
        val svcList = try {
            Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
        } catch (_: Exception) { "" }
        val target = "${ctx.packageName}/${PetAccessibilityService::class.java.name}"
        val listed = svcList.split(':').any { it.equals(target, ignoreCase = true) }
        list.add(Item("无障碍总开关", accOn, if (accOn) "已开" else "未开"))
        list.add(
            Item(
                "无障碍已注册本应用",
                listed,
                if (listed) "已在服务列表" else "不在列表 → 远程控制/点击类工具不可用"
            )
        )
        list.add(
            Item(
                "无障碍服务已连接",
                PetAccessibilityService.instance != null,
                if (PetAccessibilityService.instance != null) "onServiceConnected 已触发" else "实例为空"
            )
        )

        // 只影响远程控制，不影响桌宠本体显示，单独标注
        val amRunning = try {
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.isEnabled
        } catch (_: Exception) { false }
        list.add(Item("AccessibilityManager.isEnabled", amRunning, amRunning.toString()))

        val assetOk = try {
            ctx.assets.open("pet/base_Default.webp").close(); true
        } catch (_: Exception) { false }
        list.add(Item("核心动画资源", assetOk, if (assetOk) "pet/base_Default.webp 可读" else "缺失，需重打包"))

        val url = ctx.getSharedPreferences("arkpet", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        list.add(
            Item(
                "服务器地址",
                url.isNotBlank(),
                if (url.isBlank()) "未填写 → 桌宠仍能显示，但无法远程控制" else url
            )
        )

        val svc = com.arkpet.overlay.PetOverlayService.instance
        list.add(
            Item(
                "桌宠服务",
                svc != null,
                if (svc != null) "运行中" else "未运行 → 点「启动桌宠」"
            )
        )
        if (svc != null) {
            val wsState = svc.wsState()
            list.add(Item("WS 长连接", wsState == "已连接", wsState))
        }

        val installPerm = try {
            ctx.packageManager.canRequestPackageInstalls()
        } catch (_: Exception) { false }
        list.add(Item("允许安装未知应用", installPerm, if (installPerm) "已授予" else "未授予 → 自动更新装不上"))

        val ver = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (code ${pi.longVersionCodeCompat()})"
        } catch (_: PackageManager.NameNotFoundException) { "未知" }
        list.add(Item("当前版本", true, ver))

        list.add(Item("日志文件", true, PetLog.path()))

        return list
    }

    fun format(items: List<Item>): String = buildString {
        items.forEach { append(if (it.ok) "✓ " else "✗ ").append(it.name).append("：").append(it.detail).append('\n') }
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}
