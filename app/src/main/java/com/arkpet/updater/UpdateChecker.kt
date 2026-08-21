package com.arkpet.updater

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 更新检查：GET {server}/api/version -> {"version":"0.2.0","url":"https://.../ark-pet.apk","note":"..."}
 * 下载到 cache 后走安装（root 后静默安装；无 root 走系统安装器）
 */
class UpdateChecker(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun check(serverBase: String, onResult: (JSONObject) -> Unit) {
        Thread {
            runCatching {
                val req = Request.Builder().url(serverBase.trimEnd('/') + "/api/version").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val json = JSONObject(resp.body?.string() ?: "{}")
                    val current = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                    if (json.optString("version") != current) onResult(json)
                }
            }
        }.start()
    }

    fun downloadAndInstall(url: String, onProgress: (Int) -> Unit, onDone: (Boolean) -> Unit) {
        Thread {
            runCatching {
                val file = File(ctx.cacheDir, "ark-pet-update.apk")
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) { onDone(false); return@use }
                    val body = resp.body ?: return@use
                    val total = body.contentLength()
                    var done = 0L
                    body.byteStream().use { input ->
                        file.outputStream().use { output ->
                            val buf = ByteArray(8192)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress((done * 100 / total).toInt())
                            }
                        }
                    }
                }
                install(file)
                onDone(true)
            }.onFailure { onDone(false) }
        }.start()
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
