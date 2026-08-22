package com.arkpet.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.arkpet.R
import com.arkpet.core.RoleRegistry
import com.arkpet.core.RoleInfo
import com.arkpet.core.SkinInfo
import com.arkpet.net.PetTransform
import com.arkpet.net.WsClient
import com.arkpet.updater.UpdateChecker
import com.arkpet.updater.UpdateWorker
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import java.io.File
import java.util.concurrent.TimeUnit

class PetOverlayService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private lateinit var ivPet: ImageView
    private var currentRole: RoleInfo = RoleRegistry.roles.first()
    private var currentSkin: SkinInfo = currentRole.defaultSkin()
    private var anim = "Relax"
    private var overlayParams: WindowManager.LayoutParams? = null
    private var wsClient: WsClient? = null
    private var petScale = 1.0f
    private val baseSizePx: Int by lazy { (256 * resources.displayMetrics.density).toInt() }
    private lateinit var scaleDetector: ScaleGestureDetector
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var lastTouchTime = 0L
    private var touchCount = 0
    private var isWalking = false
    private var walkCallback: ((Boolean) -> Unit)? = null
    private var behaviorLoop: Runnable? = null
    private var behaviorEnabled: Boolean = true
    private var chatVisible = false
    private var chatView: View? = null
    private var chatParams: WindowManager.LayoutParams? = null

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
        enqueueUpdateWorker()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startForegroundCompat()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        loadPrefs()
        setupOverlay()
        startBehaviorLoop()
    }

    private fun sp() = getSharedPreferences("arkpet", MODE_PRIVATE)
    private fun loadPrefs() {
        currentRole = RoleRegistry.byId(sp().getString("role_id", currentRole.id) ?: "") ?: currentRole
        currentSkin = currentRole.skins.find { it.id == (sp().getString("skin_id", currentSkin.id) ?: "") }
            ?: currentRole.defaultSkin()
        anim = sp().getString("current_anim", "Relax") ?: "Relax"
        behaviorEnabled = sp().getBoolean("behavior_enabled", true)
    }
    private fun savePrefs() {
        sp().edit().apply {
            putString("role_id", currentRole.id)
            putString("skin_id", currentSkin.id)
            putString("current_anim", anim)
            putBoolean("behavior_enabled", behaviorEnabled)
            apply()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL_ID, "桌宠", android.app.NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("初雪桌宠运行中")
            .setContentText("点击弹出消息输入框")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .build()
        startForeground(1, n)
    }

    private fun setupOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val saved = PetTransform.load(this)
        overlayParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
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
        ivPet.layoutParams = ViewGroup.LayoutParams(baseSizePx, baseSizePx)
        ivPet.setOnLongClickListener {
            val idx = currentRole.skins.indexOfFirst { it.id == currentSkin.id }.let { if (it < 0) 0 else it }
            currentSkin = currentRole.skins.getOrElse((idx + 1) % currentRole.skins.size) { currentRole.defaultSkin() }
            loadAnim()
            true
        }
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector) = true
        })
        petScale = saved.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        applyScale()
        ivPet.scaleX = if (saved.flipX) -1f else 1f
        rootView.visibility = if (saved.visible) View.VISIBLE else View.INVISIBLE
        rootView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchCount++; touchStartX = ev.rawX; touchStartY = ev.rawY
                    lastTouchTime = System.currentTimeMillis()
                }
                MotionEvent.ACTION_UP -> {
                    val dt = System.currentTimeMillis() - lastTouchTime
                    val moved = hypot(ev.rawX - touchStartX, ev.rawY - touchStartY)
                    wsClient?.reportTouch(ev.rawX, ev.rawY, ev.actionMasked, dt)
                    if (moved > 20f) {
                        overlayParams?.let {
                            it.x = (it.x + ev.rawX - touchStartX).toInt()
                            it.y = (it.y + ev.rawY - touchStartY).toInt()
                            wm.updateViewLayout(rootView, it)
                        }
                        saveTransform(); wsClient?.reportTransform()
                    } else if (dt < 300L) {
                        if (touchCount >= 2) {
                            if (chatVisible) hideChatInput() else showChatInput()
                        } else {
                            wsClient?.reportTouch(ev.rawX, ev.rawY, MotionEvent.ACTION_UP, dt)
                            playAnimation("Interact")
                        }
                    }
                    touchCount = 0
                }
                MotionEvent.ACTION_CANCEL -> touchCount = 0
            }
            scaleDetector.onTouchEvent(ev)
            false
        }
        wm.addView(rootView, overlayParams)
        loadAnim()
    }

    fun playAnimation(name: String, speed: Double = 1.0, loop: Boolean = true, flipX: Boolean = false) {
        val valid = listOf("Default", "Interact", "Move", "Relax", "Sit", "Sleep", "Special")
        anim = if (name in valid) name else "Relax"
        ivPet.scaleX = if (flipX) -1f else 1f
        loadAnim(); wsClient?.reportAnim(anim)
    }

    fun setSkin(name: String) {
        val skin = RoleRegistry.allSkins().find { it.id == name } ?: return
        if (currentSkin.id == name) return
        currentSkin = skin
        currentRole = RoleRegistry.roleOfSkin(name) ?: currentRole
        loadAnim(); savePrefs()
    }

    private fun loadAnim() {
        val primary = Glide.with(this).load("file:///android_asset/pet/${currentSkin.id}_${anim}.webp")
        if (currentSkin.id != "base") primary.error(Glide.with(this).load("file:///android_asset/pet/base_${anim}.webp"))
        primary.transition(DrawableTransitionOptions.withCrossFade(200)).into(ivPet)
    }

    fun setBehaviorEnabled(v: Boolean) {
        behaviorEnabled = v
        savePrefs()
    }

    fun showBubble(text: String) {
        // 复用旧逻辑：用 toast 或悬浮窗显示气泡（临时需求，后续可替换为气泡 View）
        toast(text.take(50))
    }

    private fun startBehaviorLoop() {
        val loop = object : Runnable {
            override fun run() {
                try {
                    if (!behaviorEnabled) { mainHandler.postDelayed(this, 60_000L); return }
                    if (!isWalking && rootView.visibility == View.VISIBLE) {
                        when ((0..99).random()) {
                            in 0..14 -> {
                                val dm = resources.displayMetrics
                                val cx = overlayParams?.x ?: 0; val cy = overlayParams?.y ?: 0
                                val tx = (cx + (-160..160).random()).coerceIn(0, (dm.widthPixels - baseSizePx).coerceAtLeast(0))
                                val ty = (cy + (-120..120).random()).coerceIn(0, (dm.heightPixels - baseSizePx).coerceAtLeast(0))
                                if (tx != cx || ty != cy) walkTo(tx, ty, 2200L) {}
                            }
                            in 15..44 -> playAnimation("Sit")
                            in 45..74 -> playAnimation("Sleep")
                            else -> playAnimation("Relax")
                        }
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, (9000L..22000L).random())
            }
        }
        mainHandler.postDelayed(loop, 6000L)
        behaviorLoop = loop
    }

    fun showChatInput() {
        if (chatVisible) return
        val chatRoot = LayoutInflater.from(this).inflate(R.layout.overlay_chat, null) as LinearLayout
        val et = chatRoot.findViewById<EditText>(R.id.et_chat_input)
        val btnSend = chatRoot.findViewById<Button>(R.id.btn_send_chat)
        val btnClose = chatRoot.findViewById<ImageButton>(R.id.btn_close_chat)
        val btnFile = chatRoot.findViewById<ImageButton>(R.id.btn_file_chat)
        btnSend.setOnClickListener {
            val text = et.text?.toString()?.trim() ?: ""
            if (text.isEmpty()) return@setOnClickListener
            wsClient?.reportChatInput(text)
            et.setText("")
        }
        btnSend.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { btnSend.performClick(); true } else false
        }
        btnClose.setOnClickListener { hideChatInput() }
        btnFile.setOnClickListener { pickFile() }
        // 位置：在 pet 上方，y 负偏移但 clamp 到 0
        val petCenterX = (overlayParams?.x ?: 0) + ivPet.width / 2
        val petTop = (overlayParams?.y ?: 0)
        val chatW = 380; val chatH = 64
        val lp = WindowManager.LayoutParams(chatW, chatH,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (petCenterX - chatW / 2).coerceIn(0, resources.displayMetrics.widthPixels - chatW)
            y = (petTop - chatH - 8).coerceIn(0, resources.displayMetrics.heightPixels - chatH)
        }
        chatView = chatRoot; chatParams = lp
        wm.addView(chatRoot, lp); chatVisible = true
    }

    fun hideChatInput() {
        if (!chatVisible || chatParams == null) return
        try { wm.removeView(chatView!!); chatVisible = false } catch (_: Exception) {}
        chatView = null; chatParams = null
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }
        try { startActivity(intent) }
        catch (_: ActivityNotFoundException) { toast("没有可用的文件选择器") }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun applyScale() {
        val lp = ivPet.layoutParams
        lp.width = (baseSizePx * petScale).toInt().coerceAtLeast(48); lp.height = lp.width; ivPet.layoutParams = lp
    }
    fun setSize(scale: Float) { petScale = scale.coerceIn(MIN_SCALE, MAX_SCALE); mainHandler.post { applyScale() }; saveTransform() }
    fun applyTransform(t: PetTransform) {
        petScale = t.scale; applyScale(); ivPet.scaleX = if (t.flipX) -1f else 1f
        rootView.visibility = if (t.visible) View.VISIBLE else View.INVISIBLE
        overlayParams?.apply { x = t.x.toInt(); y = t.y.toInt(); wm.updateViewLayout(rootView, this) }
    }
    fun walkTo(targetX: Int, targetY: Int, durationMs: Long, onArrived: (Boolean) -> Unit) {
        if (isWalking) { onArrived(false); return }
        isWalking = true; walkCallback = onArrived
        val startX = overlayParams?.x ?: 0; val startY = overlayParams?.y ?: 0
        val dx = targetX - startX; val dy = targetY - startY
        ivPet.animate().scaleX(if (dx < 0) -1f else 1f).setDuration(200).start()
        playAnimation("Move")
        val frames = maxOf(1, (durationMs / 50).toInt())
        val stepX = dx / frames; val stepY = dy / frames
        fun step(f: Int) {
            if (!isWalking) { onArrived(false); return }
            if (f >= frames) {
                overlayParams?.apply { x = targetX; y = targetY; wm.updateViewLayout(rootView, this) }
                saveTransform(); isWalking = false; walkCallback = null
                playAnimation("Default"); wsClient?.reportTransform(); onArrived(true)
                return
            }
            overlayParams?.apply { x = (startX + stepX * f).toInt(); y = (startY + stepY * f).toInt(); wm.updateViewLayout(rootView, this) }
            mainHandler.postDelayed({ step(f + 1) }, 50)
        }
        step(1)
    }
    private fun saveTransform() {
        PetTransform.save(this, PetTransform(
            x = (overlayParams?.x ?: 0).toFloat(), y = (overlayParams?.y ?: 0).toFloat(),
            scale = petScale, flipX = ivPet.scaleX < 0, visible = rootView.visibility == View.VISIBLE
        ))
    }
    private fun enqueueUpdateWorker() {
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "update_check", androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(UpdateWorker::class.java, 12, TimeUnit.HOURS).build()
        )
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        instance = null
        behaviorLoop?.let { mainHandler.removeCallbacks(it) }
        wsClient?.stop()
        try { wm.removeView(rootView) } catch (_: Exception) {}
        super.onDestroy()
    }
}
