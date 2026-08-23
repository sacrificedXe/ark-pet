package com.arkpet.mcp.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Size
import com.arkpet.util.PetLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 相机工具：Camera2 无预览静态抓拍。
 *
 * 原实现是假的——写了一个内容为 "ARK_CAMERA_PLACEHOLDER" 的文本文件，再用 BitmapFactory 去解，
 * decodeFile 返回 null，紧接着访问 thumb.width 必然 NPE。这里换成真正的 Camera2 抓拍：
 * 打开摄像头 → ImageReader 收 JPEG → 落盘 + base64 缩略。
 *
 * 前置条件：CAMERA 运行时权限（MainActivity 首次进入会申请）。
 * 无权限时明确报错，不再假装成功。
 */
class CameraTools(private val ctx: Context) {

    companion object {
        private const val TAG = "CameraTools"
        private const val OPEN_TIMEOUT_MS = 8_000L
        private const val CAPTURE_TIMEOUT_MS = 10_000L
        private const val THUMB_MAX_BYTES = 400 * 1024
    }

    private val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val outDir = File(ctx.getExternalFilesDir(null) ?: ctx.cacheDir, "camera")

    fun photo(p: JSONObject): JSONObject {
        if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return err("camera_permission_denied：请在 App 主界面授予相机权限")
        }
        val front = p.optBoolean("front", false)
        val quality = p.optInt("quality", 85).coerceIn(40, 100)

        var thread: HandlerThread? = null
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var session: CameraCaptureSession? = null
        try {
            outDir.mkdirs()
            val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val camId = pickCamera(mgr, front) ?: return err("no_camera_found")
            val size = pickSize(mgr, camId)
            PetLog.i(TAG, "抓拍 camId=$camId size=${size.width}x${size.height} front=$front")

            thread = HandlerThread("arkpet-camera").also { it.start() }
            val handler = Handler(thread.looper)

            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            val imageLatch = CountDownLatch(1)
            var jpeg: ByteArray? = null
            reader.setOnImageAvailableListener({ r ->
                runCatching {
                    r.acquireLatestImage()?.use { img ->
                        val buf = img.planes[0].buffer
                        jpeg = ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                }.onFailure { PetLog.e(TAG, "读取图像失败", it) }
                imageLatch.countDown()
            }, handler)

            // 打开相机
            val openLatch = CountDownLatch(1)
            var openError: String? = null
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; openLatch.countDown() }
                override fun onDisconnected(camera: CameraDevice) {
                    openError = "camera_disconnected"; camera.close(); openLatch.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    openError = "camera_error_$error"; camera.close(); openLatch.countDown()
                }
            }, handler)
            if (!openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return err("camera_open_timeout")
            openError?.let { return err(it) }
            val cam = device ?: return err("camera_open_failed")

            // 建会话并拍一张
            val sessionLatch = CountDownLatch(1)
            var sessionError: String? = null
            @Suppress("DEPRECATION")
            cam.createCaptureSession(
                listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; sessionLatch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessionError = "session_configure_failed"; sessionLatch.countDown()
                    }
                }, handler
            )
            if (!sessionLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return err("session_timeout")
            sessionError?.let { return err(it) }
            val s = session ?: return err("session_null")

            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, quality.toByte())
            }.build()
            s.capture(req, null, handler)

            if (!imageLatch.await(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) return err("capture_timeout")
            val bytes = jpeg ?: return err("empty_image")

            val outFile = File(outDir, "ARK_${stamp.format(Date())}.jpg")
            outFile.writeBytes(bytes)
            PetLog.i(TAG, "抓拍完成 ${bytes.size} bytes → ${outFile.absolutePath}")

            val data = JSONObject()
                .put("path", outFile.absolutePath)
                .put("size", bytes.size)
                .put("width", size.width)
                .put("height", size.height)
                .put("mime", "image/jpeg")
            // 太大就不塞 base64，避免 WS 单帧过大被服务端截断
            if (bytes.size <= THUMB_MAX_BYTES) {
                data.put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            } else {
                data.put("base64_omitted", "文件 ${bytes.size} 字节超限，请用 file.pull 拉取")
            }
            return JSONObject().put("status", "ok").put("data", data)
        } catch (e: SecurityException) {
            return err("camera_permission_denied")
        } catch (e: Exception) {
            PetLog.e(TAG, "抓拍失败", e)
            return err("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            runCatching { thread?.quitSafely() }
        }
    }

    private fun pickCamera(mgr: CameraManager, front: Boolean): String? {
        val want = if (front) CameraCharacteristics.LENS_FACING_FRONT
        else CameraCharacteristics.LENS_FACING_BACK
        val ids = mgr.cameraIdList
        ids.forEach { id ->
            val facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
            if (facing == want) return id
        }
        return ids.firstOrNull()
    }

    /** 取不超过 1920 的最大 JPEG 尺寸，控制单帧体积 */
    private fun pickSize(mgr: CameraManager, camId: String): Size {
        val map = mgr.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (sizes.isEmpty()) return Size(1280, 720)
        return sizes.filter { it.width <= 1920 && it.height <= 1920 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }!!
    }

    private fun err(msg: String) = JSONObject().put("status", "error").put("error", msg)
}
