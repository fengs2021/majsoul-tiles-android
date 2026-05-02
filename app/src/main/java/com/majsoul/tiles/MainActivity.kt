package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION = 1001
        private const val SCREEN_CAPTURE = 1002
    }

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)

        Log.d(TAG, "onCreate, SDK=${Build.VERSION.SDK_INT}")

        // 步骤 1：立刻申请悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "→ requesting overlay permission")
            statusText.text = "正在申请悬浮窗权限…"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION)
        } else {
            Log.d(TAG, "overlay already granted → requesting capture")
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        statusText.text = "正在申请截图权限…"
        Log.d(TAG, "→ requesting screen capture")
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(pm.createScreenCaptureIntent(), SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Screen capture intent failed", e)
            Toast.makeText(this, "截图权限请求失败，仅支持手动分析", Toast.LENGTH_LONG).show()
            launchService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: request=$requestCode result=$resultCode hasData=${data != null}")

        when (requestCode) {
            OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val granted = Settings.canDrawOverlays(this)
                    Log.d(TAG, "overlay permission granted=$granted")
                    if (granted) {
                        // 立刻请求截图权限
                        requestScreenCapture()
                    } else {
                        Toast.makeText(this, "需要悬浮窗权限才能使用", Toast.LENGTH_LONG).show()
                        launchService()
                    }
                } else {
                    requestScreenCapture()
                }
            }

            SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    Log.d(TAG, "screen capture GRANTED")
                    try {
                        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        OverlayService.mediaProjection = pm.getMediaProjection(resultCode, data)
                        OverlayService.mediaProjectionResultCode = resultCode
                        OverlayService.mediaProjectionData = data
                        Log.d(TAG, "MediaProjection created: ${OverlayService.mediaProjection}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create MediaProjection", e)
                    }
                } else {
                    Log.d(TAG, "screen capture DENIED or cancelled")
                }

                launchService()
            }
        }
    }

    private fun launchService() {
        statusText.text = "启动中…"
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Android 14+: 延迟 finish 防止 MediaProjection 被回收
        window.decorView.postDelayed({
            Log.d(TAG, "finishing MainActivity")
            finish()
        }, if (Build.VERSION.SDK_INT >= 34) 2000 else 500)
    }
}
