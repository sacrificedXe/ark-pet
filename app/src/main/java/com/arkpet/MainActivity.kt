package com.arkpet

import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.arkpet.core.RoleRegistry
import com.arkpet.core.RoleInfo
import com.arkpet.overlay.PetOverlayService
import com.arkpet.updater.UpdateChecker
import com.arkpet.updater.UpdateWorker
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val shizukuRequestCode = 1024
    private lateinit var sp: SharedPreferences
    private var isServiceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = getSharedPreferences("arkpet", MODE_PRIVATE)

        val etUrl = findViewById<EditText>(R.id.et_server_url)
        etUrl.setText(sp.getString("server_url", ""))

        // 角色 Spinner
        val spRole = findViewById<Spinner>(R.id.sp_role)
        val roleNames = RoleRegistry.roles.map { it.name }.toTypedArray()
        spRole.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, roleNames)
        var roleReady = false
        val savedRole = RoleRegistry.byId(sp.getString("role_id", RoleRegistry.roles.first().id) ?: "") ?: RoleRegistry.roles.first()
        spRole.setSelection(RoleRegistry.roles.indexOf(savedRole), false)
        // 皮肤 Spinner（二级联动）
        val spSkin = findViewById<Spinner>(R.id.sp_skin)
        var skinReady = false
        fun updateSkinSpinner(role: RoleInfo) {
            val names = role.skins.map { it.name }.toTypedArray()
            spSkin.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            val cur = RoleRegistry.allSkins().find { it.id == sp.getString("skin_id", role.defaultSkin().id) }
                ?: role.defaultSkin()
            spSkin.setSelection(role.skins.indexOf(cur), false)
            skinReady = false
        }
        spRole.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (!roleReady) { roleReady = true; return }
                val role = RoleRegistry.roles[position]
                sp.edit().putString("role_id", role.id).apply()
                updateSkinSpinner(role)
                PetOverlayService.instance?.setSkin(role.defaultSkin().id)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        spSkin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (!skinReady) { skinReady = true; return }
                val role = RoleRegistry.byId(sp.getString("role_id", RoleRegistry.roles.first().id) ?: "") ?: RoleRegistry.roles.first()
                val skin = role.skins[position]
                sp.edit().putString("skin_id", skin.id).apply()
                PetOverlayService.instance?.setSkin(skin.id)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }
        updateSkinSpinner(savedRole)

        // 大小滑块
        val tvSize = findViewById<TextView>(R.id.tv_size_label)
        val sbSize = findViewById<SeekBar>(R.id.sb_size)
        sbSize.progress = sp.getFloat("size_pct", 100f).toInt().coerceIn(40, 250)
        tvSize.text = "大小：${sbSize.progress}%"
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                tvSize.text = "大小：$progress%"
                if (fromUser) {
                    sp.edit().putFloat("size_pct", progress.toFloat()).apply()
                    PetOverlayService.instance?.setSize(progress / 100f)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        // 行为开关
        val swBehavior = findViewById<Switch>(R.id.sw_behavior)
        swBehavior.isChecked = sp.getBoolean("behavior_enabled", true)
        swBehavior.setOnCheckedChangeListener { _, v ->
            sp.edit().putBoolean("behavior_enabled", v).apply()
            PetOverlayService.instance?.setBehaviorEnabled(v)
        }

        // 检查更新
        findViewById<Button>(R.id.btn_check_update).setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.isBlank()) { toast("请先填写服务器地址"); return@setOnClickListener }
            UpdateChecker(this).check(url, force = true) { _ ->
                toast("有新版本已到，请下拉通知栏安装")
            }
        }

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            sp.edit().putString("server_url", etUrl.text.toString().trim()).apply()
            toast("已保存")
        }
        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请依次授予悬浮窗和无障碍权限")
            try {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        Shizuku.requestPermission(shizukuRequestCode)
                        toast("请在弹窗中允许 Shizuku 授权")
                    } else toast("Shizuku 已授权")
                } else toast("Shizuku 未运行：装好后用 adb 激活一次")
            } catch (_: Exception) { toast("Shizuku 不可用") }
        }
        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("需要悬浮窗权限"); startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); return@setOnClickListener
            }
            // P0: 校验无障碍服务
            val am = getSystemService(android.accessibilityservice.AccessibilityManager::class.java)
            val a11yEnabled = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC)
                .any { it.id.startsWith(packageName) }
            if (!a11yEnabled) {
                toast("需要无障碍服务权限")
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            // P0: URL 落盘 SP（与 PetOverlayService 读取一致）
            sp.edit().putString("server_url", etUrl.text.toString().trim()).apply()
            val url = etUrl.text.toString().trim()
            val intent = Intent(this, PetOverlayService::class.java).putExtra("server_url", url)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            isServiceRunning = true
            findViewById<Button>(R.id.btn_start).text = "桌宠运行中"
            toast("桌宠启动")
            moveTaskToBack(true)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == shizukuRequestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            toast("Shizuku 已授权，MAA 联动可用")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
