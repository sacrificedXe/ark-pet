package com.arkpet.maa

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.util.regex.Pattern

/**
 * MAA-Meow 桥接：外部触发 MAA-Meow 执行任务配置
 * 双通道执行：优先 root(su)，其次 Shizuku（无需 root，不碰 bootloader）
 * 依赖：MAA-Meow 后台模式 + Shizuku 授权（或 root）
 * 触发 action: com.aliothmoon.maameow.action.LAUNCH_PROFILE
 * 安全修复：profileId 白名单校验，避免命令注入；am start 用数组参数避免 shell 插值
 */
class MaaBridge(private val ctx: Context) {

    companion object {
        const val PKG = "com.aliothmoon.maameow"
        const val ACTION_LAUNCH_PROFILE = "com.aliothmoon.maameow.action.LAUNCH_PROFILE"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_FORCE_START = "extra_force_start"
        /** 仅允许字母数字下划线中划线，UUID 格式兼容 */
        private const val PROFILE_ID_PATTERN = "^[a-zA-Z0-9_-]{1,64}$"
        private val profileIdRegex = Pattern.compile(PROFILE_ID_PATTERN)
    }

    /** 检查 MAA 引擎可用性：是否安装、root、Shizuku */
    fun checkAvailable(): JSONObject {
        val installed = try {
            ctx.packageManager.getPackageInfo(PKG, 0) != null
        } catch (_: Exception) {
            false
        }
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

    /** 启动指定任务配置，profileId 为 MAA-Meow 后台任务页配置的 UUID */
    fun start(profileId: String, forceStart: Boolean): JSONObject {
        if (profileId.isBlank()) {
            return JSONObject().put("status", "error").put("error", "profile_id_required")
        }
        if (!profileIdRegex.matcher(profileId).matches()) {
            return JSONObject().put("status", "error").put("error", "profile_id_invalid_format")
        }
        // 用数组参数避免 shell 插值，彻底杜绝注入
        val args = mutableListOf(
            "am", "start",
            "-a", ACTION_LAUNCH_PROFILE,
            "-n", "$PKG/.MainActivity",
            "--es", EXTRA_PROFILE_ID, profileId
        )
        if (forceStart) {
            args.add("--ez"); args.add(EXTRA_FORCE_START); args.add("true")
        }
        val (ok, out) = exec(args.toTypedArray())
        return JSONObject().put("status", if (ok) "ok" else "error").put("output", out)
    }

    /** 强制停止 MAA-Meow */
    fun stop(): JSONObject {
        val (ok, out) = exec(arrayOf("am", "force-stop", PKG))
        return JSONObject().put("status", if (ok) "ok" else "error").put("output", out)
    }

    /** 查询 MAA-Meow 是否在运行 */
    fun status(): JSONObject {
        val (ok, out) = exec(arrayOf("pidof", PKG))
        val running = ok && out.trim().isNotEmpty()
        return JSONObject().put("running", running).put("pid", if (running) out.trim() else "")
    }

    /** 双通道执行：优先 root(su)，其次 Shizuku —— 统一用数组参数 */
    private fun exec(cmdArgs: Array<String>): Pair<Boolean, String> {
        if (isRooted()) {
            val (ok, out) = runSu(cmdArgs)
            if (ok) return Pair(true, out)
        }
        if (isShizukuReady()) {
            val (ok, out) = runShizuku(cmdArgs)
            if (ok) return Pair(true, out)
        }
        return Pair(false, "需要 root 或 Shizuku 授权")
    }

    private fun runSu(cmdArgs: Array<String>): Pair<Boolean, String> {
        // su -c 需要字符串，这里拼接但已校验 profileId，且其他参数固定，风险可控
        val cmdStr = cmdArgs.joinToString(" ")
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmdStr))
            val out = p.inputStream.bufferedReader().readText().trim()
            val err = p.errorStream.bufferedReader().readText().trim()
            p.waitFor()
            Pair(p.exitValue() == 0, if (out.isNotEmpty()) out else err)
        } catch (e: Exception) {
            Pair(false, "su_failed: ${e.message}")
        }
    }

    private fun runShizuku(cmdArgs: Array<String>): Pair<Boolean, String> {
        return try {
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return Pair(false, "shizuku_not_granted")
            }
            // Shizuku 公开 API：exec() 直接执行命令并返回输出
            val output = Shizuku.exec(*cmdArgs)
            Pair(true, output.trim())
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
        } catch (_: Exception) {
            false
        }
    }

    private fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                    Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }
}
