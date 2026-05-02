package com.majsoul.tiles

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast

class MainActivity : Activity() {
    companion object {
        const val OVERLAY_PERMISSION = 1001
        const val SCREEN_CAPTURE = 1002
    }

    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 第一步：检查悬浮窗权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION)
            return
        }

        // 第二步：请求截图权限
        requestScreenCapture()
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            OVERLAY_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                    requestScreenCapture()
                } else {
                    Toast.makeText(this, "需要悬浮窗权限才能运行", Toast.LENGTH_LONG).show()
                    finish()
                }
            }

            SCREEN_CAPTURE -> {
                if (resultCode == RESULT_OK && data != null) {
                    // 保存 MediaProjection 参数到静态字段
                    OverlayService.mediaProjectionResultCode = resultCode
                    OverlayService.mediaProjectionData = data
                    // 先创建一次 MediaProjection 并保存在 Service 中
                    // 这样权限 token 在 Activity finish 后仍然有效
                    val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = pm.getMediaProjection(resultCode, data)
                    OverlayService.mediaProjection = projection
                } else {
                    // 用户拒绝了截图权限 — 仍然启动服务（只能手动选牌）
                    Toast.makeText(this, "未授权截图，仅支持手动选牌分析", Toast.LENGTH_LONG).show()
                }

                // 无论如何都启动悬浮窗服务
                launchOverlayService()
            }
        }
    }

    private fun launchOverlayService() {
        if (launched) return
        launched = true

        val serviceIntent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }
}
