package com.arkpet.maa

import android.content.Context
import android.content.pm.PackageManager
import com.arkpet.net.WsClient
import org.json.JSONObject
import rikka.shizuku.Shizuku

/**
 * MAA-Meow 桥接：外部触发 MAA-Meow 执行任务配置
 * 双通道执行：优先 root(su)，其次 Shizuku（无需 root，不碰 bootloader）
 * 事件回调 → WsClient.reportMaa() 上报 sense
 */
class MaaBridge(private val ctx: Context) {

    private var wsClient: WsClient? = null

    fun setWsClient(client: WsClient) { wsClient = client }

    companion object {
        const val PKG = "com.aliothmoon.maameow"
        const val ACTION_LAUNCH_PROFILE = "com.aliothmoon.maameow.action.LAUNCH_PROFILE"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_FORCE_START = "extra_force_start"
    }

    /** 检查 MAA 引擎可用性 */
    fun checkAvailable(): JSONObject {
        val installed = try {
            ctx.packageManager.getPackageInfo(PKG, 0) != null
        } catch (_: Exception) { false }
        val root = isRooted()
        val shizuku = isShizukuReady()
        return JSONObject()
            .put("available", installed)
            .put("maa_installed", installed)
            .put("root", root)
            .put("shizuku", shizuku)
            .put("reason", when {
                !installed -> "未安装 MAA-Meow (com.aliothmoon.maameow)"
                !root && !shizuku -> "MAA-Meow 已装，但需 Shizuku 授权（Shizuku App 授权管理里给初雪桌宠授权）"
                else -> "MAA-Meow 已安装，通道就绪（root/Shizuku）"
            })
    }

    /** 启动指定任务配置 */
    fun start(profileId: String, forceStart: Boolean): JSONObject {
        if (profileId.isBlank()) {
            return JSONObject().put("status", "error").put("error", "profile_id_required")
        }
        reportMaa("starting", "profile=$profileId force=$forceStart")
        var cmd = "am start -a $ACTION_LAUNCH_PROFILE -n $PKG/.MainActivity" +
                " --es $EXTRA_PROFILE_ID \"$profileId\""
        if (forceStart) cmd += " --ez $EXTRA_FORCE_START true"
        val (ok, out) = exec(cmd)
        reportMaa(if (ok) "started" else "start_failed", out)
        return JSONObject().put("status", if (ok) "ok" else "error").put("output", out)
    }

    /** 强制停止 MAA-Meow */
    fun stop(): JSONObject {
        reportMaa("stopping", "")
        val (ok, out) = exec("am force-stop $PKG")
        reportMaa(if (ok) "stopped" else "stop_failed", out)
        return JSONObject().put("status", if (ok) "ok" else "error").put("output", out)
    }

    /** 查询 MAA-Meow 是否在运行 */
    fun status(): JSONObject {
        val (ok, out) = exec("pidof $PKG")
        val running = ok && out.trim().isNotEmpty()
        reportMaa("status_query", if (running) "running pid=$out" else "not_running")
        return JSONObject().put("running", running).put("pid", if (running) out.trim() else "")
    }

    /** 双通道执行：优先 root(su)，其次 Shizuku */
    private fun exec(cmd: String): Pair<Boolean, String> {
        if (isRooted()) {
            val (ok, out) = runSu(cmd)
            if (ok) return Pair(true, out)
        }
        if (isShizukuReady()) {
            val (ok, out) = runShizuku(cmd)
            if (ok) return Pair(true, out)
        }
        return Pair(false, "需要 root 或 Shizuku 授权")
    }

    private fun runSu(cmd: String): Pair<Boolean, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Pair(p.exitValue() == 0, if (out.isNotEmpty()) out else err)
        } catch (e: Exception) {
            Pair(false, "su_failed: ${e.message}")
        }
    }

    private fun runShizuku(cmd: String): Pair<Boolean, String> {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Pair(false, "shizuku_not_granted")
            }
            val m = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            Pair(code == 0, if (out.isNotEmpty()) out else err)
        } catch (e: Exception) {
            Pair(false, "shizuku_failed: ${e.message}")
        }
    }

    private fun isRooted(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) { false }
    }

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    private fun reportMaa(status: String, detail: String) {
        wsClient?.reportMaa(status, detail)
    }
}
