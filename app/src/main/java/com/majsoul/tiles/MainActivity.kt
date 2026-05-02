package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView

/**
 * 入口：打开即申请悬浮窗 → 截图 → 启动 Service
 */
class MainActivity : Activity() {
    companion object {
        const val TAG = "MainActivity"
        const val OVERLAY = 1001
        const val CAPTURE = 1002
    }

    private lateinit var tv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        tv = findViewById(R.id.status_text)
        log("onCreate SDK=${Build.VERSION.SDK_INT}")

        // 步骤1: 悬浮窗
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            tv.text = "正在申请悬浮窗权限…"
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), OVERLAY)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        tv.text = "正在申请截图权限…"
        try {
            @Suppress("DEPRECATION")
            startActivityForResult((getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(), CAPTURE)
        } catch (e: Exception) {
            log("capture intent ERROR: ${e.message}")
            launch()
        }
    }

    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        log("onResult req=$req code=$code data=${data != null}")
        when (req) {
            OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this))
                    requestCapture()
                else
                    launch()
            }
            CAPTURE -> {
                if (code == RESULT_OK && data != null) {
                    OverlayService._resultCode = code
                    OverlayService._resultData = data
                    log("capture OK, stored")
                } else {
                    log("capture DENIED")
                }
                launch()
            }
        }
    }

    private fun launch() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        finish()
    }

    private fun log(msg: String) = Log.d(TAG, msg)
}
