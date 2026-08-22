package com.arkpet.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.arkpet.accessibility.PetAccessibilityService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 系统工具：返回键/主页/电量/通知/剪贴板
 */
class SystemTools(private val ctx: Context) {

    fun back(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance ?: return err("accessibility_not_connected")
        return if (runBlocking { s.back() }) ok() else err("failed")
    }

    fun home(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance ?: return err("accessibility_not_connected")
        return if (runBlocking { s.home() }) ok() else err("failed")
    }

    fun battery(p: JSONObject): JSONObject {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return JSONObject().put("status", "ok").put("data", JSONObject().put("level", level))
    }

    fun notify(p: JSONObject): JSONObject {
        val text = p.optString("text", "")
        if (text.isEmpty()) return err("need text")
        return try {
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, Intent(), android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = android.app.NotificationChannel(
                "arkpet_cmd", "指令通知", android.app.NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
            nm.notify(1002, android.app.Notification.Builder(ctx, "arkpet_cmd")
                .setContentTitle("初雪")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build())
            ok()
        } catch (e: Exception) { err(e.message ?: "notify_failed") }
    }

    fun clipboard(p: JSONObject): JSONObject {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return if (p.has("set")) {
            cm.setPrimaryClip(ClipData.newPlainText("arkpet", p.optString("set")))
            ok()
        } else {
            val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
            JSONObject().put("status", "ok").put("data", JSONObject().put("text", text))
        }
    }

    private fun ok() = JSONObject().put("status", "ok")
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
