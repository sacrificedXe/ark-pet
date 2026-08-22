package com.arkpet.mcp.tools

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import android.util.DisplayMetrics
import android.util.Log
import com.arkpet.accessibility.PetAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.lang.ref.WeakReference

/**
 * 屏幕工具：截图(需 MediaProjection 授权) / 读取界面文本(无障碍)
 * MediaProjection 需 Activity 请求权限，这里简化为提供授权入口
 */
class ScreenTools(private val ctx: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    /** 截图：返回 base64 JPEG（需先授权 MediaProjection） */
    fun capture(p: JSONObject): JSONObject = runBlocking(Dispatchers.IO) {
        if (mediaProjection == null) {
            return@runBlocking JSONObject().put("status", "error")
                .put("error", "media_projection_not_granted")
                .put("hint", "请先调用 request_media_projection 获取授权")
        }
        return@runBlocking captureScreen(p.optInt("quality", 80), p.optInt("max_dim", 1920))
    }

    /** 请求 MediaProjection 授权（需在 Activity 中调用 startActivityForResult） */
    fun requestMediaProjection(activity: Activity, requestCode: Int): JSONObject {
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mgr.createScreenCaptureIntent()
        activity.startActivityForResult(intent, requestCode)
        return JSONObject().put("status", "pending").put("request_code", requestCode)
    }

    /** 接收 onActivityResult 回调 */
    fun onActivityResult(resultCode: Int, data: Intent?): JSONObject {
        if (resultCode != Activity.RESULT_OK || data == null) {
            return JSONObject().put("status", "error").put("error", "user_denied")
        }
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
        return JSONObject().put("status", "ok")
    }

    private fun captureScreen(quality: Int, maxDim: Int): JSONObject {
        val dm = ctx.resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        if (w > maxDim || h > maxDim) {
            val scale = minOf(maxDim.toDouble() / w, maxDim.toDouble() / h)
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, Handler(Looper.getMainLooper())
        ) ?: return JSONObject().put("status", "error").put("error", "virtual_display_failed")

        Thread.sleep(200) // 等待首帧

        val image = imageReader?.acquireLatestImage() ?: return JSONObject().put("status", "error").put("error", "no_image")
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixels = IntArray(w * h)
        buffer.rewind()
        buffer.asIntBuffer().get(pixels)
        image.close()

        val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        bitmap.recycle()

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        return JSONObject().put("status", "ok").put("data", JSONObject().apply {
            put("base64", b64)
            put("width", w)
            put("height", h)
            put("mime", "image/jpeg")
        })
    }

    /** 读取界面文本（无障碍） */
    fun dump(p: JSONObject): JSONObject {
        val text = PetAccessibilityService.instance?.dumpScreen(p.optInt("max_len", 1500)) ?: ""
        return JSONObject().put("status", "ok").put("data", JSONObject().put("text", text))
    }
}
