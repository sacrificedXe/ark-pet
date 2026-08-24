package com.arkpet.net

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.arkpet.maa.MaaBridge
import com.arkpet.mcp.tools.AppTools
import com.arkpet.mcp.tools.CameraTools
import com.arkpet.mcp.tools.FileTools
import com.arkpet.mcp.tools.ScreenTools
import com.arkpet.mcp.tools.SystemTools
import com.arkpet.mcp.tools.TouchTools
import com.arkpet.overlay.PetOverlayService
import com.arkpet.util.PetLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

/**
 * 与服务端的 WebSocket 长连接。
 *
 * 重写要点：
 * 1. URL 归一化改为显式解析 scheme / host / port / path，并**只在完全没写端口时**补 9100。
 *    上一版把 9100 强改成 9101 导致 WS 握手 400（打到 HTTP 端口上了），这类改写一律删除。
 * 2. 断线重连加 stopped 标记 + 单一 pending 任务，避免 stop() 后仍在后台无限重连，
 *    也避免 onFailure/onClosed 同时触发导致重连任务翻倍指数增长。
 * 3. 每一步都落盘：握手成功、失败原因、重连间隔。之前 WS 连不上时日志里什么都没有。
 * 4. 收到未知 action 明确回错误，不静默丢弃。
 *
 * v0.4.5 新增：
 * 5. 归一化后显式补全 path="/ws"（服务端 websockets.serve 默认不匹配裸域名），
 *    并打印握手 HTTP 状态码与响应头，排查 426 Upgrade Required。
 */
class WsClient(private val ctx: Context, private val serverUrl: String) {

