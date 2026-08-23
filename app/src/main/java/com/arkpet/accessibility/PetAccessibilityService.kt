package com.arkpet.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.arkpet.util.PetLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 无障碍服务：为远程控制提供 点击 / 长按 / 滑动 / 输入 / 读屏 / 返回 / 主页。
 *
 * 重写要点：
 * 1. 全部改成阻塞 + 超时的普通函数，不再用 suspend + runBlocking。
 *    原实现在主线程调用会直接死锁（dispatchGesture 的回调也在主线程，runBlocking 卡住主线程
 *    → 回调永远进不来 → ANR）。
 * 2. 手势回调派发到独立 HandlerThread，与调用方线程彻底解耦。
 * 3. 节点树递归加深度上限，避免个别 ROM 的循环引用导致栈溢出。
 * 4. onServiceConnected 落盘，方便确认「到底有没有注册上」。
 */
class PetAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "Accessibility"
        private const val GESTURE_TIMEOUT_MS = 5_000L
        private const val MAX_DEPTH = 40

        @Volatile
        var instance: PetAccessibilityService? = null
            private set
    }

    private var cbThread: HandlerThread? = null
    private var cbHandler: Handler? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        PetLog.init(applicationContext)
        instance = this
        cbThread = HandlerThread("arkpet-gesture").also { it.start() }
        cbHandler = Handler(cbThread!!.looper)
        PetLog.i(TAG, "无障碍服务已连接，远程控制可用")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* 不订阅具体事件 */ }

    override fun onInterrupt() {
        PetLog.w(TAG, "无障碍服务被中断")
    }

    override fun onDestroy() {
        PetLog.w(TAG, "无障碍服务已断开")
        instance = null
        cbThread?.quitSafely()
        cbThread = null
        cbHandler = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 全局动作

    fun back(): Boolean = safeGlobal(GLOBAL_ACTION_BACK, "back")
    fun home(): Boolean = safeGlobal(GLOBAL_ACTION_HOME, "home")
    fun recents(): Boolean = safeGlobal(GLOBAL_ACTION_RECENTS, "recents")

    private fun safeGlobal(action: Int, name: String): Boolean {
        val ok = runCatching { performGlobalAction(action) }.getOrElse {
            PetLog.e(TAG, "$name 失败", it); false
        }
        if (!ok) PetLog.w(TAG, "$name 返回 false")
        return ok
    }

    // ---------------------------------------------------------------- 手势

    fun tap(x: Int, y: Int): Boolean = gesture("tap($x,$y)") {
        Path().apply { moveTo(x.toFloat(), y.toFloat()) } to 60L
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 600L): Boolean = gesture("longPress($x,$y)") {
        Path().apply { moveTo(x.toFloat(), y.toFloat()) } to durationMs
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean =
        gesture("swipe($x1,$y1→$x2,$y2)") {
            Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            } to durationMs.coerceIn(50L, 10_000L)
        }

    private inline fun gesture(desc: String, build: () -> Pair<Path, Long>): Boolean {
        return try {
            val (path, duration) = build()
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
            val latch = CountDownLatch(1)
            var result = false
            val dispatched = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    result = true; latch.countDown()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    result = false; latch.countDown()
                }
            }, cbHandler)
            if (!dispatched) {
                PetLog.w(TAG, "$desc dispatchGesture 直接返回 false")
                return false
            }
            if (!latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                PetLog.w(TAG, "$desc 超时未回调")
                return false
            }
            if (!result) PetLog.w(TAG, "$desc 被系统取消")
            result
        } catch (e: Exception) {
            PetLog.e(TAG, "$desc 异常", e)
            false
        }
    }

    // ---------------------------------------------------------------- 输入

    /** 先试 SET_TEXT（可靠），失败再退回粘贴法 */
    fun inputText(text: String): Boolean {
        val node = focusedEditable() ?: run {
            PetLog.w(TAG, "inputText: 找不到可编辑焦点")
            return false
        }
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
                .getOrDefault(false)
        ) return true

        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("arkpet_input", text))
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            PetLog.e(TAG, "inputText 粘贴法失败", e); false
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { return it }
        return findNode(root, 0) { it.isEditable }
    }

    // ---------------------------------------------------------------- 查找 / 读屏

    /** 找包含指定文字的可点击节点中心坐标；若文字节点本身不可点击，向上找可点击父节点 */
    fun findClickableByText(text: String): Pair<Int, Int>? {
        if (text.isBlank()) return null
        val root = rootInActiveWindow ?: return null

        val direct = findNode(root, 0) { n ->
            n.isClickable && matches(n, text)
        }
        if (direct != null) return centerOf(direct)

        val textNode = findNode(root, 0) { n -> matches(n, text) } ?: return null
        var p: AccessibilityNodeInfo? = textNode.parent
        var hop = 0
        while (p != null && hop < 6) {
            if (p.isClickable) return centerOf(p)
            p = p.parent
            hop++
        }
        return centerOf(textNode)
    }

    private fun matches(n: AccessibilityNodeInfo, text: String): Boolean {
        val t = n.text?.toString().orEmpty()
        val d = n.contentDescription?.toString().orEmpty()
        return t.contains(text, true) || d.contains(text, true)
    }

    private fun centerOf(n: AccessibilityNodeInfo): Pair<Int, Int>? {
        val r = Rect()
        n.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return null
        return r.centerX() to r.centerY()
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        if (runCatching { pred(node) }.getOrDefault(false)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            findNode(c, depth + 1, pred)?.let { return it }
        }
        return null
    }

    /** 读取当前窗口可见文本，供 AI 理解屏幕 */
    fun dumpScreen(maxLen: Int = 1500): String {
        val root = rootInActiveWindow ?: return "(读不到窗口内容，确认无障碍已授权且当前应用未禁止读屏)"
        val sb = StringBuilder()
        collect(root, sb, 0)
        val s = sb.toString().trim()
        return when {
            s.isBlank() -> "(当前窗口无文本节点)"
            s.length <= maxLen -> s
            else -> s.substring(0, maxLen) + "\n…(已截断)"
        }
    }

    private fun collect(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH) return
        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        val label = when {
            !t.isNullOrBlank() -> t
            !d.isNullOrBlank() -> d
            else -> null
        }
        if (label != null) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append("  ".repeat(depth.coerceAtMost(8))).append(label)
            if (node.isClickable) sb.append("  [可点击]")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collect(it, sb, depth + 1) }
        }
    }
}
