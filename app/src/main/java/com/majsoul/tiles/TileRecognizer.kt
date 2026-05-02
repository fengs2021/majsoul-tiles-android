package com.majsoul.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.IOException

/**
 * OpenCV 模板匹配识别日麻牌面
 * 34种牌 × 多尺度匹配 + dHash 备用验证
 */
class TileRecognizer(private val context: Context) {

    companion object {
        const val TAG = "TileRecognizer"
        // 34张牌的文件名 (与 assets/templates/ 对应)
        val TILE_LABELS = listOf(
            "一万", "二万", "三万", "四万", "五万", "六万", "七万", "八万", "九万",
            "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
            "一索", "二索", "三索", "四索", "五索", "六索", "七索", "八索", "九索",
            "東", "南", "西", "北", "白", "發", "中"
        )

        // 匹配阈值
        const val MATCH_THRESHOLD = 0.75
        // 多尺度范围
        val SCALES = listOf(0.85, 0.90, 0.95, 1.0, 1.05, 1.10, 1.15)
    }

    // 预加载的模板 (灰度图)
    private val templates = mutableListOf<Pair<Mat, String>>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            for (label in TILE_LABELS) {
                val fileName = "templates/${label}.png"
                val stream = context.assets.open(fileName)
                val bmp = BitmapFactory.decodeStream(stream)
                stream.close()

                val mat = Mat()
                Utils.bitmapToMat(bmp, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)
                templates.add(Pair(mat, label))
                bmp.recycle()
            }
            loaded = true
            Log.d(TAG, "Loaded ${templates.size} templates")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }

    /**
     * 从截图 Bitmap 中识别手牌
     * 返回识别到的牌名列表
     */
    fun matchHand(screenshot: Bitmap): List<String> {
        ensureLoaded()
        if (templates.isEmpty()) return emptyList()

        // 转为 OpenCV Mat
        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_BGR2GRAY)

        // 定位手牌区域（雀魂手牌在屏幕底部偏下位置）
        val handRegion = locateHandRegion(gray, screenshot.width, screenshot.height)

        val results = mutableListOf<Pair<String, Double>>()
        val usedLocations = mutableListOf<Rect>()

        // 对每个模板做多尺度匹配
        for ((template, label) in templates) {
            var bestScore = 0.0
            var bestLoc = Rect()

            for (scale in SCALES) {
                val scaledW = (template.cols() * scale).toInt()
                val scaledH = (template.rows() * scale).toInt()
                if (scaledW < 10 || scaledH < 10 || scaledW > handRegion.cols() || scaledH > handRegion.rows())
                    continue

                val scaled = Mat()
                Imgproc.resize(template, scaled, Size(scaledW.toDouble(), scaledH.toDouble()))

                val result = Mat()
                Imgproc.matchTemplate(handRegion, scaled, result, Imgproc.TM_CCOEFF_NORMED)

                val minMax = Core.minMaxLoc(result)
                if (minMax.maxVal > bestScore) {
                    bestScore = minMax.maxVal
                    bestLoc = Rect(
                        minMax.maxLoc.x.toInt(),
                        minMax.maxLoc.y.toInt(),
                        scaledW, scaledH
                    )
                }
                scaled.release()
                result.release()
            }

            if (bestScore >= MATCH_THRESHOLD) {
                // 检查是否与已识别位置重叠
                val overlap = usedLocations.any { rectOverlap(it, bestLoc) > 0.5 }
                if (!overlap) {
                    results.add(Pair(label, bestScore))
                    usedLocations.add(bestLoc)
                }
            }
        }

        // 按 x 坐标排序（从左到右）
        results.sortBy { usedLocations.getOrNull(results.indexOf(it))?.x ?: 0 }

        gray.release()
        srcMat.release()

        val labels = results.map { it.first }
        Log.d(TAG, "Recognized ${labels.size} tiles: $labels")
        return labels
    }

    /**
     * 定位手牌区域：检测屏幕底部的牌
     * 雀魂手牌通常在画面下方 15% 区域
     */
    private fun locateHandRegion(gray: Mat, width: Int, height: Int): Mat {
        // 裁剪底部 30% 区域（手牌通常在此处）
        val bottomY = (height * 0.65).toInt()
        val cropH = (height * 0.30).toInt()
        val region = Mat(gray, Rect(0, bottomY, width, cropH))

        // 边缘检测找牌块
        val edges = Mat()
        Imgproc.Canny(region, edges, 50.0, 150.0)

        // 膨胀连接近邻边缘
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(edges, edges, kernel)
        kernel.release()

        edges.release()
        return region
    }

    /**
     * 两个矩形的重叠比例
     */
    private fun rectOverlap(a: Rect, b: Rect): Double {
        val xOverlap = maxOf(0, minOf(a.x + a.width, b.x + b.width) - maxOf(a.x, b.x))
        val yOverlap = maxOf(0, minOf(a.y + a.height, b.y + b.height) - maxOf(a.y, b.y))
        val overlapArea = xOverlap * yOverlap
        val minArea = minOf(a.area(), b.area()).toDouble()
        return if (minArea > 0) overlapArea / minArea else 0.0
    }

    /**
     * dHash 计算（备用验证）
     */
    fun dHash(bitmap: Bitmap): Long {
        val resized = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                val left = resized.getPixel(x, y) and 0xFF
                val right = resized.getPixel(x + 1, y) and 0xFF
                hash = (hash shl 1) or (if (left < right) 1 else 0)
            }
        }
        resized.recycle()
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int =
        java.lang.Long.bitCount(a xor b)
}
