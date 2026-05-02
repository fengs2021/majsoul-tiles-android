package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * 透明中间件：创建即申请截图权限，用户看不到此页面
 */
class CaptureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("CaptureActivity", "onCreate → requesting capture immediately")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(
                (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent(),
                2001
            )
        } catch (e: Exception) {
            Log.e("CaptureActivity", "start failed: ${e.message}")
            finish()
        }
    }

    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        Log.d("CaptureActivity", "result: code=$code data=${data != null}")
        if (req == 2001 && code == RESULT_OK && data != null) {
            OverlayService._resultCode = code
            OverlayService._resultData = data
        }
        finish()
    }
}
