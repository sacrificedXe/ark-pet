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

/**
 * WebSocket 客户端：连接 ark-pet-server (9100)
 * 协议：双向 sense/act + 兼容旧 cmd/result
 */
class WsClient(private val ctx: Context, private val serverUrl: String) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private var deviceId = "arkpet-${Build.MODEL.replace(" ", "_")}"
    private val main = Handler(Looper.getMainLooper())

    // 工具集
    private val sys = SystemTools(ctx)
    private val touch = TouchTools(ctx)
    private val screen = ScreenTools(ctx)
    private val apps = AppTools(ctx)
    private val files = FileTools(ctx)
    private val camera = CameraTools(ctx)
    private val maa = MaaBridge(ctx)

    // 状态
    private var transform = PetTransform.load(ctx)
    private var currentAnim = "Relax"
    private var batteryLevel = 100

    fun connect() {
        val req = Request.Builder().url(serverUrl).build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i("ArkPet", "WS connected")
            // 发 hello + 当前身体状态
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
                    "cmd" -> dispatchLegacy(webSocket, msg)  // 兼容旧协议
                    "hello_ack" -> { deviceId = msg.optString("device_id", deviceId) }
                    "subscribed" -> {} // 大脑流订阅确认
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

    // ---------- 新协议：act ----------
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
                else -> JSONObject().put("status", "error").put("error", "unknown_action:$action")
            }
        } catch (e: Exception) {
            JSONObject().put("status", "error").put("error", e.message ?: "exception")
        }
        ws.send(JSONObject().put("type", "result").put("id", id).put("data", result).toString())
    }

    // ---------- 兼容旧协议：cmd ----------
    private fun dispatchLegacy(ws: WebSocket, cmd: JSONObject) {
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
        ws.send(JSONObject().put("type", "result").put("id", id).put("data", result).toString())
    }

    // ---------- act 实现 ----------
    private fun actAnimate(p: JSONObject): JSONObject {
        val animName = p.optString("anim")
        val speed = p.optDouble("speed", 1.0)
        val loop = p.optBoolean("loop", true)
        val flipX = p.optBoolean("flipX", false)
        main.post { PetOverlayService.instance?.playAnimation(animName, speed, loop, flipX) }
        currentAnim = animName
        return ok()
    }

    private fun actSay(p: JSONObject): JSONObject {
        val text = p.optString("text")
        val emotion = p.optString("emotion", "neutral")
        main.post { PetOverlayService.instance?.showBubble(text) }
        // TODO: MiMo TTS 播放
        return ok()
    }

    private fun actWalkTo(p: JSONObject): JSONObject {
        val x = p.optInt("x")
        val y = p.optInt("y")
        val duration = p.optLong("duration", 1500L)
        main.post { PetOverlayService.instance?.walkTo(x, y, duration) { arrived ->
            sendSense(JSONObject().put("type", "walk_arrived").put("data", JSONObject().put("x", x).put("y", y).put("arrived", arrived)))
        }}
        return ok()
    }

    private fun actTap(p: JSONObject): JSONObject = touch.tap(p)
    private fun actLongPress(p: JSONObject): JSONObject = touch.longPress(p)
    private fun actSwipe(p: JSONObject): JSONObject = touch.swipe(p)
    private fun actInput(p: JSONObject): JSONObject = touch.input(p)
    private fun actOpenApp(p: JSONObject): JSONObject = apps.open(p)

    private fun actCaptureScreen(p: JSONObject): JSONObject = screen.capture(p)
    private fun actCameraCapture(p: JSONObject): JSONObject = camera.photo(p)
    private fun actFilePull(p: JSONObject): JSONObject = files.pull(p)

    private fun actTransform(p: JSONObject): JSONObject {
        val newT = transform.copy(
            x = p.optDouble("x", transform.x.toDouble()).toFloat(),
            y = p.optDouble("y", transform.y.toDouble()).toFloat(),
            scale = p.optDouble("scale", transform.scale.toDouble()).toFloat(),
            flipX = p.optBoolean("flipX", transform.flipX),
            visible = p.optBoolean("visible", transform.visible)
        )
        transform = newT
        PetTransform.save(ctx, newT)
        main.post { PetOverlayService.instance?.applyTransform(newT) }
        sendSense(JSONObject().put("type", "transform").put("data", transformToJson()))
        return ok()
    }

    private fun actSetSkin(p: JSONObject): JSONObject {
        val skin = p.optString("skin")
        main.post { PetOverlayService.instance?.setSkin(skin) }
        return ok()
    }

    // ---------- pet 旧接口 ----------
    private fun petSay(text: String): JSONObject {
        main.post { PetOverlayService.instance?.showBubble(text) }
        return ok()
    }

    private val actionMap = mapOf(
        "idle" to "Default", "walk" to "Move", "attack" to "Interact",
        "touch" to "Interact", "work" to "Relax", "sad" to "Sleep", "special" to "Special"
    )

    private fun petAction(action: String): JSONObject {
        val animName = actionMap[action] ?: "Relax"
        main.post { PetOverlayService.instance?.playAnimation(animName) }
        currentAnim = animName
        return ok()
    }

    private fun petSkin(skin: String): JSONObject {
        main.post { PetOverlayService.instance?.setSkin(skin) }
        return ok()
    }

    // ---------- 上行 sense ----------
    fun sendSense(event: JSONObject) {
        ws?.send(JSONObject().put("type", "sense").put("event", event).toString())
    }

    /** 触摸感知（PetOverlayService 调用） */
    fun reportTouch(x: Float, y: Float, action: Int, duration: Long = 0) {
        val evType = when (action) {
            MotionEvent.ACTION_DOWN -> "down"
            MotionEvent.ACTION_UP -> "up"
            MotionEvent.ACTION_MOVE -> "move"
            else -> "unknown"
        }
        sendSense(JSONObject().put("type", "touch").put("data", JSONObject().apply {
            put("x", x); put("y", y); put("action", evType); put("duration", duration)
        }))
    }

    /** 变换状态感知 */
    fun reportTransform() {
        sendSense(JSONObject().put("type", "transform").put("data", transformToJson()))
    }

    /** 动画变化感知 */
    fun reportAnim(anim: String) {
        currentAnim = anim
        sendSense(JSONObject().put("type", "animation").put("data", JSONObject().put("anim", anim)))
    }

    /** 电量感知 */
    fun reportBattery(level: Int) {
        batteryLevel = level
        sendSense(JSONObject().put("type", "battery").put("data", JSONObject().put("level", level)))
    }

    /** 屏幕内容感知 */
    fun reportScreen(text: String) {
        sendSense(JSONObject().put("type", "screen").put("data", JSONObject().put("text", text)))
    }

    /** MAA 状态感知 */
    fun reportMaa(status: String, detail: String = "") {
        sendSense(JSONObject().put("type", "maa").put("data", JSONObject().put("status", status).put("detail", detail)))
    }

    private fun transformToJson() = JSONObject().apply {
        put("x", transform.x); put("y", transform.y)
        put("scale", transform.scale); put("flipX", transform.flipX); put("visible", transform.visible)
    }

    private fun ok() = JSONObject().put("status", "ok")
    fun stop() { ws?.close(1000, "bye"); scope.cancel() }

    // 供外部获取当前状态
    fun getState(): JSONObject = JSONObject().apply {
        put("device_id", deviceId)
        put("transform", transformToJson())
        put("current_anim", currentAnim)
        put("battery", batteryLevel)
    }
}

// MotionEvent 常量（避免依赖 android.view）
private object MotionEvent {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
}
