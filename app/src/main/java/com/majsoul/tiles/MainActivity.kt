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
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val OVERLAY_PERMISSION = 1001
        private const val SCREEN_CAPTURE = 1002
    }

    private var screenCaptureDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate, SDK=${Build.VERSION.SDK_INT}")

        // 步骤 1：悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.d(TAG, "Requesting overlay permission")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            @Suppress("DEPRECATION")
            startActivityForResult(intent, OVERLAY_PERMISSION)
        } else {
            requestCapture()
        }
    }

    private fun requestCapture() {
        Log.d(TAG, "Requesting screen capture")
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val intent = pm.createScreenCaptureIntent()
            startActivityForResult(intent, SCREEN_CAPTURE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start capture intent", e)
            // 无法截图（某些设备不支持），直接启动服务
            Toast.makeText(this, "截图权限请求失败，仅支持手动分析", Toast.LENGTH_LONG).show()
            launchService()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: request=$requestCode result=$resultCode hasData=${data != null}")

        when (requestCode) {
            OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    Log.d(TAG, "Overlay permission granted, requesting capture")
                    // 用 post 延迟一下，确保 Activity 状态稳定
                    window.decorView.postDelayed({ requestCapture() }, 300)
                } else {
                    Toast.makeText(this, "需要悬浮窗权限才能使用", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

            SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    Log.d(TAG, "Screen capture granted")
                    try {
                        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        val projection: MediaProjection = pm.getMediaProjection(resultCode, data)
                        OverlayService.mediaProjection = projection
                        OverlayService.mediaProjectionData = data
                        OverlayService.mediaProjectionResultCode = resultCode
                        screenCaptureDone = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create MediaProjection", e)
                    }
                } else {
                    Log.d(TAG, "Screen capture denied or cancelled")
                }

                // 不管有没有截图权限，都启动服务
                launchService()
            }
        }
    }

    private fun launchService() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Android 14+: Activity 立即 finish 会导致 MediaProjection 失效
        // 延迟 finish，给 Service 足够时间持有 MediaProjection 引用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window.decorView.postDelayed({ finish() }, 1000)
        } else {
            finish()
        }
    }
}
