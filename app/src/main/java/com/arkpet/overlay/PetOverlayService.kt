package com.arkpet.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.arkpet.R
import com.arkpet.net.PetTransform
import com.arkpet.net.WsClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 桌宠悬浮窗：Glide 播动画 WebP + 皮肤切换 + 拖动 + 缩放 + 走路 + sense 上报
 */
class PetOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private lateinit var ivPet: ImageView
    private var skin = "base"      // base / snow / cloud_trail
    private var anim = "Relax"     // 当前动画
    private var overlayParams: WindowManager.LayoutParams? = null
    private var wsClient: WsClient? = null
    private var petScale = 1.0f
    private var baseW = 0
    private var baseH = 0
    private lateinit var scaleDetector: ScaleGestureDetector
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchTime = 0L
    private var isWalking = false
    private var walkCallback: ((Boolean) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "arkpet_pet"
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
        var instance: PetOverlayService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("server_url")
            ?: getSharedPreferences("arkpet", MODE_PRIVATE).getString("server_url", "")
            ?: "ws://127.0.0.1:9100/ws"
        if (wsClient == null && url.isNotBlank()) {
            wsClient = WsClient(this, url).also { it.connect() }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        setupOverlay()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("初雪桌宠运行中")
            .setContentText("点击切换皮肤")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n)
    }

    private fun setupOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // 恢复保存的变换状态
        val saved = PetTransform.load(this)

        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved.x.toInt().coerceAtLeast(0)
            y = saved.y.toInt().coerceAtLeast(0)
        }

        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_pet, null)
        ivPet = rootView.findViewById(R.id.iv_pet)

        // 皮肤切换：长按桌宠循环 base/snow/cloud_trail
        ivPet.setOnLongClickListener {
            skin = when (skin) {
                "base" -> "snow"
                "snow" -> "cloud_trail"
                else -> "base"
            }
            loadAnim()
            true
        }

        // 双指捏合缩放
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                petScale = (petScale * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
                applyScale()
                return true
            }
        })

        ivPet.post {
            if (baseW == 0) { baseW = ivPet.width; baseH = ivPet.height }
            // 应用保存的 scale/flipX/visible
            petScale = saved.scale
            applyScale()
            ivPet.scaleX = if (saved.flipX) -1f else 1f
            rootView.visibility = if (saved.visible) View.VISIBLE else View.INVISIBLE
        }

        // 拖动 + 点击判定
        rootView.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = ev.rawX
                    touchStartY = ev.rawY
                    lastTouchTime = System.currentTimeMillis()
                    false // 继续传递给子 View（皮肤按钮）
                }
                MotionEvent.ACTION_MOVE -> {
                    if (scaleDetector.isInProgress) { true } else {
                        val dx = ev.rawX - touchStartX
                        val dy = ev.rawY - touchStartY
                        // 移动阈值 > 20px 视为拖动，否则可能是点击
                        if (Math.hypot(dx.toDouble(), dy.toDouble()) > 20) {
                            overlayParams!!.x = (overlayParams!!.x + dx).toInt()
                            overlayParams!!.y = (overlayParams!!.y + dy).toInt()
                            wm.updateViewLayout(rootView, overlayParams)
                            touchStartX = ev.rawX
                            touchStartY = ev.rawY
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - touchStartX
                    val dy = ev.rawY - touchStartY
                    val duration = System.currentTimeMillis() - lastTouchTime
                    val isClick = Math.hypot(dx.toDouble(), dy.toDouble()) <= 20 && duration < 300

                    // 上报触摸感知
                    wsClient?.reportTouch(ev.rawX, ev.rawY, ev.action, duration)

                    if (isClick) {
                        // 点击桌宠本体 → sense.touch + 触发 Interact 动画
                        wsClient?.reportTouch(ev.rawX, ev.rawY, MotionEvent.ACTION_UP, duration)
                        playAnimation("Interact")
                        wsClient?.reportAnim("Interact")
                    } else {
                        // 拖动结束 → 上报 transform
                        saveTransform()
                        wsClient?.reportTransform()
                    }
                    false
                }
                else -> false
            }
        }

        wm.addView(rootView, overlayParams)
        loadAnim()
    }

    /** 播放动画（支持 speed/loop/flipX） */
    fun playAnimation(name: String, speed: Double = 1.0, loop: Boolean = true, flipX: Boolean = false) {
        val valid = listOf("Default", "Interact", "Move", "Relax", "Sit", "Sleep", "Special")
        anim = if (name in valid) name else "Relax"
        ivPet.scaleX = if (flipX) -1f else 1f
        val uri = "file:///android_asset/pet/${skin}_${anim}.webp"
        Glide.with(this)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(ivPet)
    }

    /** 供 MCP 调用：切换皮肤 */
    fun setSkin(name: String) {
        if (name !in setOf("base", "snow", "cloud_trail")) return
        if (skin == name) return
        skin = name
        loadAnim()
    }

    /** 加载当前皮肤+动画 */
    private fun loadAnim() {
        val uri = "file:///android_asset/pet/${skin}_${anim}.webp"
        Glide.with(this)
            .load(uri)
            .transition(DrawableTransitionOptions.withCrossFade(200))
            .into(ivPet)
    }

    /** 气泡：在桌宠上方短暂显示文字 */
    fun showBubble(text: String) {
        try {
            val tv = TextView(this).apply {
                setText(text)
                setTextColor(0xFF333333.toInt())
                setBackgroundColor(0xEEFFFFFF.toInt())
                textSize = 14f
                setPadding(24, 16, 24, 16)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (overlayParams?.x ?: 0) + (ivPet.width / 2) - 80
                y = (overlayParams?.y ?: 0) - 80
            }
            wm.addView(tv, lp)
            mainHandler.postDelayed({ try { wm.removeView(tv) } catch (_: Exception) {} }, 3500)
        } catch (_: Exception) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 双指缩放 */
    private fun applyScale() {
        if (baseW == 0 || baseH == 0) return
        val lp = ivPet.layoutParams
        lp.width = (baseW * petScale).toInt().coerceAtLeast(50)
        lp.height = (baseH * petScale).toInt().coerceAtLeast(50)
        ivPet.layoutParams = lp
    }

    /** 供 MCP 调用：设置缩放比例 */
    fun setSize(scale: Float) {
        petScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        mainHandler.post { applyScale() }
        saveTransform()
    }

    /** 应用完整变换（位置/缩放/朝向/显隐） */
    fun applyTransform(t: PetTransform) {
        petScale = t.scale
        applyScale()
        ivPet.scaleX = if (t.flipX) -1f else 1f
        rootView.visibility = if (t.visible) View.VISIBLE else View.INVISIBLE
        if (overlayParams != null) {
            overlayParams!!.x = t.x.toInt()
            overlayParams!!.y = t.y.toInt()
            wm.updateViewLayout(rootView, overlayParams)
        }
    }

    /** 走到坐标：分段插值移动，支持 flipX 自动翻转，到达回调 */
    fun walkTo(targetX: Int, targetY: Int, durationMs: Long, onArrived: (Boolean) -> Unit) {
        if (isWalking) { onArrived(false); return }
        isWalking = true
        walkCallback = onArrived

        val startX = overlayParams?.x ?: 0
        val startY = overlayParams?.y ?: 0
        val dx = targetX - startX
        val dy = targetY - startY
        val distance = Math.hypot(dx.toDouble(), dy.toDouble())

        // 自动翻转朝向
        val needFlip = dx < 0
        ivPet.animate().scaleX(if (needFlip) -1f else 1f).setDuration(200).start()

        // 切换到 Move 动画
        playAnimation("Move")
        wsClient?.reportAnim("Move")

        // 分段插值动画（20帧/秒，约 50ms 一帧）
        val frames = maxOf(1, (durationMs / 50).toInt())
        val stepX = dx / frames
        val stepY = dy / frames

        fun animateStep(frame: Int) {
            if (!isWalking) { onArrived(false); return }
            if (frame >= frames) {
                // 到达
                overlayParams!!.x = targetX
                overlayParams!!.y = targetY
                wm.updateViewLayout(rootView, overlayParams)
                saveTransform()
                isWalking = false
                walkCallback = null
                // 切回 Idle
                playAnimation("Default")
                wsClient?.reportAnim("Default")
                wsClient?.reportTransform()
                onArrived(true)
                return
            }
            val nx = startX + stepX * frame
            val ny = startY + stepY * frame
            overlayParams!!.x = nx.toInt()
            overlayParams!!.y = ny.toInt()
            wm.updateViewLayout(rootView, overlayParams)
            mainHandler.postDelayed({ animateStep(frame + 1) }, 50)
        }

        animateStep(1)
    }

    /** 保存变换到 SharedPreferences */
    private fun saveTransform() {
        val t = PetTransform(
            x = (overlayParams?.x ?: 0).toFloat(),
            y = (overlayParams?.y ?: 0).toFloat(),
            scale = petScale,
            flipX = ivPet.scaleX < 0,
            visible = rootView.visibility == View.VISIBLE
        )
        PetTransform.save(this, t)
    }

    override fun onDestroy() {
        instance = null
        wsClient?.stop()
        try { wm.removeView(rootView) } catch (_: Exception) {}
        super.onDestroy()
    }
}
