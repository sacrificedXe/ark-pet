package com.arkpet

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkpet.core.RoleInfo
import com.arkpet.core.RoleRegistry
import com.arkpet.maa.MaaBridge
import com.arkpet.overlay.PetOverlayService
import com.arkpet.updater.UpdateChecker
import com.arkpet.util.PetDiagnostics
import com.arkpet.util.PetLog
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 主界面。设计原则：出问题时界面自己要能说清问题在哪。
 * 自检面板常驻顶部，权限按钮按 1/2/3 顺序排列，日志可在机上直接看。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sp: SharedPreferences
    private lateinit var tvDiag: TextView
    private lateinit var etUrl: EditText
    private var appliedSkinId: String = ""

    companion object {
        private const val TAG = "MainActivity"
        private const val DEFAULT_URL = "ws://39.104.27.214:9100/ws"
        private const val REQ_PERMS = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = getSharedPreferences("arkpet", MODE_PRIVATE)
        PetLog.i(TAG, "onCreate")

        bindVersion()
        bindDiagnostics()
        bindServerUrl()
        bindPermissionButtons()
        bindServiceButtons()
        bindRoleAndSkin()
        bindSizeAndBehavior()
        bindUpdate()
        bindMaa()
        requestRuntimePermissions()
    }

    /**
     * 运行时权限：相机（CameraTools 抓拍）与通知（前台服务通知，SDK 33+）。
     * 都不是桌宠显示的必需项，拒绝也不阻塞主流程，只在自检面板里体现。
     */
    private fun requestRuntimePermissions() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            need += android.Manifest.permission.CAMERA
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            need += "android.permission.POST_NOTIFICATIONS"
        }
        if (need.isEmpty()) return
        runCatching { requestPermissions(need.toTypedArray(), REQ_PERMS) }
            .onFailure { PetLog.e(TAG, "申请运行时权限失败", it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            PetLog.i(TAG, "权限 $p → ${if (ok) "已授予" else "被拒绝"}")
        }
        refreshDiagnostics()
    }

    override fun onResume() {
        super.onResume()
        refreshDiagnostics()
        findViewById<Button>(R.id.btn_start).text =
            if (PetOverlayService.instance != null) "3. 桌宠运行中（点击重启）" else "3. 启动桌宠"
    }

    // ------------------------------------------------------------------ 各区块

    private fun bindVersion() {
        val pi = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        findViewById<TextView>(R.id.tv_version).text =
            "版本 ${pi?.versionName ?: "?"}　SDK ${Build.VERSION.SDK_INT}　${Build.MODEL}"
    }

    private fun bindDiagnostics() {
        tvDiag = findViewById(R.id.tv_diagnostics)
        findViewById<Button>(R.id.btn_refresh_diag).setOnClickListener { refreshDiagnostics() }
        findViewById<Button>(R.id.btn_view_log).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("运行日志（尾部 80 行）")
                .setMessage(PetLog.tail(80))
                .setPositiveButton("关闭", null)
                .setNeutralButton("路径") { _, _ -> toast(PetLog.path()) }
                .show()
        }
    }

    private fun refreshDiagnostics() {
        val items = PetDiagnostics.run(this)
        tvDiag.text = PetDiagnostics.format(items)
    }

    private fun bindServerUrl() {
        etUrl = findViewById(R.id.et_server_url)
        etUrl.setText(sp.getString("server_url", DEFAULT_URL))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val url = etUrl.text.toString().trim()
            sp.edit().putString("server_url", url).apply()
            PetLog.i(TAG, "保存 server_url=$url")
            toast("已保存")
            refreshDiagnostics()
        }

        // 测试连通走 HTTP /api/version：WS 握手失败原因太隐晦，HTTP 能直接看到状态码
        findViewById<Button>(R.id.btn_test_conn).setOnClickListener {
            val raw = etUrl.text.toString().trim()
            if (raw.isBlank()) { toast("请先填地址"); return@setOnClickListener }
            toast("测试中…")
            Thread {
                val base = UpdateChecker.httpBaseOf(raw)
                val result = runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(8, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    client.newCall(Request.Builder().url("$base/api/version").build()).execute()
                        .use { r -> "HTTP ${r.code}\n${r.body?.string()?.take(200)}" }
                }.getOrElse { "失败：${it.javaClass.simpleName} ${it.message}" }
                PetLog.i(TAG, "连通测试 $base → $result")
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("连通测试")
                        .setMessage("$base/api/version\n\n$result")
                        .setPositiveButton("好", null)
                        .show()
                }
            }.start()
        }
    }

    private fun bindPermissionButtons() {
        findViewById<Button>(R.id.btn_overlay_perm).setOnClickListener {
            if (Settings.canDrawOverlays(this)) { toast("悬浮窗权限已授予"); return@setOnClickListener }
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }.onFailure {
                PetLog.e(TAG, "打开悬浮窗设置失败", it)
                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                    .onFailure { toast("请手动到设置 → 应用 → 初雪桌宠 → 悬浮窗") }
            }
        }

        findViewById<Button>(R.id.btn_acc_perm).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("开启无障碍服务")
                .setMessage(
                    "无障碍只影响远程控制（点击/滑动/读屏），桌宠本体显示不需要它。\n\n" +
                        "在打开的列表里找「初雪桌宠」并开启。\n" +
                        "若本机被管控找不到入口，用电脑执行（顺序不能反）：\n\n" +
                        "adb shell settings put secure enabled_accessibility_services " +
                        "com.arkpet/com.arkpet.accessibility.PetAccessibilityService\n" +
                        "adb shell settings put secure accessibility_enabled 1"
                )
                .setPositiveButton("去开启") { _, _ ->
                    runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                        .onFailure { toast("无法打开无障碍设置，请用 adb 方案") }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun bindServiceButtons() {
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("先完成第 1 步：悬浮窗权限")
                refreshDiagnostics()
                return@setOnClickListener
            }
            val url = etUrl.text.toString().trim()
            sp.edit().putString("server_url", url).apply()

            // 已在运行则先停，避免同一进程内两套悬浮窗叠加
            if (PetOverlayService.instance != null) {
                stopService(Intent(this, PetOverlayService::class.java))
            }
            val intent = Intent(this, PetOverlayService::class.java).putExtra("server_url", url)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }.onSuccess {
                PetLog.i(TAG, "已请求启动 PetOverlayService url=$url")
                toast("桌宠启动中…")
                // 延迟回检，服务起不来立刻能看见原因
                tvDiag.postDelayed({
                    refreshDiagnostics()
                    if (PetOverlayService.instance == null) {
                        toast("服务未就绪，点「查看日志」看原因")
                    } else {
                        moveTaskToBack(true)
                    }
                }, 1200)
            }.onFailure {
                PetLog.e(TAG, "启动服务失败", it)
                toast("启动失败：${it.message}")
            }
        }

        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            stopService(Intent(this, PetOverlayService::class.java))
            PetLog.i(TAG, "已请求停止 PetOverlayService")
            toast("已停止")
            tvDiag.postDelayed({ refreshDiagnostics() }, 600)
        }
    }

    private fun bindRoleAndSkin() {
        val spRole = findViewById<Spinner>(R.id.sp_role)
        val spSkin = findViewById<Spinner>(R.id.sp_skin)

        spRole.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            RoleRegistry.roles.map { it.name }
        )
        val savedRole = RoleRegistry.byId(sp.getString("role_id", "").orEmpty())
            ?: RoleRegistry.roles.first()
        spRole.setSelection(RoleRegistry.roles.indexOf(savedRole), false)

        fun fillSkins(role: RoleInfo, selectSavedSkin: Boolean) {
            spSkin.adapter = ArrayAdapter(
                this, android.R.layout.simple_spinner_dropdown_item,
                role.skins.map { it.name }
            )
            val target = if (selectSavedSkin) {
                role.skins.find { it.id == sp.getString("skin_id", "") } ?: role.defaultSkin()
            } else role.defaultSkin()
            spSkin.setSelection(role.skins.indexOf(target).coerceAtLeast(0), false)
        }
        fillSkins(savedRole, true)

        // 不能用「忽略第一次回调」的布尔标记：Spinner 的初始回调只有在
        // setSelection 落到与当前不同的位置时才必然触发。savedRole 就是 index 0 时
        // 那次回调不发生，标记留在 true，于是用户真正的第一次切换被吞掉 ——
        // 表现正是「选完角色皮肤不跟着变」。改成比对「已生效的 id」，幂等且无时序假设。
        var appliedRoleId = savedRole.id
        spRole.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val role = RoleRegistry.roles.getOrNull(pos) ?: return
                if (role.id == appliedRoleId) return
                appliedRoleId = role.id
                val skin = role.defaultSkin()
                sp.edit().putString("role_id", role.id).putString("skin_id", skin.id).apply()
                fillSkins(role, false)
                appliedSkinId = skin.id
                applySkin(skin.id, "${role.name} · ${skin.name}")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }

        appliedSkinId = sp.getString("skin_id", savedRole.defaultSkin().id).orEmpty()
        spSkin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>, v: android.view.View?, pos: Int, id: Long) {
                val role = RoleRegistry.byId(sp.getString("role_id", "").orEmpty())
                    ?: RoleRegistry.roles.first()
                val skin = role.skins.getOrNull(pos) ?: return
                if (skin.id == appliedSkinId) return
                appliedSkinId = skin.id
                sp.edit().putString("skin_id", skin.id).apply()
                applySkin(skin.id, "${role.name} · ${skin.name}")
            }
            override fun onNothingSelected(p: AdapterView<*>) {}
        }
    }

    /**
     * 落地皮肤切换。服务没起来时必须说话：
     * 原实现是 `instance?.setSkin(...)`，服务未运行就静默什么都不做，
     * 用户看到的现象和「切换功能坏了」完全一样。
     */
    private fun applySkin(skinId: String, label: String) {
        val svc = PetOverlayService.instance
        if (svc == null) {
            PetLog.i(TAG, "皮肤已存盘 $skinId，但服务未运行，下次启动生效")
            toast("已选 $label，启动桌宠后生效")
            return
        }
        svc.setSkin(skinId)
        PetLog.i(TAG, "皮肤切换 → $skinId")
        toast("已切换到 $label")
    }

    private fun bindSizeAndBehavior() {
        val tvSize = findViewById<TextView>(R.id.tv_size_label)
        val sbSize = findViewById<SeekBar>(R.id.sb_size)
        val saved = sp.getFloat("size_pct", 100f).toInt().coerceIn(40, 250)
        sbSize.progress = saved
        tvSize.text = "大小：$saved%"
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = progress.coerceAtLeast(40)
                tvSize.text = "大小：$v%"
                if (fromUser) {
                    sp.edit().putFloat("size_pct", v.toFloat()).apply()
                    PetOverlayService.instance?.setSize(v / 100f)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

        val sw = findViewById<Switch>(R.id.sw_behavior)
        sw.isChecked = sp.getBoolean("behavior_enabled", true)
        sw.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean("behavior_enabled", v).apply()
            PetOverlayService.instance?.setBehaviorEnabled(v)
        }
    }

    private fun bindUpdate() {
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isBlank()) { toast("请先填服务器地址"); return@setOnClickListener }
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(this)
                    .setTitle("需要安装权限")
                    .setMessage("系统未允许本应用安装未知来源应用，下载完也装不上。先去授权？")
                    .setPositiveButton("去授权") { _, _ ->
                        runCatching {
                            startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:$packageName")
                                )
                            )
                        }.onFailure { toast("请手动到设置里允许安装未知应用") }
                    }
                    .setNegativeButton("仍然检查") { _, _ -> doCheckUpdate(url) }
                    .show()
                return@setOnClickListener
            }
            doCheckUpdate(url)
        }
    }

    private fun doCheckUpdate(url: String) {
        toast("正在检查更新…")
        val checker = UpdateChecker(this)
        checker.check(url, force = true, onNone = { reason ->
            runOnUiThread { toast(reason) }
        }) { json ->
            val apkUrl = json.optString("url")
            val ver = json.optString("version")
            if (apkUrl.isBlank()) {
                runOnUiThread { toast("服务端没给下载地址") }
                return@check
            }
            runOnUiThread { toast("发现 $ver，开始下载") }
            checker.downloadAndInstall(apkUrl, { pct ->
                if (pct % 25 == 0) PetLog.i(TAG, "下载进度 $pct%")
            }) { ok ->
                runOnUiThread { toast(if (ok) "下载完成，按提示安装" else "下载失败，看日志") }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ MAA

    /**
     * MAA-Meow 面板。之前 MaaBridge 只挂在 WS 的 maa.start/maa.stop 上，
     * 机上完全没有入口，用户问「MAA 的界面在哪儿」——功能在，门没开。
     * 这里补齐四个动作：检测 / 启动配置 / 停止 / 直接打开 MAA-Meow 本体。
     */
    private fun bindMaa() {
        val tvStatus = findViewById<TextView>(R.id.tv_maa_status)
        val etProfile = findViewById<EditText>(R.id.et_maa_profile)
        val swForce = findViewById<Switch>(R.id.sw_maa_force)

        etProfile.setText(sp.getString("maa_profile_id", ""))
        swForce.isChecked = sp.getBoolean("maa_force_start", false)
        swForce.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean("maa_force_start", v).apply()
        }

        fun refreshMaa() {
            val j = runCatching { MaaBridge.checkAvailable() }.getOrNull()
            if (j == null) {
                tvStatus.text = "检测失败，看日志"
                return
            }
            tvStatus.text = listOf(
                (if (j.optBoolean("maa_installed")) "✓" else "✗") + " MAA-Meow 已安装",
                (if (j.optBoolean("root")) "✓" else "✗") + " root 通道",
                (if (j.optBoolean("shizuku")) "✓" else "✗") + " Shizuku 通道",
                j.optString("reason")
            ).joinToString("\n")
            PetLog.i(TAG, "MAA 检测 $j")
        }
        refreshMaa()

        findViewById<Button>(R.id.btn_maa_check).setOnClickListener { refreshMaa() }

        findViewById<Button>(R.id.btn_maa_start).setOnClickListener {
            val pid = etProfile.text.toString().trim()
            if (pid.isBlank()) { toast("先填任务配置 UUID"); return@setOnClickListener }
            sp.edit().putString("maa_profile_id", pid).apply()
            toast("启动中…")
            Thread {
                val r = runCatching { MaaBridge.start(pid, swForce.isChecked) }
                    .getOrElse { org.json.JSONObject().put("status", "error").put("output", it.message) }
                runOnUiThread {
                    AlertDialog.Builder(this)
                        .setTitle("MAA 启动结果")
                        .setMessage("status=" + r.optString("status") + "\n" + r.optString("output"))
                        .setPositiveButton("好", null)
                        .show()
                    refreshMaa()
                }
            }.start()
        }

        findViewById<Button>(R.id.btn_maa_stop).setOnClickListener {
            Thread {
                runCatching { MaaBridge.stop() }
                runOnUiThread { toast("已发送停止指令"); refreshMaa() }
            }.start()
        }

        findViewById<Button>(R.id.btn_maa_open).setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(MaaBridge.PKG)
            if (intent == null) {
                AlertDialog.Builder(this)
                    .setTitle("未安装 MAA-Meow")
                    .setMessage("包名 ${MaaBridge.PKG} 没找到。先装 MAA-Meow，再回来这里。")
                    .setPositiveButton("好", null)
                    .show()
                return@setOnClickListener
            }
            runCatching { startActivity(intent) }
                .onFailure { toast("拉起失败：${it.message}") }
        }
    }
}
