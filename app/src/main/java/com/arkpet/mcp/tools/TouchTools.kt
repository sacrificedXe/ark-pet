package com.arkpet.mcp.tools

import android.content.Context
import com.arkpet.accessibility.PetAccessibilityService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * 触摸工具：点击/长按/滑动/输入/查找文字点击
 * 全部经无障碍服务执行，无需 root
 */
class TouchTools(private val ctx: Context) {

    private fun svc(): PetAccessibilityService? = PetAccessibilityService.instance

    fun tap(p: JSONObject): JSONObject {
        val s = svc() ?: return err("accessibility_not_connected")
        val x = p.optInt("x", -1); val y = p.optInt("y", -1)
        if (x < 0 || y < 0) return err("need x,y")
        val ok = runBlocking { s.tap(x, y) }
        return if (ok) ok() else err("gesture_failed")
    }

    fun longPress(p: JSONObject): JSONObject {
        val s = svc() ?: return err("accessibility_not_connected")
        val ok = runBlocking { s.longPress(p.optInt("x"), p.optInt("y")) }
        return if (ok) ok() else err("gesture_failed")
    }

    fun swipe(p: JSONObject): JSONObject {
        val s = svc() ?: return err("accessibility_not_connected")
        val ok = runBlocking {
            s.swipe(p.optInt("x1"), p.optInt("y1"), p.optInt("x2"), p.optInt("y2"), p.optInt("duration", 300).toLong())
        }
        return if (ok) ok() else err("gesture_failed")
    }

    fun input(p: JSONObject): JSONObject {
        val s = svc() ?: return err("accessibility_not_connected")
        val text = p.optString("text")
        return if (s.inputText(text)) ok() else err("input_failed")
    }

    fun findAndTap(p: JSONObject): JSONObject {
        val s = svc() ?: return err("accessibility_not_connected")
        val pos = s.findClickableByText(p.optString("text")) ?: return err("text_not_found")
        val ok = runBlocking { s.tap(pos.first, pos.second) }
        return if (ok) JSONObject().put("status", "ok").put("data", JSONObject().put("x", pos.first).put("y", pos.second))
        else err("gesture_failed")
    }

    private fun ok() = JSONObject().put("status", "ok")
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
