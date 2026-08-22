package com.arkpet.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

/**
 * 无障碍服务：为 MCP 工具提供 back/home/点击/滑动能力
 */
class PetAccessibilityService : AccessibilityService() {

    companion object {
        var instance: PetAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    suspend fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    suspend fun home(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val d = CompletableDeferred<Boolean>()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { d.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { d.complete(false) }
        }, null)
        return d.await()
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val d = CompletableDeferred<Boolean>()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { d.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { d.complete(false) }
        }, null)
        return d.await()
    }

    suspend fun longPress(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
            .build()
        val d = CompletableDeferred<Boolean>()
        dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { d.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { d.complete(false) }
        }, null)
        return d.await()
    }

    /** 输入文字：targetSdk 28 用粘贴法（免 IME 焦点问题） */
    fun inputText(text: String): Boolean {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("arkpet_input", text))
            val node = rootInActiveWindow ?: return false
            val focused = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
            focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            true
        } catch (_: Exception) { false }
    }

    /** 在屏幕节点树中查找包含指定文字的可点击元素坐标 */
    fun findClickableByText(text: String): Pair<Int, Int>? {
        val root = rootInActiveWindow ?: return null
        val out = arrayOfNulls<android.graphics.Rect>(1)
        val found = findInTree(root, text, out)
        if (!found || out[0] == null) return null
        val r = out[0]!!
        return Pair(r.centerX(), r.centerY())
    }

    /** 把当前屏幕无障碍节点树拍平成文本，供 ScreenTools.dumpScreen 调用 */
    fun dumpScreen(maxLen: Int = 800): String {
        val root = rootInActiveWindow ?: return "no_active_window"
        val sb = StringBuilder()
        dumpNode(root, sb, 0)
        val result = sb.toString()
        return if (result.length <= maxLen) result else result.substring(0, maxLen) + "...(truncated)"
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        val indent = "  ".repeat(depth)
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val clazz = node.className?.toString() ?: ""
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        sb.append("$indent$clazz text=\"$text\" desc=\"$desc\" bounds=$bounds clickable=${node.isClickable}\n")
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, sb, depth + 1)
        }
    }

    private fun findInTree(
        node: AccessibilityNodeInfo,
        text: String,
        out: Array<android.graphics.Rect?>
    ): Boolean {
        val t = node.text?.toString() ?: ""
        if (t.contains(text, ignoreCase = true) && node.isClickable) {
            val r = android.graphics.Rect()
            node.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) { out[0] = r; return true }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (findInTree(child, text, out)) return true
        }
        return false
    }
}