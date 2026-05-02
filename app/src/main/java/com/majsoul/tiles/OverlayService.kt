package com.majsoul.tiles

import android.annotation.SuppressLint
import android.app.*
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class OverlayService : Service() {

    companion object {
        const val TAG = "MajsoulOverlay"
        const val CHANNEL_ID = "majsoul_overlay"
        const val NOTIFICATION_ID = 1001
        const val AUTO_SCREENSHOT_INTERVAL_MS = 5000L

        // 由 MainActivity 在权限回调中注入
        var mediaProjection: MediaProjection? = null
        var mediaProjectionResultCode: Int = -1
        var mediaProjectionData: Intent? = null
    }

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null

    private lateinit var webView: WebView

    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private val autoScreenshotEnabled = AtomicBoolean(false)
    private val screenshotHandler = Handler(Looper.getMainLooper())
    private val screenshotRunnable = object : Runnable {
        override fun run() {
            if (autoScreenshotEnabled.get()) {
                performScreenshot()
                screenshotHandler.postDelayed(this, AUTO_SCREENSHOT_INTERVAL_MS)
            }
        }
    }

    private lateinit var tileRecognizer: TileRecognizer

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate, projection=${mediaProjection != null}")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        tileRecognizer = TileRecognizer(this)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        registerProjectionCallback()
        createOverlayWindow()
    }

    private fun registerProjectionCallback() {
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped by system")
                mediaProjection = null
                runOnWebView("updateStatus('⚠ 截图权限失效，请重启App')")
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "牌效助手", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "悬浮窗牌效分析服务" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)

        return builder
            .setContentTitle("牌效助手运行中")
            .setContentText("悬浮窗已就绪")
            .setSmallIcon(R.drawable.ic_tile)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createOverlayWindow() {
        val density = resources.displayMetrics.density
        val winW = (350 * density).toInt()
        val winH = (340 * density).toInt()

        layoutParams = WindowManager.LayoutParams(
            winW, winH,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (100 * density).toInt()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            addJavascriptInterface(JavaScriptBridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.d(TAG, "WebView loaded")
                }
            }
            loadUrl("file:///android_asset/overlay.html")
        }

        overlayView = webView
        windowManager.addView(overlayView, layoutParams)
    }

    private fun performScreenshot() {
        val projection = mediaProjection
        if (projection == null) {
            Log.w(TAG, "No MediaProjection available")
            runOnWebView("updateStatus('⚠ 无截图权限，请重启App')")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@OverlayService, "截图权限未授权，请重新打开App", Toast.LENGTH_LONG).show()
            }
            return
        }

        runOnWebView("updateStatus('📷 截图中...')")
        overlayView?.visibility = View.INVISIBLE

        try {
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 1)
            virtualDisplay = projection.createVirtualDisplay(
                "screenshot",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )

            imageReader!!.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image)
                        processScreenshot(bitmap)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Screenshot error", e)
                } finally {
                    image?.close()
                    cleanupScreenshot()
                }
            }, screenshotHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed", e)
            overlayView?.visibility = View.VISIBLE
            runOnWebView("updateStatus('⚠ 截图失败')")
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val bitmap = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
    }

    private fun processScreenshot(bitmap: Bitmap) {
        Log.d(TAG, "Processing screenshot ${bitmap.width}x${bitmap.height}")
        runOnWebView("updateStatus('🔍 识别中...')")

        resultHandler.post {
            try {
                val labels = tileRecognizer.matchHand(bitmap)
                Log.d(TAG, "Recognized: $labels")

                if (labels.isEmpty()) {
                    overlayView?.visibility = View.VISIBLE
                    runOnWebView("updateStatus('⚠ 未识别到手牌')")
                    return@post
                }

                val hand = labels.mapNotNull { ShantenAnalyzer.tileToInt(it) }.filter { it >= 0 }
                if (hand.size < 5) {
                    overlayView?.visibility = View.VISIBLE
                    runOnWebView("updateStatus('⚠ 手牌不足: ${hand.size}张')")
                    return@post
                }

                sendAnalysisResult(hand.sorted())
            } catch (e: Exception) {
                Log.e(TAG, "Analysis error", e)
                overlayView?.visibility = View.VISIBLE
                runOnWebView("updateStatus('⚠ 分析出错')")
            }
        }
    }

    private fun sendAnalysisResult(hand: List<Int>) {
        val sr = ShantenAnalyzer.calcShanten(hand)
        val candidates = ShantenAnalyzer.analyzeHand(hand)

        val result = JSONObject()
        result.put("shanten", sr.shanten)
        result.put("shanten_detail", JSONObject(sr.toMap()))
        result.put("best_discard",
            if (candidates.isNotEmpty()) ShantenAnalyzer.intToTile(candidates[0].tile) else "")
        val arr = JSONArray()
        candidates.forEachIndexed { i, c ->
            val obj = JSONObject()
            obj.put("tile", ShantenAnalyzer.intToTile(c.tile))
            obj.put("shanten_after", c.shantenAfter)
            obj.put("ukeire_types", c.ukeireTypes)
            obj.put("ukeire_count", c.ukeireCount)
            obj.put("ukeire_tiles", JSONArray(c.ukeireTiles.map { ShantenAnalyzer.intToTile(it) }))
            obj.put("wait_quality", c.waitQuality)
            obj.put("is_best", i == 0)
            arr.put(obj)
        }
        result.put("candidates", arr)

        val jsonStr = result.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        runOnWebView("onAnalyzeResult(\"$jsonStr\")")
        Log.d(TAG, "Analysis done: shanten=${sr.shanten}")
    }

    private val resultHandler = Handler(Looper.getMainLooper())

    private fun cleanupScreenshot() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
    }

    private fun runOnWebView(js: String) {
        screenshotHandler.post {
            webView.evaluateJavascript(js, null)
            overlayView?.visibility = View.VISIBLE
        }
    }

    // ===== JavaScript Bridge =====

    inner class JavaScriptBridge {
        @JavascriptInterface
        fun screenshot() {
            Log.d(TAG, "JS: screenshot requested")
            Handler(Looper.getMainLooper()).post { performScreenshot() }
        }

        @JavascriptInterface
        fun analyze(handJson: String) {
            Log.d(TAG, "JS: analyze: $handJson")
            Handler(Looper.getMainLooper()).post {
                try {
                    val arr = JSONArray(handJson)
                    val labels = (0 until arr.length()).map { arr.getString(it) }
                    val hand = labels.mapNotNull { ShantenAnalyzer.tileToInt(it) }.filter { it >= 0 }

                    if (hand.size < 5) {
                        runOnWebView("updateStatus('⚠ 手牌不足: ${hand.size}张')")
                        return@post
                    }

                    sendAnalysisResult(hand.sorted())
                } catch (e: Exception) {
                    Log.e(TAG, "Manual analyze error", e)
                    val msg = e.message?.replace("\"", "\\\"") ?: "未知错误"
                    runOnWebView("updateStatus('⚠ 分析出错: $msg')")
                }
            }
        }

        @JavascriptInterface
        fun setAutoScreenshot(enabled: Boolean) {
            Log.d(TAG, "JS: setAutoScreenshot=$enabled")
            Handler(Looper.getMainLooper()).post {
                if (enabled) {
                    autoScreenshotEnabled.set(true)
                    screenshotHandler.post(screenshotRunnable)
                } else {
                    autoScreenshotEnabled.set(false)
                    screenshotHandler.removeCallbacks(screenshotRunnable)
                }
            }
        }

        @JavascriptInterface
        fun toast(message: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@OverlayService, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        autoScreenshotEnabled.set(false)
        screenshotHandler.removeCallbacks(screenshotRunnable)
        cleanupScreenshot()
        mediaProjection?.stop()
        mediaProjection = null
        overlayView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }
}