    companion object {
        private const val TAG = "WsClient"
        private const val WS_DEFAULT_PORT = 9100
        private const val WS_PATH = "ws"

        /**
         * 归一化 WS 地址。规则：
         *   - http→ws, https→wss，无 scheme 视为 ws
         *   - 无端口补 9100；**已写端口一律保留**（不做任何 9100/9101 互换）
         *   - 无 path 或 path 为空补 /ws
         * 非法输入返回 null。
         */
        fun normalizeWsUrl(raw: String): String? {
            var u = raw.trim()
            if (u.isBlank()) return null
            if (!u.contains("://")) u = "ws://$u"

            val lower = u.lowercase()
            val scheme = when {
                lower.startsWith("wss://") -> "wss"
                lower.startsWith("ws://") -> "ws"
                lower.startsWith("https://") -> "wss"
                lower.startsWith("http://") -> "ws"
                else -> return null
            }
            val rest = u.substringAfter("://")
            if (rest.isBlank()) return null
            val authority = rest.substringBefore('/')
            var path = rest.substringAfter('/', "")

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
            if (host.isBlank()) return null

            if (path.trim('/').isBlank()) path = WS_PATH
            return "$scheme://$host:${port ?: WS_DEFAULT_PORT}/$path"
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private val stopped = AtomicBoolean(false)
    private val reconnectPending = AtomicBoolean(false)

    private var ws: WebSocket? = null
    private var deviceId = "arkpet-${Build.MODEL.replace(" ", "_")}"
    private var failCount = 0

    private val sys by lazy { SystemTools(ctx) }
    private val touch by lazy { TouchTools(ctx) }
    private val screen by lazy { ScreenTools(ctx) }
    private val apps by lazy { AppTools(ctx) }
    private val files by lazy { FileTools(ctx) }
    private val camera by lazy { CameraTools(ctx) }

    private var transform = PetTransform.load(ctx)
    private var currentAnim = "Relax"
    private var batteryLevel = 100

    /** 最近一次连接状态，供自检面板展示 */
    @Volatile var connected = false
        private set
    @Volatile var lastError: String = ""
        private set

    // ---------------------------------------------------------------- 连接

    fun connect() {
        if (stopped.get()) return
        val url = normalizeWsUrl(serverUrl)
        if (url == null) {
            lastError = "地址无法解析：$serverUrl"
            PetLog.e(TAG, lastError)
            return
        }
        PetLog.i(TAG, "连接 $url (第 ${failCount + 1} 次尝试)")
        try {
            ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        } catch (e: Exception) {
            lastError = "建连异常：${e.message}"
            PetLog.e(TAG, lastError, e)
            scheduleReconnect()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            failCount = 0
            connected = true
            lastError = ""
            // 打印完整响应头，排查 426/400 等握手失败
            val headers = response.headers().toMultimap().map { "${it.key}: ${it.value.joinToString(", ")}" }.joinToString("; ")
            PetLog.i(TAG, "握手成功 HTTP ${response.code} [$headers]，上报 hello device=$deviceId")
            webSocket.send(
                JSONObject().apply {
                    put("type", "hello")
                    put("device", deviceId)
                    put("model", Build.MODEL)
                    put("sdk", Build.VERSION.SDK_INT)
                    put("app_version", appVersion())
                    put("state", transformToJson())
                }.toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val msg = JSONObject(text)
                when (msg.optString("type")) {
                    "act" -> dispatchAct(webSocket, msg)
                    "cmd" -> dispatchCmd(webSocket, msg)
                    "hello_ack" -> {
                        deviceId = msg.optString("device_id", deviceId)
                        PetLog.i(TAG, "hello_ack server_version=${msg.optString("version")}")
                    }
                    "ping" -> webSocket.send(JSONObject().put("type", "pong").toString())
                    else -> {}
                }
            } catch (e: Exception) {
                PetLog.e(TAG, "消息处理异常: ${text.take(120)}", e)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            connected = false
            val respInfo = response?.let {
                val hs = it.headers().toMultimap().map { "${it.key}: ${it.value.joinToString(", ")}" }.joinToString("; ")
                " HTTP ${it.code} [$hs]"
            } ?: ""
            lastError = "${t.javaClass.simpleName}: ${t.message}$respInfo"
            PetLog.w(TAG, "连接失败 $lastError")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected = false
            PetLog.i(TAG, "连接关闭 code=$code reason=$reason")
            scheduleReconnect()
        }
    }

    /** 指数退避重连，上限 60s；同一时刻只允许一个待执行任务 */
    private fun scheduleReconnect() {
        if (stopped.get()) return
        if (!reconnectPending.compareAndSet(false, true)) return
        failCount++
        val delay = (3000L * 2.0.pow((failCount - 1).coerceAtMost(5))).toLong().coerceAtMost(60_000L)
        PetLog.i(TAG, "${delay}ms 后重连")
        main.postDelayed({
            reconnectPending.set(false)
            connect()
        }, delay)
    }

    fun stop() {
        stopped.set(true)
        connected = false
        main.removeCallbacksAndMessages(null)
        runCatching { ws?.close(1000, "bye") }
        ws = null
        PetLog.i(TAG, "已停止")
    }

    private fun appVersion(): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    // ---------------------------------------------------------------- 指令分发

    /** 新协议：{"type":"act","id":..,"action":..,"params":{..}} */
    private fun dispatchAct(sock: WebSocket, msg: JSONObject) {
        val id = msg.optString("id")
        val action = msg.optString("action")
        val p = msg.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (action) {
                "animate" -> post { PetOverlayService.instance?.playAnimation(
                    p.optString("anim", currentAnim), p.optDouble("speed", 1.0),
                    p.optBoolean("loop", true),
                    // 没传 flipX 就传 null（保持当前朝向）。原来默认 false 会把
                    // walkTo 刚设好的朝向硬掰回正面，走路方向和贴图永远相反。
                    if (p.has("flipX")) p.optBoolean("flipX") else null
                ) }
                "say" -> post { PetOverlayService.instance?.showBubble(p.optString("text")) }
                "walk_to" -> post {
                    PetOverlayService.instance?.walkTo(
                        p.optInt("x"), p.optInt("y"), p.optLong("duration", 1500L)
                    ) {}
                }
                "tap" -> touch.tap(p)
                "long_press" -> touch.longPress(p)
                "swipe" -> touch.swipe(p)
                "input" -> touch.input(p)
                "open_app" -> apps.open(p)
                "capture_screen" -> screen.capture(p)
                "camera_capture" -> camera.photo(p)
                "file_pull" -> files.pull(p)
                "transform" -> applyRemoteTransform(p)
                "set_skin" -> post { PetOverlayService.instance?.setSkin(p.optString("skin")) }
                "show_chat" -> post { PetOverlayService.instance?.showChatInput() }
                "hide_chat" -> post { PetOverlayService.instance?.hideChatInput() }
                else -> err("unknown_action:$action")
            }
        } catch (e: Exception) {
            PetLog.e(TAG, "act 执行异常 action=$action", e)
            err("${e.javaClass.simpleName}: ${e.message}")
        }
        reply(sock, id, result)
    }

    /** 旧协议：{"type":"cmd","id":..,"tool":"pet.say","params":{..}}，服务端当前用的是这套 */
    private fun dispatchCmd(sock: WebSocket, cmd: JSONObject) {
        val id = cmd.optString("id")
        val tool = cmd.optString("tool")
        val p = cmd.optJSONObject("params") ?: JSONObject()
        val result = try {
            when (tool) {
                "pet.say" -> post { PetOverlayService.instance?.showBubble(p.optString("text")) }
                "pet.action" -> post {
                    PetOverlayService.instance?.playAnimation(actionMap[p.optString("action")] ?: "Relax")
                }
                "pet.skin" -> post { PetOverlayService.instance?.setSkin(p.optString("skin")) }
                "pet.transform" -> applyRemoteTransform(p)
                "pet.show_chat" -> post { PetOverlayService.instance?.showChatInput() }
                "pet.hide_chat" -> post { PetOverlayService.instance?.hideChatInput() }
                "pet.state" -> JSONObject().put("status", "ok").put("data", getState())
                "pet.dump", "screen.dump" -> screen.dump(p)
                "screen.capture" -> screen.capture(p)
                "touch.tap" -> touch.tap(p)
                "touch.long_press" -> touch.longPress(p)
                "touch.swipe" -> touch.swipe(p)
                "touch.input" -> touch.input(p)
                "touch.find_and_tap" -> touch.findAndTap(p)
                "system.back" -> sys.back(p)
                "system.home" -> sys.home(p)
                "system.recents" -> sys.recents(p)
                "system.battery" -> sys.battery(p)
                "system.notify" -> sys.notify(p)
                "system.clipboard" -> sys.clipboard(p)
                "system.log" -> JSONObject().put("status", "ok")
                    .put("data", JSONObject().put("log", PetLog.tail(p.optInt("lines", 60))))
                "app.open" -> apps.open(p)
                "app.close" -> apps.close(p)
                "app.list" -> apps.list(p)
                "file.list" -> files.list(p)
                "file.delete" -> files.delete(p)
                "file.scan" -> files.scan(p)
                "file.pull" -> files.pull(p)
                "camera.photo" -> camera.photo(p)
                "maa.start" -> MaaBridge.start(p.optString("profile_id"), p.optBoolean("force_start"))
                "maa.stop" -> MaaBridge.stop()
                "maa.status" -> MaaBridge.status()
                "maa.check" -> MaaBridge.checkAvailable()
                else -> err("unknown_tool:$tool")
            }
        } catch (e: Exception) {
            PetLog.e(TAG, "cmd 执行异常 tool=$tool", e)
            err("${e.javaClass.simpleName}: ${e.message}")
        }
        reply(sock, id, result)
    }

    private fun applyRemoteTransform(p: JSONObject): JSONObject {
        transform = transform.copy(
            x = p.optDouble("x", transform.x.toDouble()).toFloat(),
            y = p.optDouble("y", transform.y.toDouble()).toFloat(),
            scale = p.optDouble("scale", transform.scale.toDouble()).toFloat(),
            flipX = p.optBoolean("flipX", transform.flipX),
            visible = p.optBoolean("visible", transform.visible)
        )
        PetOverlayService.instance?.applyTransform(transform)
        sendSense("transform", transformToJson())
        return ok()
    }

    private fun reply(sock: WebSocket, id: String, data: JSONObject) {
        runCatching {
            sock.send(JSONObject().put("type", "result").put("id", id).put("data", data).toString())
        }.onFailure { PetLog.w(TAG, "结果回传失败: ${it.message}") }
    }

    private inline fun post(crossinline fn: () -> Unit): JSONObject {
        main.post { runCatching { fn() }.onFailure { PetLog.e(TAG, "主线程动作异常", it) } }
        return ok()
    }

    private val actionMap = mapOf(
        "idle" to "Default", "walk" to "Move", "attack" to "Interact",
        "touch" to "Interact", "work" to "Relax", "sad" to "Sleep", "special" to "Special"
    )

    // ---------------------------------------------------------------- 上报

    fun reportTouch(x: Float, y: Float, action: Int, duration: Long = 0) {
        val evType = when (action) {
            android.view.MotionEvent.ACTION_DOWN -> "down"
            android.view.MotionEvent.ACTION_UP -> "up"
            android.view.MotionEvent.ACTION_MOVE -> "move"
            else -> "unknown"
        }
        sendSense("touch", JSONObject().put("x", x).put("y", y)
            .put("action", evType).put("duration", duration))
    }

    fun reportTransform() = sendSense("transform", transformToJson())

    fun reportAnim(anim: String) {
        currentAnim = anim
        sendSense("animation", JSONObject().put("anim", anim))
    }

    fun reportBattery(level: Int) {
        batteryLevel = level
        sendSense("battery", JSONObject().put("level", level))
    }

    fun reportScreen(text: String) = sendSense("screen", JSONObject().put("text", text))

    fun reportMaa(status: String, detail: String = "") =
        sendSense("maa", JSONObject().put("status", status).put("detail", detail))

    fun reportChatInput(text: String) = sendSense("chat_input", JSONObject().put("text", text))

    fun reportChatFile(name: String, size: Long, localPath: String) =
        sendSense("chat_file", JSONObject().put("name", name).put("size", size).put("local_path", localPath))

    private fun sendSense(type: String, data: JSONObject) {
        val sock = ws
        if (sock == null || !connected) return
        runCatching {
            sock.send(
                JSONObject().put("type", "sense")
                    .put("event", JSONObject().put("type", type).put("data", data))
                    .toString()
            )
        }
    }

    private fun transformToJson() = JSONObject()
        .put("x", transform.x).put("y", transform.y)
        .put("scale", transform.scale).put("flipX", transform.flipX)
        .put("visible", transform.visible)

    private fun ok() = JSONObject().put("status", "ok")
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)

    fun getState(): JSONObject = JSONObject()
        .put("device_id", deviceId)
        .put("connected", connected)
        .put("last_error", lastError)
        .put("transform", transformToJson())
        .put("current_anim", currentAnim)
        .put("battery", batteryLevel)
}
