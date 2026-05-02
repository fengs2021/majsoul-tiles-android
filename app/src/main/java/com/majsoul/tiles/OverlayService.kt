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
import android.media.projection.MediaProjectionManager
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
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class OverlayService : Service() {

    companion object {
        const val TAG = "MajsoulOverlay"
        // 由 MainActivity 注入（不存 Projection 对象，只存凭证）
        @JvmStatic var _resultCode: Int = -1
        @JvmStatic var _resultData: Intent? = null
    }

    private val logFile by lazy { File(getExternalFilesDir(null), "majsoul_log.txt") }
    private val sdf = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())

    private fun log(msg: String) {
        val line = "[${sdf.format(Date())}] $msg"
        Log.d(TAG, line)
        try {
            FileWriter(logFile, true).use { it.write(line + "\n") }
        } catch (_: Exception) {}
        runOnWebView("appendLog('$msg')")
    }

    // ===== 窗口 =====
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var overlay: View? = null
    private lateinit var webView: WebView

    // ===== 截图 =====
    private var screenW = 0
    private var screenH = 0
    private var screenDpi = 0
    private var vd: VirtualDisplay? = null
    private var ir: ImageReader? = null
    private var projection: MediaProjection? = null
    private var callbackRegistered = false

    // ===== 自动截图 =====
    private val autoOn = AtomicBoolean(false)
    private val h = Handler(Looper.getMainLooper())
    private val autoTask = object : Runnable {
        override fun run() { if (autoOn.get()) { capture(); h.postDelayed(this, 5000) } }
    }

    private lateinit var recognizer: TileRecognizer

    override fun onCreate() {
        super.onCreate()
        log("=== Service onCreate ===")
        log("_resultCode=$_resultCode _resultData=${_resultData != null}")

        createNotif()
        startForeground(1001, notif())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        recognizer = TileRecognizer(this)

        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels; screenH = m.heightPixels; screenDpi = m.densityDpi
        log("screen=${screenW}x${screenH} dpi=$screenDpi")

        createOverlay()
    }

    private fun createNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("majsoul", "牌效", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notif() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        Notification.Builder(this, "majsoul").setContentTitle("牌效助手").setSmallIcon(R.drawable.ic_tile).setOngoing(true).build()
    else @Suppress("DEPRECATION") Notification.Builder(this).setContentTitle("牌效助手").setSmallIcon(R.drawable.ic_tile).setOngoing(true).build()

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun createOverlay() {
        val d = resources.displayMetrics.density
        lp = WindowManager.LayoutParams(
            (260 * d).toInt(), (360 * d).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (60 * d).toInt()
            y = (120 * d).toInt()
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(Color.TRANSPARENT); setLayerType(View.LAYER_TYPE_HARDWARE, null)
            addJavascriptInterface(Bridge(), "Android")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) { log("WebView loaded") }
            }
            loadUrl("file:///android_asset/overlay.html")
            setOnTouchListener(drag)
        }

        overlay = webView
        wm.addView(overlay, lp)
        log("overlay created")
    }

    // ===== 拖动 =====
    private var dx0 = 0f; private var dy0 = 0f
    private var wx0 = 0; private var wy0 = 0; private var dragging = false
    private val drag = View.OnTouchListener { _, ev ->
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> { dx0 = ev.rawX; dy0 = ev.rawY; wx0 = lp.x; wy0 = lp.y; dragging = false; false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(ev.rawX - dx0) > 10 || abs(ev.rawY - dy0) > 10) {
                    dragging = true; lp.x = (wx0 + ev.rawX - dx0).toInt(); lp.y = (wy0 + ev.rawY - dy0).toInt()
                    wm.updateViewLayout(overlay, lp)
                }; dragging
            }
            else -> if (dragging) true else false
        }
    }

    // ===== 核心：截图 =====
    private fun capture() {
        try {
            // 重建 projection（防止被系统回收）
            if (projection == null && _resultCode != -1 && _resultData != null) {
                log("rebuilding MediaProjection from code=$_resultCode")
                val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection = try {
                    pm.getMediaProjection(_resultCode, _resultData!!)
                } catch (e: SecurityException) {
                    log("SECURITY: ${e.message}")
                    null
                } catch (e: Exception) {
                    log("projection rebuild ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }

            if (projection == null) {
                log("projection null, launching CaptureActivity")
                runOnWebView("updateStatus('📋 申请截图权限…')")
                startActivity(Intent(this@OverlayService, CaptureActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // 权限回来后自动截图
                h.postDelayed({
                    if (_resultCode != -1 && _resultData != null) {
                        log("permission returned, auto-capture")
                        capture()
                    }
                }, 2500)
                return
            }

            log("capture start: ${screenW}x${screenH}")
            runOnWebView("updateStatus('📷 截图中…')")
            overlay?.visibility = View.INVISIBLE

            // 注册回调
            if (!callbackRegistered) {
                projection!!.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        log("projection stopped by system")
                        projection = null; callbackRegistered = false
                        runOnWebView("updateStatus('⚠ 截图权限失效')")
                    }
                }, h)
                callbackRegistered = true
                log("callback registered")
            }

            ir = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 1)
            vd = projection!!.createVirtualDisplay("ss", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir!!.surface, null, null)
            log("VirtualDisplay created")

            ir!!.setOnImageAvailableListener({ reader ->
                try {
                    val img = reader.acquireLatestImage()
                    if (img != null) {
                        log("image acquired: ${img.width}x${img.height} planes=${img.planes.size}")
                        val bmp = img2bmp(img)
                        log("bitmap: ${bmp.width}x${bmp.height}")
                        process(bmp)
                    } else {
                        log("acquireLatestImage returned NULL")
                        runOnWebView("updateStatus('⚠ 截图失败: 无数据')")
                    }
                    img?.close()
                } catch (e: Exception) {
                    log("image ERROR: ${e.javaClass.simpleName}: ${e.message}")
                    e.printStackTrace()
                } finally {
                    cleanup()
                }
            }, h)
        } catch (e: Exception) {
            log("capture CRASH: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            overlay?.visibility = View.VISIBLE
            runOnWebView("updateStatus('⚠ 截图异常: ${e.message}')")
        }
    }

    private fun img2bmp(img: Image): Bitmap {
        val p = img.planes[0]; val buf = p.buffer
        val ps = p.pixelStride; val rs = p.rowStride
        val rp = rs - ps * img.width
        val bmp = Bitmap.createBitmap(img.width + rp / ps, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return Bitmap.createBitmap(bmp, 0, 0, img.width, img.height)
    }

    private fun process(bmp: Bitmap) {
        try {
            val labels = recognizer.matchHand(bmp)
            log("recognized: ${labels.size} tiles: $labels")
            if (labels.isEmpty()) { runOnWebView("updateStatus('⚠ 未识别到手牌')"); return }
            val hand = labels.mapNotNull { ShantenAnalyzer.tileToInt(it) }.filter { it >= 0 }
            if (hand.size < 5) { runOnWebView("updateStatus('⚠ 手牌不足: ${hand.size}张')"); return }
            sendResult(hand.sorted())
        } catch (e: Exception) {
            log("process ERROR: ${e.javaClass.simpleName}: ${e.message}")
            runOnWebView("updateStatus('⚠ 识别异常')")
        }
    }

    private fun sendResult(hand: List<Int>) {
        val sr = ShantenAnalyzer.calcShanten(hand)
        val cands = ShantenAnalyzer.analyzeHand(hand)
        val json = JSONObject()
        json.put("shanten", sr.shanten)
        json.put("shanten_detail", JSONObject(sr.toMap()))
        json.put("best_discard", if (cands.isNotEmpty()) ShantenAnalyzer.intToTile(cands[0].tile) else "")
        val arr = JSONArray()
        cands.forEachIndexed { i, c ->
            val o = JSONObject()
            o.put("tile", ShantenAnalyzer.intToTile(c.tile))
            o.put("shanten_after", c.shantenAfter)
            o.put("ukeire_types", c.ukeireTypes)
            o.put("ukeire_count", c.ukeireCount)
            o.put("ukeire_tiles", JSONArray(c.ukeireTiles.map { ShantenAnalyzer.intToTile(it) }))
            o.put("wait_quality", c.waitQuality)
            o.put("is_best", i == 0)
            arr.put(o)
        }
        json.put("candidates", arr)
        val js = json.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        runOnWebView("onAnalyzeResult(\"$js\")")
        log("analysis done: shanten=${sr.shanten}")
    }

    private fun cleanup() {
        try { vd?.release() } catch (_: Exception) {}
        vd = null
        try { ir?.close() } catch (_: Exception) {}
        ir = null
    }

    private fun runOnWebView(js: String) {
        h.post {
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) {}
            overlay?.visibility = View.VISIBLE
        }
    }

    // ===== JS Bridge =====
    inner class Bridge {
        @JavascriptInterface fun screenshot() { h.post { capture() } }

        @JavascriptInterface fun analyze(json: String) {
            h.post {
                try {
                    val arr = JSONArray(json)
                    val labels = (0 until arr.length()).map { arr.getString(it) }
                    val hand = labels.mapNotNull { ShantenAnalyzer.tileToInt(it) }.filter { it >= 0 }
                    if (hand.size < 5) { runOnWebView("updateStatus('⚠ 手牌不足: ${hand.size}张')"); return@post }
                    sendResult(hand.sorted())
                } catch (e: Exception) {
                    log("analyze ERROR: ${e.message}")
                    runOnWebView("updateStatus('⚠ 分析出错')")
                }
            }
        }

        @JavascriptInterface fun setAutoScreenshot(on: Boolean) {
            if (on) { autoOn.set(true); h.post(autoTask) } else { autoOn.set(false); h.removeCallbacks(autoTask) }
        }

        @JavascriptInterface fun toast(msg: String) {
            h.post { Toast.makeText(this@OverlayService, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        autoOn.set(false); h.removeCallbacks(autoTask)
        cleanup()
        projection?.stop(); projection = null
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        super.onDestroy()
    }
}
