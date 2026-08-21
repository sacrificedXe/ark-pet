package com.arkpet

import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkpet.overlay.PetOverlayService
import rikka.shizuku.Shizuku

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

        // 皮肤选择
        val spSkin = findViewById<Spinner>(R.id.sp_skin)
        val skins = arrayOf("初雪(base)", "雪境(snow)", "云迹(cloud_trail)")
        val skinAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, skins)
        spSkin.adapter = skinAdapter
        spSkin.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                if (isServiceRunning) {
                    val skinName = when (position) { 0 -> "base"; 1 -> "snow"; 2 -> "cloud_trail"; else -> "base" }
                    PetOverlayService.instance?.setSkin(skinName)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
        }

        // 大小滑块
        val tvSizeLabel = findViewById<TextView>(R.id.tv_size_label)
        val sbSize = findViewById<SeekBar>(R.id.sb_size)
        sbSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isServiceRunning) {
                    val scale = progress / 100f
                    PetOverlayService.instance?.setSize(scale)
                    tvSizeLabel.text = "大小：${progress}%"
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            val url = etUrl.text.toString().trim()
            sp.edit().putString("server_url", url).apply()
            toast("已保存")
        }

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请依次授予悬浮窗和无障碍权限")
            try {
                if (Shizuku.pingBinder()) {
                    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                        Shizuku.requestPermission(shizukuRequestCode)
                        toast("请在弹窗中允许 Shizuku 授权")
                    } else {
                        toast("Shizuku 已授权")
                    }
                } else {
                    toast("Shizuku 未运行：装好后用 adb 激活一次")
                }
            } catch (_: Exception) {
                toast("Shizuku 不可用")
            }
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("需要悬浮窗权限")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                return@setOnClickListener
            }
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
        if (requestCode == shizukuRequestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            toast("Shizuku 已授权，MAA 联动可用")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
