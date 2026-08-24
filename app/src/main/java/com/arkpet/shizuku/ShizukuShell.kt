package com.arkpet.shizuku

import android.content.pm.PackageManager
import com.arkpet.util.PetLog
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shizuku shell 通道。
 *
 * 为什么必须有这个东西：
 * 步步高 StudyOS 的 PackageInstaller 只认自家 BPK 加密格式，第三方标准 APK 走
 * Intent.ACTION_VIEW 拉安装器会被直接判「家教机无法识别」。这一层在系统里，
 * 授了 REQUEST_INSTALL_PACKAGES appop 也没用——那只解决权限声明，解决不了格式校验。
 * 机器没有 root（which su 空、su -c id 空），所以只剩 Shizuku：
 * 它以 shell uid 起进程，pm install 走的是和 adb install 同一条特权路径，
 * 完全不经过 StudyOS 那个界面。
 *
 * Shizuku.newProcess 在 13.x 上是 @hide/@RestrictTo，只能反射调。
 * 返回的 ShizukuRemoteProcess 继承 java.lang.Process，接口和本地进程一致。
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    /** 缓存置空即可强制重新探测（授权状态会变） */
    @Volatile
    private var newProcessMethod: java.lang.reflect.Method? = null

    /** binder 活着 + 已授权，两个条件都要 */
    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    /** 只有 binder 活但没授权 —— UI 该提示「去 Shizuku 里授权」而不是「没装 Shizuku」 */
    fun isRunningButUnauthorized(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun resolveNewProcess(): java.lang.reflect.Method? {
        newProcessMethod?.let { return it }
        val m = runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.onFailure { PetLog.e(TAG, "反射 newProcess 失败", it) }.getOrNull()
        newProcessMethod = m
        return m
    }

    /**
     * 以 shell uid 执行命令。
     * @param timeoutSec pm install 大包会久，默认给足
     * @return ok 为 exitCode==0；out 合并 stdout/stderr，失败时原因在里面
     */
    fun exec(cmd: String, timeoutSec: Long = 180): Pair<Boolean, String> {
        if (!isReady()) {
            return Pair(false, if (isRunningButUnauthorized()) "shizuku_not_authorized" else "shizuku_not_running")
        }
        val method = resolveNewProcess() ?: return Pair(false, "shizuku_api_unavailable")
        return try {
            @Suppress("UNCHECKED_CAST")
            val proc = method.invoke(
                null,
                arrayOf("sh", "-c", cmd),
                null,
                null
            ) as java.lang.Process

            val out = proc.inputStream.bufferedReader().readText().trim()
            val err = proc.errorStream.bufferedReader().readText().trim()
            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                runCatching { proc.destroyForcibly() }
                return Pair(false, "shizuku_timeout(${timeoutSec}s)")
            }
            val code = proc.exitValue()
            val text = listOf(out, err).filter { it.isNotEmpty() }.joinToString("\n")
            PetLog.i(TAG, "exec[$code] $cmd → ${text.take(200)}")
            Pair(code == 0, text)
        } catch (e: Throwable) {
            PetLog.e(TAG, "exec 异常: $cmd", e)
            Pair(false, "shizuku_exec_failed: ${e.message}")
        }
    }

    /** 探活用，顺带能确认拿到的是不是 shell uid（期望 uid=2000） */
    fun whoami(): String = exec("id", 10).second

    /**
     * 主动向 Shizuku 申请权限。
     *
     * 这是「App 没出现在 Shizuku 授权列表里」的解法：Shizuku 只列出请求过权限的应用，
     * 从不主动申请 = 永远不出现在列表 = 用户没法手动勾。必须由 App 自己发起一次。
     * REQUEST_CODE 随便取，回调在 MainActivity 里注册。
     */
    const val REQUEST_CODE = 4711

    /** @return 0=已授权 1=已发起申请等用户点 2=binder 不在（Shizuku 没跑） 3=旧版 Shizuku 需手动授权 */
    fun requestPermission(): Int {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) return 2
        return try {
            if (Shizuku.isPreV11()) return 3
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return 0
            if (Shizuku.shouldShowRequestPermissionRationale()) return 3
            Shizuku.requestPermission(REQUEST_CODE)
            1
        } catch (e: Throwable) {
            PetLog.e(TAG, "requestPermission 失败", e)
            2
        }
    }

    /**
     * 静默安装 APK。
     *
     * 关键坑：文件必须让 shell uid 读得到。cacheDir（/data/data/com.arkpet/cache）
     * shell 进不去，pm install 会报 Permission denied。所以调用方要把 APK
     * 下到 externalCacheDir（/sdcard/Android/data/com.arkpet/cache），
     * API 28 上 shell 对 /sdcard 有读权限。
     *
     * -r 覆盖安装、-d 允许 versionCode 降级（回滚旧版时用得上）、--user 0 指定主用户。
     */
    fun installApk(apk: File): Pair<Boolean, String> {
        if (!apk.exists() || apk.length() < 1024) {
            return Pair(false, "apk_missing_or_truncated(${apk.length()}B)")
        }
        val path = apk.absolutePath
        // 先放宽可读位，多用户/沙箱路径下 shell 偶尔读不到
        exec("chmod 644 \"$path\"", 10)
        val (ok, out) = exec("pm install -r -d --user 0 \"$path\"")
        // pm install 成功时 stdout 是 "Success"，但个别 ROM exitCode 不为 0，两个都看
        val success = ok || out.contains("Success", ignoreCase = true)
        return Pair(success, if (out.isBlank()) (if (success) "Success" else "no_output") else out)
    }
}
