package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast

/**
 * 入口 Activity: 申请悬浮窗权限 → 启动 Service
 * 截图权限由 CaptureActivity 在用户点截图时触发
 */
class MainActivity : Activity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION = 1001
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)

        Log.d(TAG, "onCreate, SDK=${Build.VERSION.SDK_INT}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "requesting overlay permission")
            statusText.text = "启动中…"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION)
        } else {
            Log.d(TAG, "overlay already granted, launching service")
            launchService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION) {
            val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(this) else true
            Log.d(TAG, "overlay result: granted=$granted")
            if (!granted) {
                Toast.makeText(this, "需要悬浮窗权限才能使用", Toast.LENGTH_LONG).show()
            }
        }
        launchService()
    }

    private fun launchService() {
        statusText.text = "启动中…"
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        finish()
    }
}
