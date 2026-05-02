package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView

/**
 * 截图权限申请 Activity
 * OverlayService 在用户点击截图时启动此 Activity
 * 用户点击按钮后弹出系统截图权限对话框
 */
class CaptureActivity : Activity() {
    companion object {
        const val TAG = "CaptureActivity"
        private const val REQUEST_CODE = 2001
    }

    private lateinit var statusText: TextView
    private lateinit var grantBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_capture)
        statusText = findViewById(R.id.status_text)
        grantBtn = findViewById(R.id.btn_grant)

        Log.d(TAG, "onCreate")
        statusText.text = "需要截图权限才能使用牌效分析"
        grantBtn.text = "授权截图权限"
        grantBtn.setOnClickListener {
            requestCapture()
        }
    }

    private fun requestCapture() {
        Log.d(TAG, "requesting screen capture")
        try {
            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(pm.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "capture intent failed", e)
            statusText.text = "无法启动截图权限: ${e.message}"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: req=$requestCode result=$resultCode data=${data != null}")

        if (requestCode == REQUEST_CODE) {
            if (resultCode == RESULT_OK && data != null) {
                Log.d(TAG, "capture granted, creating MediaProjection")
                try {
                    val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection: MediaProjection = pm.getMediaProjection(resultCode, data)

                    // 注册回调
                    projection.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            Log.d(TAG, "MediaProjection stopped")
                            OverlayService.mediaProjection = null
                            OverlayService.mediaProjectionResultCode = -1
                            OverlayService.mediaProjectionData = null
                        }
                    }, null)

                    // 注入到 Service
                    OverlayService.mediaProjection = projection
                    OverlayService.mediaProjectionResultCode = resultCode
                    OverlayService.mediaProjectionData = data
                    Log.d(TAG, "MediaProjection injected, will now screenshot")
                } catch (e: Exception) {
                    Log.e(TAG, "failed to create MediaProjection", e)
                }
            } else {
                Log.d(TAG, "capture denied or cancelled")
            }
        }

        finish()
    }
}
