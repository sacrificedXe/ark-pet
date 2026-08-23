package com.arkpet.mcp.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.arkpet.accessibility.PetAccessibilityService
import com.arkpet.util.PetLog
import org.json.JSONObject

/**
 * 应用工具：打开 / 回桌面 / 列出应用。
 * 增补：list 支持关键字过滤并返回应用名（只给包名很难认），close 改为经无障碍回主页
 * （原实现发 ACTION_CLOSE_SYSTEM_DIALOGS，Android 8+ 起对普通应用无效，等于什么都没做）。
 */
class AppTools(private val ctx: Context) {

    companion object { private const val TAG = "AppTools" }

    fun open(p: JSONObject): JSONObject {
        val pkg = p.optString("pkg")
        if (pkg.isEmpty()) return err("need pkg")
        return try {
            val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return err("no_launch_intent: $pkg（未安装或无启动入口）")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            PetLog.i(TAG, "启动应用 $pkg")
            ok("launched", pkg)
        } catch (e: Exception) {
            PetLog.e(TAG, "启动 $pkg 失败", e)
            err("${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun close(p: JSONObject): JSONObject {
        val pkg = p.optString("pkg")
        val s = PetAccessibilityService.instance
            ?: return err("无障碍服务未连接，无法回主页（无 root 也无法强杀应用）")
        return if (s.home()) ok("home", pkg.ifEmpty { "-" })
        else err("home_action_failed")
    }

    fun list(p: JSONObject): JSONObject {
        val pm = ctx.packageManager
        val kw = p.optString("keyword")
        val includeSystem = p.optBoolean("system", false)
        val limit = p.optInt("limit", 50).coerceIn(1, 500)
        val apps = pm.getInstalledApplications(0)
            .filter { includeSystem || (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .mapNotNull { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                if (kw.isNotEmpty() &&
                    !label.contains(kw, true) && !info.packageName.contains(kw, true)
                ) null
                else JSONObject().put("pkg", info.packageName).put("name", label)
            }
            .sortedBy { it.optString("name") }
            .take(limit)
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("count", apps.size).put("apps", apps))
    }

    private fun ok(action: String, pkg: String) = JSONObject().put("status", "ok")
        .put("data", JSONObject().put("action", action).put("pkg", pkg))

    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
