package com.arkpet.mcp.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.arkpet.accessibility.PetAccessibilityService
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 屏幕工具：截图(需 MediaProjection 授权) / 读取界面文本(无障碍)
 */
class ScreenTools(private val ctx: Context) {

    fun capture(p: JSONObject): JSONObject {
        val dump = PetAccessibilityService.instance?.dumpScreen(p.optInt("max_len", 800)) ?: ""
        return JSONObject().put("status", "ok")
            .put("data", JSONObject().put("text", dump))
    }

    fun dump(p: JSONObject): JSONObject {
        val text = PetAccessibilityService.instance?.dumpScreen(p.optInt("max_len", 1500)) ?: ""
        return JSONObject().put("status", "ok").put("data", JSONObject().put("text", text))
    }
}
