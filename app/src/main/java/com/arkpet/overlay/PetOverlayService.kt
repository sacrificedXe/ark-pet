package com.arkpet.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable2
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.arkpet.App
import com.arkpet.R
import com.arkpet.core.RoleInfo
import com.arkpet.core.RoleRegistry
import com.arkpet.core.SkinInfo
import com.arkpet.net.PetTransform
import com.arkpet.net.WsClient
import com.arkpet.updater.UpdateWorker
import com.arkpet.util.PetLog
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 桌宠悬浮窗服务。
 *
 * 重写要点（v0.4.0）——每一条都对应一个真实踩过的坑：
 * 1. startForeground 先于一切：Android 8+ 用 startForegroundService 拉起后 5s 内不进前台会被
 *    系统 ANR 杀掉，而 Study OS 杀得更快，表现就是「点了启动什么也没有」。
 * 2. 悬浮窗权限缺失时不静默 stopSelf，而是明确落盘 + Toast，并停在可诊断状态。
 * 3. asset 缺失不再直接 stopSelf：降级显示占位色块，桌宠先出来，再报资源问题。
 *    「什么都不显示」永远是最难排查的失败模式。
 * 4. 首帧位置不用 (0,0)：状态栏/挖孔区域可能完全盖住，默认落在右侧中部。
 * 5. WsClient 连不上不影响桌宠显示：网络是可选功能，显示是核心功能，两者解耦。
 * 6. 拖动改成 ACTION_MOVE 实时跟手（原实现只在 UP 时跳一次，手感像卡顿）。
 *
 * v0.4.5 新增：
 * 7. 动作队列 + 优先级：用户交互(100) > 走路到达(80) > 自主行为(10)。同优先级先进先出。
 *    playAnimation/ walkTo/ behaviorLoop 统一入队，执行前显式 stop 当前 AnimatedImageDrawable 并清理
 *    ImageView pivot/scale/translation，防止切动画残留变换导致偏移。
 * 8. WS 路径显式 /ws，握手失败打印完整 HTTP 响应码。
 */
class PetOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "arkpet_pet"
        const val NOTIF_ID = 1
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 2.5f
        private const val TAG = "Overlay"
        // 双击窗口 300ms 是「两次抬手之间」的间隔上限。
        // 单次点击时长上限单列成 TAP_MAX_MS：原实现把两者混用成同一个 300ms，
        // 手指按住超过 300ms 再松开就既不算点击也不算双击，直接被丢弃。
        private const val DOUBLE_TAP_MS = 320L
        private const val TAP_MAX_MS = 500L
        private const val LONG_PRESS_MS = 900L
        private const val DRAG_SLOP_PX = 12f
        // 一次性动作：播完回 Relax，不能无限循环
        private val ONE_SHOT_ANIMS = setOf("Interact", "Special")
        // Drawable 缓存上限。单个 webp 解出来占几十 MB，缓存太多会 OOM
        private const val ANIM_CACHE_MAX = 4

        // 动作优先级：越大越优先
        private const val PRIO_USER = 100   // 用户点击/长按/双击触发的 Interact/Special
        private const val PRIO_WALK_ARRIVE = 80 // walkTo 到达回 Relax
        private const val PRIO_AUTO = 10    // 自主行为 Sit/Sleep/Relax/Walk

        @Volatile
        var instance: PetOverlayService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    // webp 解码放后台：cloud_trail_Sleep 有 120 帧、2MB，主线程解会卡住整个悬浮窗
    private val decodeScope = Executors.newSingleThreadExecutor()
    private var currentAnimDrawable: AnimatedImageDrawable? = null
    // 已解码的 Drawable 缓存，避免同一动作反复解码
    private val animCache = LinkedHashMap<String, Drawable>()

    // 动作队列：(priority, sequence, actionName, flipX, speed, loop, onComplete)
    private val animQueue = ConcurrentLinkedQueue<AnimTask>()
    private var animSeq = 0L
    private val isAnimPlaying = AtomicBoolean(false)

    private var wm: WindowManager? = null
    private var rootView: View? = null
    private var ivPet: ImageView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    private var currentRole: RoleInfo = RoleRegistry.roles.first()
    private var currentSkin: SkinInfo = currentRole.defaultSkin()
    private var anim = "Relax"

    private var wsClient: WsClient? = null
    private var petScale = 1.0f
    private val baseSizePx: Int by lazy { (160 * resources.displayMetrics.density).toInt() }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var downTime = 0L
    private var dragging = false
    private var lastUpTime = 0L

    private var isWalking = false
    private var behaviorEnabled = true
    private var behaviorLoop: Runnable? = null

    private var chatView: View? = null
    private var chatVisible = false

    private var overlayReady = false

    /** 动作任务：优先级高者先执行，同优先级按序号 FIFO */
    private data class AnimTask(
        val priority: Int,
        val seq: Long,
        val name: String,
        val flipX: Boolean?,
        val speed: Double,
        val loop: Boolean,
        val onComplete: (() -> Unit)?
    )

    // ---------------------------------------------------------------- 生命周期

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        PetLog.i(TAG, "onCreate")

        // 1) 前台通知优先，抢在系统 5s 计时之前
        try {
            startForegroundCompat()
            PetLog.i(TAG, "startForeground 成功")
        } catch (e: Exception) {
            PetLog.e(TAG, "startForeground 失败，服务可能被系统回收", e)
        }

        // 2) 权限校验：没有悬浮窗权限就别硬加 View（addView 会抛 BadTokenException）
        if (!Settings.canDrawOverlays(this)) {
            PetLog.e(TAG, "缺少悬浮窗权限，无法显示桌宠")
            toast("缺少悬浮窗权限，请在设置里授予后重新启动桌宠")
            return
        }

        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            loadPrefs()
            setupOverlay()
            overlayReady = true
            PetLog.i(TAG, "悬浮窗创建完成 skin=${currentSkin.id} anim=$anim scale=$petScale")
            startBehaviorLoop()
        } catch (e: Exception) {
            PetLog.e(TAG, "悬浮窗创建失败", e)
            toast("桌宠启动失败：${e.javaClass.simpleName} ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra("server_url")?.takeIf { it.isNotBlank() }
            ?: sp().getString("server_url", "").orEmpty()
        PetLog.i(TAG, "onStartCommand url=${url.ifBlank { "(空)" }} overlayReady=$overlayReady")

        connectWs(url)

        runCatching { enqueueUpdateWorker() }
            .onFailure { PetLog.e(TAG, "UpdateWorker 入队失败", it) }

        return START_STICKY
    }

    override fun onDestroy() {
        PetLog.i(TAG, "onDestroy")
        instance = null
        behaviorLoop?.let { mainHandler.removeCallbacks(it) }
        // 清空动画队列并停止当前动画
        animQueue.clear()
        isAnimPlaying.set(false)
        stopCurrentAnim()
        synchronized(animCache) { animCache.clear() }
        runCatching { decodeScope.shutdownNow() }
        runCatching { wsClient?.stop() }
        hideChatInput()
        runCatching { rootView?.let { wm?.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 前台通知

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "桌宠", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setContentTitle("桌宠运行中")
            .setContentText("双击桌宠打开输入框")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
    }

    // ---------------------------------------------------------------- 偏好读写

    private fun sp() = getSharedPreferences("arkpet", MODE_PRIVATE)

    private fun loadPrefs() {
        val roleId = sp().getString("role_id", currentRole.id).orEmpty()
        currentRole = RoleRegistry.byId(roleId) ?: RoleRegistry.roles.first()
        val skinId = sp().getString("skin_id", currentRole.defaultSkin().id).orEmpty()
        currentSkin = currentRole.skins.find { it.id == skinId } ?: currentRole.defaultSkin()
        anim = sp().getString("current_anim", "Relax") ?: "Relax"
        behaviorEnabled = sp().getBoolean("behavior_enabled", true)
    }

    private fun savePrefs() {
        sp().edit()
            .putString("role_id", currentRole.id)
            .putString("skin_id", currentSkin.id)
            .putString("current_anim", anim)
            .putBoolean("behavior_enabled", behaviorEnabled)
            .apply()
    }

    // ---------------------------------------------------------------- 悬浮窗

    private fun setupOverlay() {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val dm = resources.displayMetrics
        val saved = PetTransform.load(this)
        petScale = saved.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        val sizePx = (baseSizePx * petScale).toInt().coerceAtLeast(48)

        // 默认位置：右侧偏下，绝不落在 (0,0) 被状态栏吃掉
        val defaultX = (dm.widthPixels - sizePx - 24).coerceAtLeast(0)
        val defaultY = (dm.heightPixels * 0.55f).toInt().coerceAtMost(dm.heightPixels - sizePx)
        val px = if (saved.x <= 0f && saved.y <= 0f) defaultX else saved.x.toInt()
        val py = if (saved.x <= 0f && saved.y <= 0f) defaultY else saved.y.toInt()

        overlayParams = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = px.coerceIn(0, (dm.widthPixels - 48).coerceAtLeast(0))
            y = py.coerceIn(0, (dm.heightPixels - 48).coerceAtLeast(0))
        }

        val view = LayoutInflater.from(this).inflate(R.layout.overlay_pet, null)
        val img = view.findViewById<ImageView>(R.id.iv_pet)
            ?: throw IllegalStateException("overlay_pet.xml 缺少 iv_pet")
        img.layoutParams = FrameLayout.LayoutParams(sizePx, sizePx)
        img.scaleX = if (saved.flipX) -1f else 1f

        // 触摸监听必须挂在 ImageView 自己身上，且绝不能用 setOnLongClickListener。
        // 原因：setOnLongClickListener 会把 ImageView 的 longClickable 置 true，
        // View.onTouchEvent 见到 clickable||longClickable 就 return true 把事件全吃掉，
        // 父 FrameLayout 上的 OnTouchListener 一个 MOVE 都收不到 ——
        // 表现就是「拖不动 + 双击不出气泡」，两个症状同一个原因。
        // 长按切皮肤改为在 handleTouch 里按住时长自行判定。
        img.isClickable = false
        img.isLongClickable = false
        img.setOnTouchListener { _, ev -> handleTouch(ev) }

        rootView = view
        ivPet = img

        wm!!.addView(view, overlayParams)
        PetLog.i(TAG, "addView 完成 pos=(${overlayParams!!.x},${overlayParams!!.y}) size=$sizePx")

        loadAnim()
    }

    /** 触摸：DOWN 记锚点 → MOVE 实时跟手 → UP 判定单击/双击/拖动结束 */
    private fun handleTouch(ev: MotionEvent): Boolean {
        val params = overlayParams ?: return false
        val view = rootView ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                downParamX = params.x; downParamY = params.y
                downTime = System.currentTimeMillis()
                dragging = false
                wsClient?.reportTouch(ev.rawX, ev.rawY, MotionEvent.ACTION_DOWN, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragging && hypot(dx, dy) > DRAG_SLOP_PX) dragging = true
                if (dragging) {
                    val dm = resources.displayMetrics
                    params.x = (downParamX + dx).toInt().coerceIn(0, (dm.widthPixels - 48).coerceAtLeast(0))
                    params.y = (downParamY + dy).toInt().coerceIn(0, (dm.heightPixels - 48).coerceAtLeast(0))
                    runCatching { wm?.updateViewLayout(view, params) }
                }
            }
            MotionEvent.ACTION_UP -> {
                val now = System.currentTimeMillis()
                val held = now - downTime
                wsClient?.reportTouch(ev.rawX, ev.rawY, MotionEvent.ACTION_UP, held)
                if (dragging) {
                    saveTransform()
                    wsClient?.reportTransform()
                    lastUpTime = 0L
                } else if (held >= LONG_PRESS_MS) {
                    // 长按切皮肤：自己判时长，不用 setOnLongClickListener（会吞掉 MOVE 事件）
                    lastUpTime = 0L
                    cycleSkin()
                } else if (held < TAP_MAX_MS) {
                    if (lastUpTime > 0 && now - lastUpTime < DOUBLE_TAP_MS) {
                        lastUpTime = 0L
                        if (chatVisible) hideChatInput() else showChatInput()
                    } else {
                        lastUpTime = now
                        playAnimation("Interact")
                    }
                } else {
                    lastUpTime = 0L
                }
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; lastUpTime = 0L }
        }
        return true
    }

    /**
     * 长按切换：跨角色循环所有皮肤。
     * 只在当前角色内循环的话，单皮肤角色（云迹只有澄澈空）长按会毫无反应。
     */
    private fun cycleSkin() {
        val all = RoleRegistry.allSkins()
        if (all.isEmpty()) return
        val idx = all.indexOfFirst { it.id == currentSkin.id }.coerceAtLeast(0)
        val next = all[(idx + 1) % all.size]
        currentSkin = next
        currentRole = RoleRegistry.roleOfSkin(next.id) ?: currentRole
        PetLog.i(TAG, "长按切换 → ${currentRole.name}/${currentSkin.name}")
        loadAnim(); savePrefs()
        toast("${currentRole.name} · ${currentSkin.name}")
    }

    /**
     * 加载动画帧。三级降级：
     *   当前皮肤 webp → base 皮肤同动作 webp → base_Default → 占位色块
     * 任何一级成功就停，绝不出现「桌宠不显示且无提示」。
     *
     * 解码器换成 ImageDecoder（API 28+，本项目 minSdk 28）：
     * Glide 4.16 本体不带动画 WebP 解码器，多帧 VP8X 只会解出第一帧 ——
     * 表现就是「走路动作还是不行」「动作看起来没实现」，其实是贴图定格。
     * ImageDecoder 原生支持 ANIM webp，返回 AnimatedImageDrawable，start() 就动。
     */
    private fun loadAnim() {
        val img = ivPet ?: return
        val candidates = buildList {
            add("pet/${currentSkin.id}_$anim.webp")
            // 先在同一皮肤内降级：跨皮肤兜底会让「云迹」显示成初雪的图，
            // 表现为「皮肤显示出错」——比不显示更容易被误判成资源损坏。
            add("pet/${currentSkin.id}_Relax.webp")
            add("pet/${currentSkin.id}_Sit.webp")
            add("pet/${currentSkin.id}_Move.webp")
            if (currentSkin.id != "base") add("pet/base_$anim.webp")
            add("pet/base_Relax.webp")
            add("pet/base_Default.webp")
        }.distinct()

        val existing = candidates.firstOrNull { assetExists(it) }
        if (existing == null) {
            PetLog.e(TAG, "全部候选资源均缺失: $candidates，降级为占位色块")
            stopCurrentAnim()
            img.setImageDrawable(null)
            img.setBackgroundColor(0x88FF6688.toInt())
            toast("动画资源缺失，已显示占位块")
            return
        }
        if (existing != candidates.first()) {
            PetLog.w(TAG, "资源降级: ${candidates.first()} 缺失 → $existing")
        }
        img.setBackgroundColor(0x00000000)

        val loadedFor = anim
        decodeScope.execute {
            // 同一路径只解一次。走路每次都重解 900KB / 41 帧的话，
            // 后台线程被占满，动作切换会延迟一两秒才出来。
            val drawable = synchronized(animCache) { animCache[existing] } ?: runCatching {
                // 用 ByteBuffer 而不是 createSource(AssetManager, String)：
                // 后者在部分 ROM 的 API 28 实现上不可用，ByteBuffer 重载是 28 起的稳定 API。
                val bytes = assets.open(existing).use { it.readBytes() }
                val src = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                ImageDecoder.decodeDrawable(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrElse {
                PetLog.e(TAG, "ImageDecoder 解码失败 $existing: ${it.message}")
                null
            }?.also { d ->
                synchronized(animCache) {
                    if (animCache.size >= ANIM_CACHE_MAX) {
                        val victim = animCache.keys.firstOrNull()
                        victim?.let { animCache.remove(it) }
                    }
                    animCache[existing] = d
                }
            }
            mainHandler.post {
                if (drawable == null) {
                    img.setBackgroundColor(0x88FF6688.toInt())
                    return@post
                }
                // 解码是异步的，回来时用户可能已经切了动作/皮肤，丢弃过期结果
                if (anim != loadedFor) {
                    PetLog.w(TAG, "丢弃过期解码结果 $loadedFor（当前 $anim）")
                    return@post
                }
                stopCurrentAnim()
                img.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.clearAnimationCallbacks()
                    if (loadedFor in ONE_SHOT_ANIMS) {
                        // Interact/Special 是一次性动作。不注册回调的话最后一帧永久定格，
                        // 看起来像卡死；播完自动回 Relax。
                        drawable.repeatCount = 0
                        drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                            override fun onAnimationEnd(d: Drawable?) {
                                if (anim == loadedFor) playAnimation("Relax")
                            }
                        })
                    } else {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    }
                    drawable.start()
                    currentAnimDrawable = drawable
                    PetLog.i(TAG, "动画播放 $existing oneShot=${loadedFor in ONE_SHOT_ANIMS}")
                } else {
                    currentAnimDrawable = null
                    PetLog.i(TAG, "静态贴图 $existing")
                }
            }
        }
    }

    private fun stopCurrentAnim() {
        currentAnimDrawable?.let { runCatching { it.stop() } }
        currentAnimDrawable = null
    }

    private fun assetExists(path: String): Boolean = try {
        applicationContext.assets.open(path).close(); true
    } catch (_: Exception) { false }

    // ---------------------------------------------------------------- 对外动作（入队）

    fun playAnimation(name: String, speed: Double = 1.0, loop: Boolean = true, flipX: Boolean? = null, priority: Int = PRIO_USER) {
        val valid = listOf("Default", "Interact", "Move", "Relax", "Sit", "Sleep", "Special")
        val target = if (name in valid) name else "Relax"
        enqueueAnim(target, priority, flipX, speed, loop, null)
    }

    private fun enqueueAnim(name: String, priority: Int, flipX: Boolean?, speed: Double, loop: Boolean, onComplete: (() -> Unit)?) {
        mainHandler.post {
            val seq = animSeq++
            animQueue.offer(AnimTask(priority, seq, name, flipX, speed, loop, onComplete))
            // 按优先级降序、序号升序排序（ConcurrentLinkedQueue 不支持排序，转 List 排完再重建）
            val sorted = animQueue.toMutableList().sortedWith(compareByDescending<AnimTask> { it.priority }.thenBy { it.seq })
            animQueue.clear()
            sorted.forEach { animQueue.offer(it) }
            tryRunNextAnim()
        }
    }

    /** 取队头执行；正在播放时不抢占，等 onAnimationEnd 回调里再取下一个 */
    private fun tryRunNextAnim() {
        if (isAnimPlaying.get()) return
        val task = animQueue.poll() ?: return
        runAnimTask(task)
    }

    private fun runAnimTask(task: AnimTask) {
        isAnimPlaying.set(true)
        val img = ivPet ?: run { isAnimPlaying.set(false); tryRunNextAnim(); return }
        // 关键：切动画前显式清理 ImageView 残留变换，防止不同 webp anchor 导致偏移
        img.pivotX = 0f; img.pivotY = 0f
        img.translationX = 0f; img.translationY = 0f
        img.rotation = 0f
        // scaleX 由 flipX 决定，不归零
        if (task.flipX != null) img.scaleX = if (task.flipX!!) -1f else 1f

        anim = task.name
        val candidates = buildList {
            add("pet/${currentSkin.id}_${task.name}.webp")
            add("pet/${currentSkin.id}_Relax.webp")
            add("pet/${currentSkin.id}_Sit.webp")
            add("pet/${currentSkin.id}_Move.webp")
            if (currentSkin.id != "base") add("pet/base_${task.name}.webp")
            add("pet/base_Relax.webp")
            add("pet/base_Default.webp")
        }.distinct()

        val existing = candidates.firstOrNull { assetExists(it) }
        if (existing == null) {
            PetLog.e(TAG, "全部候选资源均缺失: $candidates，降级为占位色块")
            stopCurrentAnim()
            img.setImageDrawable(null)
            img.setBackgroundColor(0x88FF6688.toInt())
            toast("动画资源缺失，已显示占位块")
            isAnimPlaying.set(false)
            tryRunNextAnim()
            task.onComplete?.invoke()
            return
        }
        if (existing != candidates.first()) {
            PetLog.w(TAG, "资源降级: ${candidates.first()} 缺失 → $existing")
        }
        img.setBackgroundColor(0x00000000)

        val loadedFor = task.name
        decodeScope.execute {
            val drawable = synchronized(animCache) { animCache[existing] } ?: runCatching {
                val bytes = assets.open(existing).use { it.readBytes() }
                val src = ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                ImageDecoder.decodeDrawable(src) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = false
                }
            }.getOrElse {
                PetLog.e(TAG, "ImageDecoder 解码失败 $existing: ${it.message}")
                null
            }?.also { d ->
                synchronized(animCache) {
                    if (animCache.size >= ANIM_CACHE_MAX) {
                        val victim = animCache.keys.firstOrNull()
                        victim?.let { animCache.remove(it) }
                    }
                    animCache[existing] = d
                }
            }
            mainHandler.post {
                if (drawable == null) {
                    img.setBackgroundColor(0x88FF6688.toInt())
                    isAnimPlaying.set(false)
                    tryRunNextAnim()
                    task.onComplete?.invoke()
                    return@post
                }
                // 解码回来时可能已被更高优先级任务抢占，检查队头是否还是自己
                val head = animQueue.peek()
                if (head == null || head.seq != task.seq) {
                    PetLog.w(TAG, "丢弃过期解码结果 $loadedFor（已被更高优先级任务抢占）")
                    isAnimPlaying.set(false)
                    tryRunNextAnim()
                    task.onComplete?.invoke()
                    return@post
                }
                stopCurrentAnim()
                img.setImageDrawable(drawable)
                if (drawable is AnimatedImageDrawable) {
                    drawable.clearAnimationCallbacks()
                    val isOneShot = loadedFor in ONE_SHOT_ANIMS
                    if (isOneShot || !task.loop) {
                        drawable.repeatCount = 0
                        drawable.registerAnimationCallback(object : Animatable2.AnimationCallback() {
                            override fun onAnimationEnd(d: Drawable?) {
                                mainHandler.post {
                                    isAnimPlaying.set(false)
                                    // 一次性动作播完自动回 Relax（除非队列里已有更高优先级任务）
                                    if (isOneShot && anim == loadedFor && animQueue.isEmpty()) {
                                        enqueueAnim("Relax", PRIO_USER, null, 1.0, true, null)
                                    }
                                    tryRunNextAnim()
                                    task.onComplete?.invoke()
                                }
                            }
                        })
                    } else {
                        drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                    }
                    drawable.start()
                    currentAnimDrawable = drawable
                    PetLog.i(TAG, "动画播放 $existing oneShot=$isOneShot loop=${task.loop} prio=${task.priority}")
                } else {
                    currentAnimDrawable = null
                    PetLog.i(TAG, "静态贴图 $existing")
                    // 静态图视为已完成，直接跑下一个
                    isAnimPlaying.set(false)
                    tryRunNextAnim()
                    task.onComplete?.invoke()
                }
                wsClient?.reportAnim(anim)
            }
        }
    }

    private fun stopCurrentAnim() {
        currentAnimDrawable?.let { runCatching { it.stop() } }
        currentAnimDrawable = null
    }

    private fun assetExists(path: String): Boolean = try {
        applicationContext.assets.open(path).close(); true
    } catch (_: Exception) { false }

    fun setSkin(name: String) {
        val skin = RoleRegistry.allSkins().find { it.id == name } ?: run {
            PetLog.w(TAG, "未知皮肤: $name"); return
        }
        if (currentSkin.id == name) return
        mainHandler.post {
            // 切皮肤清空队列，立即播新皮肤 Relax
            animQueue.clear()
            isAnimPlaying.set(false)
            currentSkin = skin
            currentRole = RoleRegistry.roleOfSkin(name) ?: currentRole
            enqueueAnim("Relax", PRIO_USER, null, 1.0, true, null)
            savePrefs()
        }
    }

    fun setSize(scale: Float) {
        petScale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        mainHandler.post { applyScale(); saveTransform() }
    }

    fun setBehaviorEnabled(v: Boolean) {
        behaviorEnabled = v
        savePrefs()
        PetLog.i(TAG, "自主行为: $v")
    }

    fun showBubble(text: String) = mainHandler.post { toast(text.take(80)) }

    fun applyTransform(t: PetTransform) = mainHandler.post {
        val view = rootView ?: return@post
        petScale = t.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        applyScale()
        ivPet?.scaleX = if (t.flipX) -1f else 1f
        view.visibility = if (t.visible) View.VISIBLE else View.INVISIBLE
        overlayParams?.let {
            val dm = resources.displayMetrics
            it.x = t.x.toInt().coerceIn(0, (dm.widthPixels - 48).coerceAtLeast(0))
            it.y = t.y.toInt().coerceIn(0, (dm.heightPixels - 48).coerceAtLeast(0))
            runCatching { wm?.updateViewLayout(view, it) }
        }
    }

    private fun applyScale() {
        val img = ivPet ?: return
        val side = (baseSizePx * petScale).toInt().coerceAtLeast(48)
        img.layoutParams = FrameLayout.LayoutParams(side, side)
        img.requestLayout()
    }

    fun walkTo(targetX: Int, targetY: Int, durationMs: Long, onArrived: (Boolean) -> Unit) {
        mainHandler.post {
            val view = rootView
            val params = overlayParams
            if (view == null || params == null || isWalking) { onArrived(false); return@post }
            isWalking = true
            val dm = resources.displayMetrics
            val tx = targetX.coerceIn(0, (dm.widthPixels - 48).coerceAtLeast(0))
            val ty = targetY.coerceIn(0, (dm.heightPixels - 48).coerceAtLeast(0))
            val startX = params.x
            val startY = params.y
            val frames = (durationMs / 40).toInt().coerceIn(1, 200)
            ivPet?.animate()?.scaleX(if (tx < startX) -1f else 1f)?.setDuration(180)?.start()
            // 走路动作入队，优先级低于用户交互；到达时再入队 Relax（PRIO_WALK_ARRIVE）
            enqueueAnim("Move", PRIO_USER, if (tx < startX) true else false, 1.0, true, null)

            var f = 1
            val stepper = object : Runnable {
                override fun run() {
                    if (!isWalking || rootView == null) { isWalking = false; onArrived(false); return }
                    val ratio = f.toFloat() / frames
                    params.x = (startX + (tx - startX) * ratio).toInt()
                    params.y = (startY + (ty - startY) * ratio).toInt()
                    runCatching { wm?.updateViewLayout(view, params) }
                    if (f >= frames) {
                        isWalking = false
                        saveTransform()
                        // 走完入队 Relax，优先级 PRIO_WALK_ARRIVE（比自主行为高，比用户交互低）
                        enqueueAnim("Relax", PRIO_WALK_ARRIVE, null, 1.0, true, null)
                        wsClient?.reportTransform()
                        onArrived(true)
                        return
                    }
                    f++
                    mainHandler.postDelayed(this, 40)
                }
            }
            mainHandler.post(stepper)
        }
    }

    private fun saveTransform() {
        val params = overlayParams ?: return
        PetTransform.save(
            this,
            PetTransform(
                x = params.x.toFloat(), y = params.y.toFloat(),
                scale = petScale,
                flipX = (ivPet?.scaleX ?: 1f) < 0,
                visible = rootView?.visibility == View.VISIBLE
            )
        )
    }

    // ---------------------------------------------------------------- 自主行为

    private fun startBehaviorLoop() {
        val loop = object : Runnable {
            override fun run() {
                try {
                    if (behaviorEnabled && !isWalking && !chatVisible &&
                        rootView?.visibility == View.VISIBLE
                    ) {
                        when ((0..99).random()) {
                            // 走路权重从 15% 提到 40%：原来平均要等 3~4 轮（半分钟以上）
                            // 才会走一次，用户观察十几秒得出的结论必然是「走路不行」。
                            in 0..39 -> {
                                val dm = resources.displayMetrics
                                val cx = overlayParams?.x ?: 0
                                val cy = overlayParams?.y ?: 0
                                // 目标点按屏宽取，不再是 ±160px 的原地挪动
                                val tx = (0..(dm.widthPixels - 120).coerceAtLeast(1)).random()
                                val ty = (cy + (-200..200).random())
                                    .coerceIn(0, (dm.heightPixels - 120).coerceAtLeast(1))
                                val dist = abs(tx - cx) + abs(ty - cy)
                                if (dist > 60) {
                                    // 时长按距离算，恒定 2200ms 会让远距离像瞬移、近距离像蜗牛
                                    val dur = (dist * 6L).coerceIn(1200L, 6000L)
                                    PetLog.i(TAG, "自主走路 ($cx,$cy)→($tx,$ty) dist=$dist dur=$dur")
                                    walkTo(tx, ty, dur) {}
                                } else {
                                    enqueueAnim("Relax", PRIO_AUTO, null, 1.0, true, null)
                                }
                            }
                            in 40..59 -> enqueueAnim("Sit", PRIO_AUTO, null, 1.0, true, null)
                            in 60..74 -> enqueueAnim("Sleep", PRIO_AUTO, null, 1.0, true, null)
                            else -> enqueueAnim("Relax", PRIO_AUTO, null, 1.0, true, null)
                        }
                    }
                } catch (e: Exception) {
                    PetLog.e(TAG, "behavior loop 异常", e)
                }
                mainHandler.postDelayed(this, (9_000L..22_000L).random())
            }
        }
        behaviorLoop = loop
        mainHandler.postDelayed(loop, 6_000L)
    }

    // ---------------------------------------------------------------- 聊天输入

    fun showChatInput() = mainHandler.post {
        if (chatVisible || wm == null) return@post
        try {
            val chatRoot = LayoutInflater.from(this).inflate(R.layout.overlay_chat, null)
            val et = chatRoot.findViewById<EditText>(R.id.et_chat_input)
            val btnSend = chatRoot.findViewById<Button>(R.id.btn_send_chat)
            val btnClose = chatRoot.findViewById<ImageButton>(R.id.btn_close_chat)
            val btnFile = chatRoot.findViewById<ImageButton>(R.id.btn_file_chat)

            btnSend.setOnClickListener {
                val text = et.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setOnClickListener
                if (wsClient == null) { toast("未连接服务器，消息发不出去"); return@setOnClickListener }
                wsClient?.reportChatInput(text)
                et.setText("")
                toast("已发送")
            }
            // 键盘「发送」键：监听器要挂在 EditText 上，挂 Button 上永远不触发
            et.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    btnSend.performClick(); true
                } else false
            }
            btnClose.setOnClickListener { hideChatInput() }
            btnFile.setOnClickListener { toast("文件发送待接入") }

            val dm = resources.displayMetrics
            val chatW = (dm.widthPixels * 0.86f).toInt()
            val chatH = (56 * dm.density).toInt()
            val petX = overlayParams?.x ?: 0
            val petY = overlayParams?.y ?: 0
            val lp = WindowManager.LayoutParams(
                chatW, chatH,
                if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                // 输入框必须可聚焦，否则弹不出键盘打不了字
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (petX - chatW / 2).coerceIn(0, (dm.widthPixels - chatW).coerceAtLeast(0))
                y = (petY - chatH - 12).coerceIn(0, (dm.heightPixels - chatH).coerceAtLeast(0))
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            }
            wm!!.addView(chatRoot, lp)
            chatView = chatRoot
            chatVisible = true
            et.requestFocus()
            PetLog.i(TAG, "聊天框已显示")
        } catch (e: Exception) {
            PetLog.e(TAG, "聊天框显示失败", e)
            toast("输入框打开失败：${e.message}")
        }
    }

    fun hideChatInput() {
        val doHide = {
            chatView?.let { v -> runCatching { wm?.removeView(v) } }
            chatView = null
            chatVisible = false
        }
        if (Looper.myLooper() == Looper.getMainLooper()) doHide() else mainHandler.post { doHide() }
    }

    // ---------------------------------------------------------------- 网络

    private fun connectWs(url: String) {
        if (url.isBlank()) {
            PetLog.w(TAG, "server_url 为空，跳过 WS 连接（桌宠本体不受影响）")
            return
        }
        if (wsClient != null) {
            PetLog.i(TAG, "WsClient 已存在，跳过重建")
            return
        }
        try {
            val c = WsClient(applicationContext, url)
            wsClient = c
            App.instance.setWsClient(c)
            c.connect()
            PetLog.i(TAG, "WsClient 已启动 → $url")
        } catch (e: Exception) {
            PetLog.e(TAG, "WsClient 初始化失败", e)
        }
    }

    private fun enqueueUpdateWorker() {
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "update_check",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequest.Builder(UpdateWorker::class.java, 12, TimeUnit.HOURS).build()
        )
    }

    /** 供自检面板展示的连接状态 */
    fun wsState(): String = wsClient?.let {
        if (it.connected) "已连接" else "未连接（${it.lastError.ifBlank { "重连中" }}）"
    } ?: "未启动"

    private fun toast(msg: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}
