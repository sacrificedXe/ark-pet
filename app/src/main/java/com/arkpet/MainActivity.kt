package com.arkpet

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.arkpet.overlay.PetOverlayService
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private val shizukuRequestCode = 1024

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val etUrl = findViewById<EditText>(R.id.et_server_url)
        val sp = getSharedPreferences("arkpet", MODE_PRIVATE)
        etUrl.setText(sp.getString("server_url", ""))

        findViewById<Button>(R.id.btn_save).setOnClickListener {
            sp.edit().putString("server_url", etUrl.text.toString().trim()).apply()
            toast("已保存")
        }

        findViewById<Button>(R.id.btn_permission).setOnClickListener {
            // 悬浮窗
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            // 无障碍
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("请依次授予悬浮窗和无障碍权限")
            // Shizuku（MAA 联动用，无 root 场景）
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
            val intent = Intent(this, PetOverlayService::class.java)
            intent.putExtra("server_url", etUrl.text.toString().trim())
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            toast("桌宠启动")
            moveTaskToBack(true)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == shizukuRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            toast("Shizuku 已授权，MAA 联动可用")
        }
    }
}
