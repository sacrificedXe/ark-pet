package com.arkpet.mcp.tools

import android.content.Context
import com.arkpet.accessibility.PetAccessibilityService
import com.arkpet.util.PetLog
import org.json.JSONObject

/**
 * 屏幕工具。
 *
 * 重要变更：**移除 MediaProjection 截图**。
 * 原实现从未真正可用——mediaProjection 永远为 null（没有 Activity 走 startActivityForResult），
 * 且 ImageReader 直接 buffer.asIntBuffer().get() 忽略 rowStride，在多数设备上会画面错位或抛异常。
 * 与其留个假接口骗调用方，不如明确返回 unsupported，把读屏能力集中在无障碍文本 dump 上。
 * 真要截图，后续应接 CameraX/MediaProjection + 前台 Activity 授权，作为独立需求做。
 */
class ScreenTools(private val ctx: Context) {

    companion object { private const val TAG = "ScreenTools" }

    fun capture(p: JSONObject): JSONObject =
        JSONObject().put("status", "error")
            .put("error", "screenshot_unsupported")
            .put("hint", "本版本不支持截图，请用 screen.dump 读取界面文本")

    /** 读取当前界面文本 */
    fun dump(p: JSONObject): JSONObject {
        val s = PetAccessibilityService.instance
            ?: return JSONObject().put("status", "error")
                .put("error", "无障碍服务未连接：设置 → 无障碍 → 开启「初雪桌宠」")
        val text = s.dumpScreen(p.optInt("max_len", 1500))
        PetLog.i(TAG, "dump 返回 ${text.length} 字符")
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("text", text).put("length", text.length))
    }
}
