package com.arkpet.mcp.tools

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * 应用工具：打开/关闭/列出应用
 * 打开优先显式 Intent，失败回退隐式（需确认唯一匹配）
 */
class AppTools(private val ctx: Context) {

    fun open(p: JSONObject): JSONObject {
        val pkg = p.optString("pkg")
        if (pkg.isEmpty()) return err("need pkg")
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                ok("launched", pkg)
            } else {
                err("no_launch_intent: $pkg")
            }
        } catch (e: Exception) {
            err(e.message ?: "start_failed")
        }
    }

    fun close(p: JSONObject): JSONObject {
        val pkg = p.optString("pkg")
        if (pkg.isEmpty()) return err("need pkg")
        return try {
            ctx.startActivity(
                Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            // 无 root 时无法强杀第三方应用，仅回桌面；root 后可用 am force-stop
            ok("closed_to_home", pkg)
        } catch (e: Exception) {
            err(e.message ?: "close_failed")
        }
    }

    fun list(p: JSONObject): JSONObject {
        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .take(p.optInt("limit", 50))
            .map { it.packageName }
        return JSONObject().put("status", "ok").put("data", JSONObject().put("apps", apps))
    }

    private fun ok(action: String, pkg: String) =
        JSONObject().put("status", "ok").put("data", JSONObject().put("action", action).put("pkg", pkg))
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
