package com.arkpet.mcp.tools

import android.content.Context
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Size
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * 相机工具：调摄像头拍照（前置/后置），返回 base64 JPEG
 * 用于 MAA 日活时可能需要的前置识别辅助，P3 完善
 */
class CameraTools(private val ctx: Context) {

    fun photo(p: JSONObject): JSONObject {
        // 占位：Android 9 上 Camera2 + ImageReader 拍照流程较长，P2 阶段完整实现
        return JSONObject().put("status", "pending").put("note", "P2 实现相机拍照")
    }
}
