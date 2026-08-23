package com.arkpet.updater

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新检查 + 下载 + 安装。
 * 服务端契约：GET {serverBase}/api/version -> {"version":"x.y.z","url":"...","note":"...","force":false}
 * 语义比较：逐段 int 比较，服务端 version 必须严格大于当前才通知。
 */
class UpdateChecker(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val CHANNEL_ID = "arkpet_update"
        private const val NOTIF_ID_UPDATE = 1001
        private const val NOTIF_ID_INSTALL = 1002
        private const val KEY_SERVER = "server_url"
        private const val KEY_LAST_CHECK_VERSION = "last_check_version"

        fun semanticCompare(a: String, b: String): Int {
            val sa = a.split('.').map { try { it.toInt() } catch (e: NumberFormatException) { 0 } }
            val sb = b.split('.').map { try { it.toInt() } catch (e: NumberFormatException) { 0 } }
            val len = maxOf(sa.size, sb.size)
            for (i in 0 until len) {
                val x = if (i < sa.size) sa[i] else 0
                val y = if (i < sb.size) sb[i] else 0
                if (x != y) return x - y
            }
            return 0
        }
    }

    init {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "更新", android.app.NotificationManager.IMPORTANCE_LOW))
        }
    }

    /** URL 归一化：ws(s)→http(s)；WS 端口 9100 自动换成 HTTP 端口 9101 */
    private fun normalizeHttpUrl(raw: String): String {
        var u = raw.trim()
        if (u.isBlank()) return u
        if (!u.contains("://")) u = "http://$u"
        u = when {
            u.startsWith("ws://", true) -> "http://" + u.substring(5)
            u.startsWith("wss://", true) -> "https://" + u.substring(6)
            else -> u
        }
        // 去掉 WS path（/ws）；WS 端口 9100 → HTTP 端口 9101；无端口时补 9101
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        var hostPort = if (pathStart < 0) u else u.substring(0, pathStart)
        val hp = hostPort.substring(schemeEnd)
        hostPort = when {
            hostPort.endsWith(":9100") -> hostPort.dropLast(5) + ":9101"
            !hp.contains(':') -> "$hostPort:9101"
            else -> hostPort
        }
        return hostPort
    }

    /** 检查一次，新版本回调 onResult；无新无可不回调 */
    fun check(serverBase: String, force: Boolean = false, onResult: (JSONObject) -> Unit) {
        Thread {
            runCatching {
                val base = normalizeHttpUrl(serverBase).trimEnd('/')
                val req = Request.Builder().url("$base/api/version").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val remoteVer = json.optString("version")
                    if (remoteVer.isBlank()) return@runCatching
                    val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    if (!force && semanticCompare(remoteVer, current) <= 0) return@runCatching
                    // 记录已查看，避免重复弹窗
                    ctx.getSharedPreferences("arkpet_update", Context.MODE_PRIVATE)
                        .edit().putString(KEY_LAST_CHECK_VERSION, remoteVer).apply()
                    onResult(json)
                }
            }.onFailure { e -> /* 静默失败，下次再试 */ }
        }.start()
    }

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        Thread {
            val file = File(ctx.cacheDir, "ark-pet-update.apk")
            runCatching {
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { notifyProgress("下载失败：${resp.code}"); onDone(false); return@use }
                    val body = resp.body ?: run { onDone(false); return@use }
                    val total = body.contentLength().coerceAtLeast(1L)
                    var done = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                done += n
                                onProgress((done * 100 / total).toInt())
                            }
                        }
                    }
                }
                notifyProgress("下载完成，点击安装")
                install(file)
                onDone(true)
            }.onFailure { e ->
                notifyProgress("安装失败：${e.message}")
                onDone(false)
            }
        }.start()
    }

    private fun notifyProgress(text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_UPDATE, NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("初雪桌宠")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
    }

    private fun notifyInstall() {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_INSTALL, NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("更新就绪")
            .setContentText("点击安装新版本")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(android.app.PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW, FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", File(ctx.cacheDir, "ark-pet-update.apk")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION), android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE))
            .build())
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }
}

