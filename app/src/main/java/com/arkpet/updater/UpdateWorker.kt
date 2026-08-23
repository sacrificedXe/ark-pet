package com.arkpet.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arkpet.util.PetLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * 后台周期检查更新（12h）。
 *
 * 原实现的问题：UpdateChecker.check 内部起线程立即返回，doWork 紧跟着 return Result.success()，
 * WorkManager 认为任务结束就可能回收进程，回调很可能永远跑不完。这里用 suspend 桥接等结果。
 */
class UpdateWorker(private val ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    companion object { private const val TAG = "UpdateWorker" }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val serverUrl = ctx.getSharedPreferences("arkpet", Context.MODE_PRIVATE)
            .getString("server_url", "").orEmpty()
        if (serverUrl.isBlank()) {
            PetLog.i(TAG, "server_url 为空，跳过后台查更新")
            return@withContext Result.success()
        }

        val checker = UpdateChecker(ctx)
        val json: JSONObject? = withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine { cont ->
                checker.check(
                    serverUrl,
                    force = false,
                    onNone = { reason ->
                        PetLog.i(TAG, "后台查更新：$reason")
                        if (cont.isActive) cont.resume(null)
                    }
                ) { j ->
                    if (cont.isActive) cont.resume(j)
                }
            }
        }

        if (json == null) return@withContext Result.success()

        // 后台只下载并发通知，不强行拉安装界面打断用户
        val url = json.optString("url")
        if (url.isBlank()) return@withContext Result.success()
        PetLog.i(TAG, "后台下载新版 ${json.optString("version")}")
        withTimeoutOrNull(10 * 60_000L) {
            suspendCancellableCoroutine { cont ->
                checker.downloadAndInstall(url, {}) { ok ->
                    PetLog.i(TAG, "后台下载结果 $ok")
                    if (cont.isActive) cont.resume(ok)
                }
            }
        }
        Result.success()
    }
}
