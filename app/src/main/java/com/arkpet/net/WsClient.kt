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
    private val maa = MaaBridge(ctx)

    private var transform = PetTransform.load(ctx)
    private var currentAnim = "Relax"
    private var batteryLevel = 100

    fun connect() {
        val req = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
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
            main.postDelayed({ connect() }, 3000)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.postDelayed({ connect() }, 3000)
        }
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
                "maa.start" -> maa.start(params.optString("profile_id"), params.optBoolean("force_start"))
                "maa.stop" -> maa.stop()
                "maa.status" -> maa.status()
                "maa.check" -> maa.checkAvailable()
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
    fun stop() { ws?.close(1000, "bye"); scope.cancel() }
    fun getState(): JSONObject = JSONObject().apply {
        put("device_id", deviceId); put("transform", transformToJson()); put("current_anim", currentAnim); put("battery", batteryLevel)
    }
}
