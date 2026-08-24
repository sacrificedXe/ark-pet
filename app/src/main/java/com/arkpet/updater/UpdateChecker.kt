package com.arkpet.updater

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.arkpet.shizuku.ShizukuShell
import com.arkpet.util.PetLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新检查 / 下载 / 安装。
 *
 * 服务端契约：GET {httpBase}/api/version →
 *   {"version":"0.4.0","url":"http://host:9101/apk/ark-pet.apk","note":"...","force":false}
 *
 * 重写要点：
 * 1. URL 归一化用字符串切分明确处理 scheme/host/port/path，不再用 dropLast 数字符。
 *    原实现 `hostPort.dropLast(5) + ":9101"` 在没带端口时会砍掉主机名尾部，产出 `::9101`
 *    这种畸形 URL，OkHttp 直接抛异常且被 runCatching 静默吞掉——一个永远不报错的死循环。
 * 2. 失败必须有回声：新增 onNone 回调，把「已是最新 / HTTP 4xx / 网络异常」都回报到 UI，
 *    并全部落盘。静默失败是上一版最大的问题。
 * 3. 下载先写 .tmp 再 rename，避免半截 APK 被当成完整包去安装。
 */
class UpdateChecker(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "Updater"
        private const val CHANNEL_ID = "arkpet_update"
        private const val NOTIF_ID = 1001
        const val APK_NAME = "ark-pet-update.apk"

        /** WS 端口 → HTTP 端口的映射；服务端 9100=WS / 9101=HTTP / 9102=MCP */
        private const val WS_PORT = 9100
        private const val HTTP_PORT = 9101

        fun semanticCompare(a: String, b: String): Int {
            fun parse(s: String) = s.trim().split('.', '-', '_')
                .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
            val sa = parse(a); val sb = parse(b)
            for (i in 0 until maxOf(sa.size, sb.size)) {
                val x = sa.getOrElse(i) { 0 }
                val y = sb.getOrElse(i) { 0 }
                if (x != y) return x - y
            }
            return 0
        }

        /**
         * 把用户填的任意地址（ws/wss/http/https、带或不带端口、带或不带 /ws）
         * 归一化成 HTTP API 基址，形如 http://host:9101（不含尾斜杠、不含 path）。
         */
        fun httpBaseOf(raw: String): String {
            var u = raw.trim()
            if (u.isBlank()) return ""
            if (!u.contains("://")) u = "http://$u"

            val lower = u.lowercase()
            val scheme = when {
                lower.startsWith("wss://") -> "https"
                lower.startsWith("ws://") -> "http"
                lower.startsWith("https://") -> "https"
                else -> "http"
            }
            val rest = u.substringAfter("://")
            // 只取 authority，path/query 全丢
            val authority = rest.substringBefore('/').substringBefore('?')

            // IPv6 字面量形如 [::1]:9100，冒号计数法会误判，单独处理
            val host: String
            val port: Int?
            if (authority.startsWith("[")) {
                host = authority.substringBefore(']') + "]"
                val tail = authority.substringAfter(']')
                port = if (tail.startsWith(":")) tail.drop(1).toIntOrNull() else null
            } else {
                host = authority.substringBefore(':')
                port = authority.substringAfter(':', "").toIntOrNull()
            }
            if (host.isBlank()) return ""

            val finalPort = when (port) {
                null -> HTTP_PORT      // 没写端口：默认 HTTP 端口
                WS_PORT -> HTTP_PORT   // 写的是 WS 端口：换成 HTTP 端口
                else -> port           // 用户明确指定：尊重原值
            }
            return "$scheme://$host:$finalPort"
        }
    }

    init {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "更新", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun currentVersion(): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0.0.0"
    }.getOrDefault("0.0.0")

    /**
     * @param force true 时忽略版本比较，命中即回调（手动「检查更新」用）
     * @param onNone 无更新或失败时的原因回报，UI 可直接 toast
     * @param onUpdate 有更新时回调，参数为服务端 JSON（url 已补成绝对地址）
     */
    fun check(
        serverBase: String,
        force: Boolean = false,
        onNone: (String) -> Unit = {},
        onUpdate: (JSONObject) -> Unit
    ) {
        Thread {
            val base = httpBaseOf(serverBase)
            if (base.isBlank()) {
                PetLog.e(TAG, "地址无法解析: $serverBase")
                onNone("服务器地址无法解析"); return@Thread
            }
            val api = "$base/api/version"
            PetLog.i(TAG, "查更新 → $api (当前 ${currentVersion()})")
            try {
                client.newCall(Request.Builder().url(api).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        PetLog.w(TAG, "查更新 HTTP ${resp.code}")
                        onNone("服务器返回 HTTP ${resp.code}"); return@use
                    }
                    val body = resp.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrElse {
                        PetLog.e(TAG, "响应非 JSON: ${body.take(120)}")
                        onNone("服务器响应不是 JSON"); return@use
                    }
                    val remote = json.optString("version")
                    if (remote.isBlank()) { onNone("服务器未返回版本号"); return@use }

                    val cur = currentVersion()
                    val diff = semanticCompare(remote, cur)
                    PetLog.i(TAG, "远端 $remote / 本地 $cur / diff=$diff / force=$force")
                    if (!force && diff <= 0) { onNone("已是最新版本 $cur"); return@use }
                    if (force && diff <= 0) {
                        onNone("服务器版本 $remote，未高于本机 $cur"); return@use
                    }

                    // 服务端可能给相对路径，补成绝对地址，免得 OkHttp 抛 IllegalArgumentException
                    val rawUrl = json.optString("url")
                    val absUrl = when {
                        rawUrl.isBlank() -> "$base/apk/ark-pet.apk"
                        rawUrl.startsWith("http", true) -> rawUrl
                        else -> "$base/${rawUrl.trimStart('/')}"
                    }
                    json.put("url", absUrl)
                    onUpdate(json)
                }
            } catch (e: Exception) {
                PetLog.e(TAG, "查更新失败", e)
                onNone("连接失败：${e.javaClass.simpleName}")
            }
        }.start()
    }

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        Thread {
            // 落盘位置从 cacheDir 改成 externalCacheDir：
            // Shizuku 以 shell uid(2000) 执行 pm install，进不去 /data/data/com.arkpet/cache，
            // 会报 Permission denied。/sdcard/Android/data/com.arkpet/cache 在 API 28 上 shell 可读。
            val dir = ctx.externalCacheDir ?: ctx.cacheDir
            val tmp = File(dir, "$APK_NAME.tmp")
            val target = File(dir, APK_NAME)
            PetLog.i(TAG, "开始下载 $url")
            notify("正在下载更新…")
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        PetLog.e(TAG, "下载失败 HTTP ${resp.code}")
                        notify("下载失败：HTTP ${resp.code}")
                        onDone(false); return@Thread
                    }
                    val body = resp.body ?: run {
                        notify("下载失败：响应为空"); onDone(false); return@Thread
                    }
                    val total = body.contentLength()
                    var done = 0L
                    var lastPct = -1
                    body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                                }
                            }
                        }
                    }
                    if (done < 1024) {
                        PetLog.e(TAG, "下载内容过小 ($done B)，判定失败")
                        notify("下载失败：文件不完整")
                        tmp.delete(); onDone(false); return@Thread
                    }
                }
                if (target.exists()) target.delete()
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true); tmp.delete()
                }
                PetLog.i(TAG, "下载完成 ${target.length()} bytes → ${target.absolutePath}")
                notifyInstall()
                install(target)
                onDone(true)
            } catch (e: Exception) {
                PetLog.e(TAG, "下载/安装异常", e)
                notify("更新失败：${e.javaClass.simpleName}")
                runCatching { tmp.delete() }
                onDone(false)
            }
        }.start()
    }

    /**
     * 安装。优先 Shizuku 静默装，失败再退回系统安装器。
     *
     * 为什么不能只靠系统安装器：步步高 StudyOS 的 PackageInstaller 只认自家 BPK 加密格式，
     * 标准 APK 走 ACTION_VIEW 会被判「家教机无法识别」。这一层在系统里，
     * REQUEST_INSTALL_PACKAGES appop 放行也解决不了——那只管权限声明，不管格式校验。
     * Shizuku 以 shell uid 调 pm install，走的是和 adb install 同一条路，绕开那个界面。
     */
    private fun install(file: File) {
        if (ShizukuShell.isReady()) {
            val (ok, out) = ShizukuShell.installApk(file)
            PetLog.i(TAG, "Shizuku 安装 ok=$ok out=$out")
            if (ok) { notify("已通过 Shizuku 安装完成"); return }
            notify("Shizuku 安装失败：${out.take(60)}，改走系统安装器")
        } else {
            PetLog.w(TAG, "Shizuku 未就绪，退回系统安装器（S5 上大概率会被拒）")
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(intent)
            PetLog.i(TAG, "已拉起安装器")
        } catch (e: Exception) {
            // 拉不起安装器时通知栏那条仍可点，不算彻底失败
            PetLog.e(TAG, "拉起安装器失败，请点通知栏", e)
        }
    }

    private fun notify(text: String) {
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("初雪桌宠更新")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
            )
        }
    }

    private fun notifyInstall() {
        runCatching {
            val file = File(ctx.cacheDir, APK_NAME)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val pi = PendingIntent.getActivity(
                ctx, 0,
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (android.os.Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(
                NOTIF_ID,
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setContentTitle("更新已就绪")
                    .setContentText("点击安装新版本")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .build()
            )
        }
    }
}
