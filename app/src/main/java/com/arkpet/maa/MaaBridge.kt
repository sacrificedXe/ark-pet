package com.arkpet.maa

import android.content.Context
import android.content.pm.PackageManager
import com.arkpet.net.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * MAA-Meow 桥接：外部触发 MAA-Meow 执行任务配置
 * 双通道执行：优先 root(su)，其次 Shizuku（无需 root，不碰 bootloader）
 * 单例模式，避免双实例分裂（P0）
 * 事件回调 → WsClient.reportMaa() 上报 sense
 */
object MaaBridge {

    @Volatile
    private var wsClient: WsClient? = null
    private var initialized = false
    private var rootCache: Boolean? = null
    private var shizukuCache: Boolean? = null
    private var ctx: Context? = null
    private val context
        get() = ctx ?: throw IllegalStateException("MaaBridge 未初始化，先调用 MaaBridge.init(context)")
    private val execScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(ctx: Context) {
        if (initialized) return
        this.ctx = ctx
        initialized = true
    }

    fun setWsClient(client: WsClient) { wsClient = client }

    companion object {
        const val PKG = "com.aliothmoon.maameow"
        const val ACTION_LAUNCH_PROFILE = "com.aliothmoon.maameow.action.LAUNCH_PROFILE"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_FORCE_START = "extra_force_start"
    }

    /** 检查 MAA 引擎可用性（带缓存） */
    fun checkAvailable(): JSONObject {
        val installed = try {
            context.packageManager.getPackageInfo(PKG, 0) != null
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

    fun stop(): JSONObject {
        reportMaa("stopping", "")
        exec("am force-stop $PKG")
        reportMaa("stopped", "")
        return JSONObject().put("status", "ok")
    }

    fun status(): JSONObject = checkAvailable()

    /** 双通道执行：优先 root(su)，其次 Shizuku（带通道缓存与超时） */
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

    /** su 执行：waitFor 加 10s 超时 */
    private fun runSu(cmd: String): Pair<Boolean, String> {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val finished = p.waitFor(10, TimeUnit.SECONDS) // P0: 超时保护
            if (!finished) { p.destroyForcibly(); return Pair(false, "su_timeout") }
            Pair(p.exitValue() == 0, if (out.isNotEmpty()) out else err)
        } catch (e: Exception) {
            Pair(false, "su_failed: ${e.message}")
        }
    }

    /** Shizuku 执行：waitFor 加 10s 超时 */
    private fun runShizuku(cmd: String): Pair<Boolean, String> {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Pair(false, "shizuku_not_granted")
            }
            val m = Shizuku.getSystemService("shell")?.javaClass?.getMethod("exec", String::class.java, Array<String>::class.java, String::class.java, Array<out String>::class.java)
                ?: return Pair(false, "shizuku_shell_unavailable")
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            val finished = p.waitFor(10, TimeUnit.SECONDS) // P0: 超时保护
            if (!finished) { p.destroyForcibly(); return Pair(false, "shizuku_timeout") }
            val code = p.exitValue()
            Pair(code == 0, if (out.isNotEmpty()) out else err)
        } catch (e: Exception) {
            Pair(false, "shizuku_failed: ${e.message}")
        }
    }

    /** root 检测（缓存结果） */
    private fun isRooted(): Boolean {
        if (rootCache != null) return rootCache!!
        rootCache = try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(3, TimeUnit.SECONDS)
            out.contains("uid=0")
        } catch (_: Exception) { false }
        return rootCache!!
    }

    /** Shizuku 就绪检测（缓存结果） */
    private fun isShizukuReady(): Boolean {
        if (shizukuCache != null) return shizukuCache!!
        shizukuCache = try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
        return shizukuCache!!
    }

    private fun reportMaa(status: String, detail: String) {
        wsClient?.reportMaa(status, detail)
    }
}
