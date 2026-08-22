package com.arkpet.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.arkpet.R
import com.arkpet.net.WsClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import java.lang.ref.WeakReference

/**
 * 桌宠悬浮窗：Glide 播动画 WebP + 皮肤实时切换按钮 + 拖动
 * 修复：Glide 绑定 View 生命周期、Handler 清理、lateinit 安全化、wsClient 单例
 */
class PetOverlayService : Service() {

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var ivPet: ImageView? = null
    private var skin = "base"      // base / snow
    private var anim = "Relax"     // 当前动画
    private var overlayParams: WindowManager.LayoutParams? = null
    private var wsClient: WsClient? = null

    companion object {
        const val CHANNEL_ID = "arkpet_pet"
        var instance: PetOverlayService? = null
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("server_url")
            ?: getSharedPreferences("arkpet", MODE_PRIVATE).getString("server_url", "")
            ?: "ws://127.0.0.1:9100/ws"
        // wsClient 单例：仅首次创建，避免 BootReceiver + onStartCommand 重复连接
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
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 200
        }

        rootView = LayoutInflater.from(this).inflate(R.layout.overlay_pet, null)
        ivPet = rootView?.findViewById(R.id.iv_pet)
        val btnSkin = rootView?.findViewById<View>(R.id.btn_skin)

        // 皮肤切换：点击实时切换 base/snow，保持当前动画
        btnSkin?.setOnClickListener {
            skin = if (skin == "base") "snow" else "base"
            loadAnim()
        }

        // 拖动
        var dx = 0f; var dy = 0f
        rootView?.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { dx = ev.rawX - (overlayParams?.x ?: 0); dy = ev.rawY - (overlayParams?.y ?: 0); false }
                MotionEvent.ACTION_MOVE -> {
                    overlayParams?.x = (ev.rawX - dx).toInt()
                    overlayParams?.y = (ev.rawY - dy).toInt()
                    overlayParams?.let { wm?.updateViewLayout(rootView!!, it) }
                    true
                }
                else -> false
            }
        }

        rootView?.let { wm?.addView(it, overlayParams!!) }
        loadAnim()
    }

    /** 播放当前皮肤+动画（assets/pet/{skin}_{anim}.webp） */
    private fun loadAnim() {
        val uri = "file:///android_asset/pet/${skin}_${anim}.webp"
        // Glide 绑定 ImageView 的 Context（而非 Service），随 View 生命周期自动清理，避免 Bitmap 累积
        ivPet?.let {
            Glide.with(it.context)
                .load(uri)
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(it)
        }
    }

    /** 供 MCP 调用：切换皮肤（实时） */
    fun setSkin(name: String) {
        if (name != "base" && name != "snow") return
        if (skin == name) return
        skin = name
        loadAnim()
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
                x = (overlayParams?.x ?: 80)
                y = (overlayParams?.y ?: 200) - 80
            }
            wm?.addView(tv, lp)
            // 用 WeakReference 避免捕获 TextView 导致泄漏；onDestroy 清理所有回调
            val ref = WeakReference(tv)
            mainHandler.postDelayed({
                ref.get()?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            }, 3500)
        } catch (_: Exception) {}
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 供 MCP 调用：切换动画 */
    fun playAnimation(name: String) {
        val valid = listOf("Default", "Interact", "Move", "Relax", "Sit", "Sleep", "Special")
        anim = if (name in valid) name else "Relax"
        loadAnim()
    }

    override fun onDestroy() {
        instance = null
        // 清理所有主线程回调，防止泄漏
        mainHandler.removeCallbacksAndMessages(null)
        wsClient?.stop()
        rootView?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
