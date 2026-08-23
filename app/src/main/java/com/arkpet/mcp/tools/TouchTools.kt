package com.arkpet.mcp.tools

import android.content.Context
import com.arkpet.accessibility.PetAccessibilityService
import org.json.JSONObject

/**
 * 触摸工具：点击 / 长按 / 滑动 / 输入 / 按文字点击。全部经无障碍执行，无需 root。
 * 注意：PetAccessibilityService 的手势方法已改为阻塞式，这里不再用 runBlocking。
 */
class TouchTools(private val ctx: Context) {

    private fun svc(): PetAccessibilityService? = PetAccessibilityService.instance

    private val notConnected = "无障碍服务未连接：设置 → 无障碍 → 开启「初雪桌宠」"

    fun tap(p: JSONObject): JSONObject {
        val s = svc() ?: return err(notConnected)
        val x = p.optInt("x", -1)
        val y = p.optInt("y", -1)
        if (x < 0 || y < 0) return err("need x,y")
        return if (s.tap(x, y)) ok() else err("gesture_failed")
    }

    fun longPress(p: JSONObject): JSONObject {
        val s = svc() ?: return err(notConnected)
        val x = p.optInt("x", -1)
        val y = p.optInt("y", -1)
        if (x < 0 || y < 0) return err("need x,y")
        return if (s.longPress(x, y, p.optLong("duration", 600L))) ok() else err("gesture_failed")
    }

    fun swipe(p: JSONObject): JSONObject {
        val s = svc() ?: return err(notConnected)
        val ok = s.swipe(
            p.optInt("x1"), p.optInt("y1"), p.optInt("x2"), p.optInt("y2"),
            p.optLong("duration", 300L)
        )
        return if (ok) ok() else err("gesture_failed")
    }

    fun input(p: JSONObject): JSONObject {
        val s = svc() ?: return err(notConnected)
        val text = p.optString("text")
        if (text.isEmpty()) return err("need text")
        return if (s.inputText(text)) ok() else err("input_failed：当前无可编辑输入框焦点")
    }

    fun findAndTap(p: JSONObject): JSONObject {
        val s = svc() ?: return err(notConnected)
        val text = p.optString("text")
        if (text.isEmpty()) return err("need text")
        val pos = s.findClickableByText(text) ?: return err("text_not_found: $text")
        return if (s.tap(pos.first, pos.second))
            JSONObject().put("status", "ok")
                .put("data", JSONObject().put("x", pos.first).put("y", pos.second))
        else err("gesture_failed")
    }

    private fun ok() = JSONObject().put("status", "ok")
    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
