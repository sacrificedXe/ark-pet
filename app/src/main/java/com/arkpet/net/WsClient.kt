package com.arkpet.net

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.arkpet.maa.MaaBridge
import com.arkpet.mcp.tools.AppTools
import com.arkpet.mcp.tools.CameraTools
import com.arkpet.mcp.tools.FileTools
import com.arkpet.mcp.tools.ScreenTools
import com.arkpet.mcp.tools.SystemTools
import com.arkpet.mcp.tools.TouchTools
import com.arkpet.overlay.PetOverlayService
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
import java.util.concurrent.TimeUnit

class WsClient(private val ctx: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var deviceId = "arkpet-${Build.MODEL.replace(" ", "_")}"
    private val main = Handler(Looper.getMainLooper())

    private val sys = SystemTools(ctx)
    private val touch = TouchTools(ctx)
    private val screen = ScreenTools(ctx)
    private val apps = AppTools(ctx)
    private val files = FileTools(ctx)
    private val camera = CameraTools(ctx)

    private var transform = PetTransform.load(ctx)
    private var currentAnim = "Relax"
    private var batteryLevel = 100

    private var wsFailCount = 0

    /** URL 归一化：http(s)→ws(s)；无 path 补 /ws；非法 scheme 返回 null */
    private fun normalizeWsUrl(raw: String): String? {
        var u = raw.trim()
        if (u.isBlank()) return null
        if (!u.contains("://")) u = "ws://$u"
        u = when {
            u.startsWith("http://", true) -> "ws://" + u.substring(7)
            u.startsWith("https://", true) -> "wss://" + u.substring(8)
            u.startsWith("ws://", true) || u.startsWith("wss://", true) -> u
            else -> return null
        }
        // 补 path：无路径或只有 / 时补 /ws（服务端 WS 路由）；trimEnd 避免尾斜杠双写
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        if (pathStart < 0 || u.substring(pathStart).trim('/').isBlank()) {
            u = u.trimEnd('/') + "/ws"
        }
        // 端口映射：9100 → 9101（旧端口兼容，保留用户协议）
        val authEnd = u.indexOf('/', schemeEnd)
        val authority = if (authEnd < 0) u else u.substring(0, authEnd)
        if (authority.endsWith(":9100")) {
            u = authority.dropLast(1) + "1" + if (authEnd < 0) "" else u.substring(authEnd)
        }
        return u
    }

    fun connect() {
        val url = normalizeWsUrl(serverUrl)
        if (url == null) {
            Log.e("ArkPet", "非法服务器地址: $serverUrl")
            return
        }
        try {
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, listener)
        } catch (e: Exception) {
            Log.e("ArkPet", "WS connect failed: ${e.message}")
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            wsFailCount = 0
            webSocket.send(JSONObject().apply {
                put("type", "hello")
                put("device", deviceId)
                put("sdk", Build.VERSION.SDK_INT)
                put("state", transformToJson())
            }.toString())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                val mtype = msg.optString("type")
                when (mtype) {
                    "act" -> dispatchAct(webSocket, msg)
                    "cmd" -> dispatchLegacy(webSocket, msg)
                    "hello_ack" -> { deviceId = msg.optString("device_id", deviceId) }
                    "subscribed" -> {}
                }
            } catch (_: Exception) {}
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w("ArkPet", "WS fail: ${t.message}")
            scheduleReconnect()
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        wsFailCount++
        // P0: 无限指数退避（上限 60s），不再 10 次后放弃
        val delay = (3000L * 2.0.pow(wsFailCount - 1)).toLong().coerceAtMost(60_000L)
        main.removeCallbacksAndMessages(this)
        main.postDelayed({ connect() }, delay)
    }

    private fun dispatchAct(ws: WebSocket, msg: JSONObject) {
        val id = msg.optString("id")
        val action = msg.optString("action")
        val params = msg.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (action) {
                "animate" -> actAnimate(params)
                "say" -> actSay(params)
                "walk_to" -> actWalkTo(params)
                "tap" -> actTap(params)
                "long_press" -> actLongPress(params)
                "swipe" -> actSwipe(params)
                "input" -> actInput(params)
                "open_app" -> actOpenApp(params)
                "capture_screen" -> actCaptureScreen(params)
                "camera_capture" -> actCameraCapture(params)
                "file_pull" -> actFilePull(params)
                "transform" -> actTransform(params)
                "set_skin" -> actSetSkin(params)
                "show_chat" -> actShowChat()
                "hide_chat" -> actHideChat()
                else -> JSONObject().put("status", "error").put("error", "unknown_action:$action")
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", e.message ?: "exception")
        }
        ws.send(JSONObject().put("type", "result").put("id", id).put("data", result).toString())
    }

    private fun actAnimate(p: JSONObject) = act { PetOverlayService.instance?.playAnimation(
        p.optString("anim", currentAnim), p.optDouble("speed", 1.0), p.optBoolean("loop", true), p.optBoolean("flipX", false)) }
    private fun actSay(p: JSONObject) = act { PetOverlayService.instance?.showBubble(p.optString("text")) }
    private fun actWalkTo(p: JSONObject) = act { PetOverlayService.instance?.walkTo(
        p.optInt("x"), p.optInt("y"), p.optLong("duration", 1500L)) { ok() } }
    private fun actTap(p: JSONObject) = touch.tap(p)
    private fun actLongPress(p: JSONObject) = touch.longPress(p)
    private fun actSwipe(p: JSONObject) = touch.swipe(p)
    private fun actInput(p: JSONObject) = touch.input(p)
    private fun actOpenApp(p: JSONObject) = apps.open(p)
    private fun actCaptureScreen(p: JSONObject) = screen.capture(p)
    private fun actCameraCapture(p: JSONObject) = camera.photo(p)
    private fun actFilePull(p: JSONObject) = files.pull(p)
    private fun actTransform(p: JSONObject): JSONObject {
        val newT = transform.copy(
            x = p.optDouble("x", transform.x.toDouble()).toFloat(),
            y = p.optDouble("y", transform.y.toDouble()).toFloat(),
            scale = p.optDouble("scale", transform.scale.toDouble()).toFloat(),
            flipX = p.optBoolean("flipX", transform.flipX),
            visible = p.optBoolean("visible", transform.visible)
        )
        transform = newT
        PetOverlayService.instance?.applyTransform(newT)
        sendSense(JSONObject().put("type", "transform").put("data", transformToJson()))
        return ok()
    }
    private fun actSetSkin(p: JSONObject) = act { PetOverlayService.instance?.setSkin(p.optString("skin")) }
    private fun actShowChat() = act { PetOverlayService.instance?.showChatInput() }
    private fun actHideChat() = act { PetOverlayService.instance?.hideChatInput() }

    private fun dispatchLegacy(ws: WebSocket, cmd: JSONObject) {
        val id = cmd.optString("id")
        val tool = cmd.optString("tool")
        val params = cmd.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (tool) {
                "pet.say" -> { PetOverlayService.instance?.showBubble(params.optString("text")); ok() }
                "pet.action" -> { PetOverlayService.instance?.playAnimation(actionMap[params.optString("action")] ?: "Relax"); ok() }
                "pet.skin" -> { PetOverlayService.instance?.setSkin(params.optString("skin")); ok() }
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
                "maa.start" -> MaaBridge.start(params.optString("profile_id"), params.optBoolean("force_start"))
                "maa.stop" -> MaaBridge.stop()
                "maa.status" -> MaaBridge.status()
                "maa.check" -> MaaBridge.checkAvailable()
                else -> JSONObject().put("status", "error").put("error", "unknown_tool:$tool")
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", e.message ?: "exception")
        }
        ws.send(JSONObject().put("type", "result").put("id", id).put("data", result).toString())
    }

    private inline fun act(crossinline fn: () -> Unit): JSONObject {
        main.post { fn() }; return ok()
    }

    private val actionMap = mapOf(
        "idle" to "Default", "walk" to "Move", "attack" to "Interact",
        "touch" to "Interact", "work" to "Relax", "sad" to "Sleep", "special" to "Special"
    )

    // ---------- Sense ----------
    fun reportTouch(x: Float, y: Float, action: Int, duration: Long = 0) {
        val evType = when (action) {
            android.view.MotionEvent.ACTION_DOWN -> "down"
            android.view.MotionEvent.ACTION_UP -> "up"
            android.view.MotionEvent.ACTION_MOVE -> "move"
            else -> "unknown"
        }
        sendSense(JSONObject().put("type", "touch").put("data", JSONObject().apply {
            put("x", x); put("y", y); put("action", evType); put("duration", duration)
        }))
    }
    fun reportTransform() = sendSense(JSONObject().put("type", "transform").put("data", transformToJson()))
    fun reportAnim(anim: String) { currentAnim = anim; sendSense(JSONObject().put("type", "animation").put("data", JSONObject().put("anim", anim))) }
    fun reportBattery(level: Int) { batteryLevel = level; sendSense(JSONObject().put("type", "battery").put("data", JSONObject().put("level", level))) }
    fun reportScreen(text: String) = sendSense(JSONObject().put("type", "screen").put("data", JSONObject().put("text", text)))
    fun reportMaa(status: String, detail: String = "") = sendSense(JSONObject().put("type", "maa").put("data", JSONObject().put("status", status).put("detail", detail)))
    fun reportChatInput(text: String) {
        sendSense(JSONObject().put("type", "chat_input").put("data", JSONObject().put("text", text)))
    }
    fun reportChatFile(name: String, size: Long, localPath: String) {
        sendSense(JSONObject().put("type", "chat_file").put("data", JSONObject().apply {
            put("name", name); put("size", size); put("local_path", localPath)
        }))
    }

    private fun sendSense(event: JSONObject) { ws?.send(JSONObject().put("type", "sense").put("event", event).toString()) }
    private fun transformToJson() = JSONObject().apply {
        put("x", transform.x); put("y", transform.y)
        put("scale", transform.scale); put("flipX", transform.flipX); put("visible", transform.visible)
    }
    private fun ok() = JSONObject().put("status", "ok")
    fun stop() { wsFailCount = 99; main.removeCallbacksAndMessages(null); ws?.close(1000, "bye"); scope.cancel() }
    fun getState(): JSONObject = JSONObject().apply {
        put("device_id", deviceId); put("transform", transformToJson()); put("current_anim", currentAnim); put("battery", batteryLevel)
    }
}
