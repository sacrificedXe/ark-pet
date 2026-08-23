package com.arkpet.mcp.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import com.arkpet.accessibility.PetAccessibilityService
import com.arkpet.util.PetLog
import org.json.JSONObject

/**
 * 系统工具：返回 / 主页 / 最近任务 / 电量 / 通知 / 剪贴板 / 日志。
 * 去掉 runBlocking（无障碍方法已改阻塞式），通知渠道只建一次。
 */
class SystemTools(private val ctx: Context) {

    companion object {
        private const val TAG = "SystemTools"
        private const val CHANNEL_ID = "arkpet_cmd"
        private const val NOTIF_ID = 1003
    }

    private val notConnected = "无障碍服务未连接：设置 → 无障碍 → 开启「初雪桌宠」"

    fun back(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance ?: return err(notConnected)
        return if (s.back()) ok() else err("action_failed")
    }

    fun home(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance ?: return err(notConnected)
        return if (s.home()) ok() else err("action_failed")
    }

    fun recents(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance ?: return err(notConnected)
        return if (s.recents()) ok() else err("action_failed")
    }

    fun battery(p: JSONObject): JSONObject {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            JSONObject().put("status", "ok").put(
                "data",
                JSONObject().put("level", level).put("charging", charging)
            )
        } catch (e: Exception) {
            err("battery_failed: ${e.message}")
        }
    }

    fun notify(p: JSONObject): JSONObject {
        val text = p.optString("text")
        if (text.isEmpty()) return err("need text")
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(
                        CHANNEL_ID, "远程消息",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
            val builder = if (android.os.Build.VERSION.SDK_INT >= 26)
                android.app.Notification.Builder(ctx, CHANNEL_ID)
            else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            nm.notify(
                NOTIF_ID,
                builder.setContentTitle(p.optString("title", "初雪"))
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setAutoCancel(true)
                    .build()
            )
            ok()
        } catch (e: Exception) {
            PetLog.e(TAG, "notify 失败", e)
            err("notify_failed: ${e.message}")
        }
    }

    fun clipboard(p: JSONObject): JSONObject {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (p.has("set")) {
                cm.setPrimaryClip(ClipData.newPlainText("arkpet", p.optString("set")))
                ok()
            } else {
                // Android 10+ 后台读剪贴板会被系统拦，返回空是正常现象
                val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.text?.toString().orEmpty()
                JSONObject().put("status", "ok").put("data", JSONObject().put("text", text))
            }
        } catch (e: Exception) {
            err("clipboard_failed: ${e.message}")
        }
    }

    fun log(p: JSONObject): JSONObject = JSONObject().put("status", "ok")
        .put("data", JSONObject().put("log", PetLog.tail(p.optInt("lines", 60))))

    private fun ok() = JSONObject().put("status", "ok")
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
