package com.arkpet.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 落盘日志 + 全局崩溃捕获。
 *
 * 存在理由：步步高 Study OS 会过滤第三方 App 的 logcat，`adb logcat` 抓不到东西，
 * Toast 也一闪而过，导致「桌宠打不开」无任何可诊断信息。所有关键步骤必须落盘。
 *
 * 日志路径（免存储权限，API 28 可直接写）：
 *   /sdcard/Android/data/com.arkpet/files/arkpet.log
 * 拉取：adb pull /sdcard/Android/data/com.arkpet/files/arkpet.log
 */
object PetLog {

    private const val TAG = "ArkPet"
    private const val MAX_BYTES = 512 * 1024L

    @Volatile private var logFile: File? = null
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(ctx: Context) {
        synchronized(lock) {
            if (logFile != null) return
            logFile = try {
                val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                dir.mkdirs()
                File(dir, "arkpet.log")
            } catch (e: Exception) {
                Log.e(TAG, "PetLog init failed: ${e.message}")
                null
            }
        }
        installCrashHandler()
        i("PetLog", "==== log opened: ${path()} ====")
    }

    fun path(): String = logFile?.absolutePath ?: "(未初始化)"

    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) {
        write("E", tag, if (t == null) msg else "$msg\n${stackOf(t)}")
    }

    private fun write(level: String, tag: String, msg: String) {
        when (level) {
            "E" -> Log.e(TAG, "[$tag] $msg")
            "W" -> Log.w(TAG, "[$tag] $msg")
            else -> Log.i(TAG, "[$tag] $msg")
        }
        val f = logFile ?: return
        synchronized(lock) {
            try {
                if (f.length() > MAX_BYTES) rotate(f)
                f.appendText("${fmt.format(Date())} $level/$tag: $msg\n")
            } catch (_: Exception) {
                // 落盘失败不能反过来搞崩 App
            }
        }
    }

    /** 超限时保留后半段，避免无限增长又不丢最近现场 */
    private fun rotate(f: File) {
        try {
            val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
            f.writeText("---- rotated ${fmt.format(Date())} ----\n$keep")
        } catch (_: Exception) {
            try { f.delete() } catch (_: Exception) {}
        }
    }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    /** 未捕获异常先落盘再交还系统默认处理，不吞崩溃 */
    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        if (prev is CrashHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(prev))
    }

    private class CrashHandler(private val prev: Thread.UncaughtExceptionHandler?) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try { e("CRASH", "thread=${t.name}", e) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** 读取日志尾部若干行，供 App 内「查看日志」直接展示（无需 adb） */
    fun tail(lines: Int = 60): String {
        val f = logFile ?: return "日志未初始化"
        return try {
            if (!f.exists()) "日志文件不存在：${f.absolutePath}"
            else f.readLines().takeLast(lines).joinToString("\n").ifBlank { "日志为空" }
        } catch (ex: Exception) {
            "读取日志失败：${ex.message}"
        }
    }
}
