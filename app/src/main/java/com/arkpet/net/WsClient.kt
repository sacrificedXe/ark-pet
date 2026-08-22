package com.arkpet.net

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arkpet.maa.MaaBridge
import com.arkpet.overlay.PetOverlayService
import com.arkpet.mcp.tools.AppTools
import com.arkpet.mcp.tools.CameraTools
import com.arkpet.mcp.tools.FileTools
import com.arkpet.mcp.tools.ScreenTools
import com.arkpet.mcp.tools.SystemTools
import com.arkpet.mcp.tools.TouchTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket 客户端：连接 ark-pet-server (9100)，接收指令并分发执行
 * 改进：指数退避 + 抖动 + 最大重试次数 + 重连互斥锁
 */
class WsClient(private val ctx: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var deviceId = "arkpet-" + android.os.Build.MODEL.replace(" ", "_")
    private val main = Handler(Looper.getMainLooper())

    // 重连状态
    private val isConnecting = AtomicBoolean(false)
    private val retryCount = AtomicInteger(0)
    private val MAX_RETRIES = 10
    private val BASE_DELAY_MS = 3000L
    private val MAX_DELAY_MS = 30000L

    // 工具集
    private val sys = SystemTools(ctx)
    private val touch = TouchTools(ctx)
    private val screen = ScreenTools(ctx)
    private val apps = AppTools(ctx)
    private val files = FileTools(ctx)
    private val camera = CameraTools(ctx)
    private val maa = MaaBridge(ctx)

    fun connect() {
        // 防重入：同一时间只允许一个连接尝试
        if (!isConnecting.compareAndSet(false, true)) {
            return
        }
        try {
            val req = Request.Builder().url(serverUrl).build()
            ws = client.newWebSocket(req, listener)
        } catch (e: Exception) {
            isConnecting.set(false)
            scheduleReconnect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("ArkPet", "WS connected")
            retryCount.set(0) // 连接成功重置计数
            isConnecting.set(false)
            webSocket.send(JSONObject().put("type", "hello")
                .put("device", deviceId)
                .put("sdk", android.os.Build.VERSION.SDK_INT)
                .toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                if (msg.optString("type") == "cmd") {
                    dispatch(webSocket, msg)
                }
            } catch (_: Exception) {}
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("ArkPet", "WS fail: ${t.message}")
            isConnecting.set(false)
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i("ArkPet", "WS closed: code=$code reason=$reason")
            isConnecting.set(false)
            scheduleReconnect()
        }
    }

    /** 指数退避 + 抖动重连 */
    private fun scheduleReconnect() {
        val current = retryCount.incrementAndGet()
        if (current > MAX_RETRIES) {
            Log.e("ArkPet", "WS 超过最大重试次数 ($MAX_RETRIES)，放弃重连")
            return
        }
        // 指数退避：min(30s, 3s * 2^(n-1)) + 抖动 0-1s
        val expDelay = min(MAX_DELAY_MS, BASE_DELAY_MS * (1L shl (current - 1)))
        val jitter = ThreadLocalRandom.current().nextLong(1000)
        val delay = expDelay + jitter
        Log.i("ArkPet", "WS 重连尝试 $current/$MAX_RETRIES，延迟 ${delay}ms")
        main.postDelayed({ connect() }, delay)
    }

    private fun dispatch(ws: WebSocket, cmd: JSONObject) {
        val id = cmd.optString("id")
        val tool = cmd.optString("tool")
        val params = cmd.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (tool) {
                "pet.say" -> petSay(params.optString("text"))
                "pet.action" -> petAction(params.optString("action"))
                "pet.skin" -> petSkin(params.optString("skin"))
                "pet.dump" -> screen.dump(params)
                "screen.dump" -> screen.dump(params)
                "screen.capture" -> screen.capture(params)
                "touch.tap" -> touch.tap(params)
                "touch.long_press" -> touch.longPress(params)
                "touch.swipe" -> touch.swipe(params)
                "touch.input" -> touch.input(params)
                "touch.find_and_tap" -> touch.findAndTap(params)
                "system.back" -> sys.back(params)
                "system.home" -> sys.home(params)
                "system.battery" -> sys.battery(params)
                "system.notify" -> sys.notify(params)
                "system.clipboard" -> sys.clipboard(params)
                "app.open" -> apps.open(params)
                "app.close" -> apps.close(params)
                "app.list" -> apps.list(params)
                "file.list" -> files.list(params)
                "file.delete" -> files.delete(params)
                "file.scan" -> files.scan(params)
                "file.pull" -> files.pull(params)
                "camera.photo" -> camera.photo(params)
                "maa.start" -> maa.start(params.optString("profile_id"), params.optBoolean("force_start"))
                "maa.stop" -> maa.stop()
                "maa.status" -> maa.status()
                "maa.check" -> maa.checkAvailable()
                else -> JSONObject().put("status", "error").put("error", "unknown_tool:$tool")
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", e.message ?: "exception")
        }
        try {
            ws.send(JSONObject().put("type", "result").put("id", id).put("data", result).toString())
        } catch (e: Exception) {
            Log.e("ArkPet", "WS send result failed: ${e.message}")
        }
    }

    // pet 控制走主线程（操作 UI）
    private fun petSay(text: String): JSONObject {
        main.post { PetOverlayService.instance?.showBubble(text) }
        return JSONObject().put("status", "ok")
    }

    private fun petAction(action: String): JSONObject {
        main.post { PetOverlayService.instance?.playAnimation(action) }
        return JSONObject().put("status", "ok")
    }

    private fun petSkin(skin: String): JSONObject {
        main.post { PetOverlayService.instance?.setSkin(skin) }
        return JSONObject().put("status", "ok")
    }

    fun stop() {
        ws?.close(1000, "bye")
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        isConnecting.set(false)
        retryCount.set(0)
    }

    companion object {
        private fun min(a: Long, b: Long): Long = if (a < b) a else b
    }
}
