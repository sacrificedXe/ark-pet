package com.arkpet.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 后台周期性检查更新（WorkManager 12h）。
 * BootReceiver 在启动时也会立即入队一次。
 */
class UpdateWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val serverUrl = ctx.getSharedPreferences("arkpet", Context.MODE_PRIVATE)
            .getString("server_url", "") ?: ""
        if (serverUrl.isBlank()) return@withContext Result.success()
        val checker = UpdateChecker(ctx)
        checker.check(serverUrl, force = false) { _ ->
            // 新版已到，通知栏由 UpdateChecker 自己发；这里什么都不做
            // 如果希望弹窗更明确，可以在这里发一个带 PendingIntend 的通知跳转到 MainActivity
        }
        Result.success()
    }
}
