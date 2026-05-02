package com.majsoul.tiles

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.IOException
import kotlin.math.abs

/**
 * 模板匹配识别日麻牌面（雀魂 Android 版）
 * 使用雀魂真实截图模板，自适应阈值，多尺度 + 重叠去重
 *
 * 模板来源: Anistorica1/Majiang (雀魂桌面版截图 45×77px)
 */
class TileRecognizer(private val context: Context) {

    companion object {
        const val TAG = "TileRecognizer"

        val TILE_LABELS = listOf(
            "一万", "二万", "三万", "四万", "五万", "六万", "七万", "八万", "九万",
            "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
            "一索", "二索", "三索", "四索", "五索", "六索", "七索", "八索", "九索",
            "東", "南", "西", "北", "白", "發", "中"
        )

        // 每个字牌的阈值较低（易混淆），数牌阈值较高
        private val DIFFICULT_TILES = setOf("八筒", "九筒", "白", "五筒", "七索", "九索", "四索")
        const val THRESHOLD_NORMAL = 0.80
        const val THRESHOLD_EASY = 0.72

        // 桌面版模板 45×77，Android 屏幕更大，基础缩放
        val SCALES = listOf(0.8, 0.95, 1.1, 1.25, 1.40, 1.55)

        // 去重重叠阈值
        const val OVERLAP_THRESHOLD = 0.35
    }

    private val templates = mutableListOf<Triple<Mat, String, Double>>()  // 模板, 标签, 阈值
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

                val threshold = if (label in DIFFICULT_TILES) THRESHOLD_EASY else THRESHOLD_NORMAL
                templates.add(Triple(mat, label, threshold))
                bmp.recycle()
            }
            loaded = true
            Log.d(TAG, "Loaded ${templates.size} 雀魂 templates")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to load templates", e)
        }
    }

    /**
     * 从截图 Bitmap 中识别手牌
     * 返回识别到的牌名列表（从左到右排序）
     */
    fun matchHand(screenshot: Bitmap): List<String> {
        ensureLoaded()
        if (templates.isEmpty()) return emptyList()

        val srcMat = Mat()
        Utils.bitmapToMat(screenshot, srcMat)
        val gray = Mat()
        Imgproc.cvtColor(srcMat, gray, Imgproc.COLOR_BGR2GRAY)

        // 定位雀魂手牌区域（画面底部偏上一点）
        val handRegion = locateHandRegion(gray, screenshot.width, screenshot.height)

        // 检测结果: (label, x坐标, 分数)
        val detections = mutableListOf<Triple<String, Int, Double>>()

        for ((template, label, threshold) in templates) {
            var bestScore = 0.0
            var bestX = 0

            for (scale in SCALES) {
                val sw = (template.cols() * scale).toInt()
                val sh = (template.rows() * scale).toInt()
                if (sw < 8 || sh < 8 || sw > handRegion.cols() || sh > handRegion.rows())
                    continue

                val scaled = Mat()
                Imgproc.resize(template, scaled, Size(sw.toDouble(), sh.toDouble()))
                val result = Mat()
                Imgproc.matchTemplate(handRegion, scaled, result, Imgproc.TM_CCOEFF_NORMED)

                val minMax = Core.minMaxLoc(result)
                if (minMax.maxVal > bestScore) {
                    bestScore = minMax.maxVal
                    bestX = minMax.maxLoc.x.toInt()
                }
                scaled.release()
                result.release()
            }

            if (bestScore >= threshold) {
                detections.add(Triple(label, bestX, bestScore))
            }
        }

        // 按 x 坐标排序
        detections.sortBy { it.second }

        // 去重：同一位置只保留最高分的匹配
        val filtered = mutableListOf<Triple<String, Int, Double>>()
        for (d in detections) {
            val overlap = filtered.any { abs(it.second - d.second) < 15 }
            if (!overlap) {
                filtered.add(d)
            } else {
                // 替换为分更高的
                val idx = filtered.indexOfFirst { abs(it.second - d.second) < 15 }
                if (idx >= 0 && d.third > filtered[idx].third) {
                    filtered[idx] = d
                }
            }
        }

        gray.release()
        srcMat.release()

        val labels = filtered.map { it.first }
        Log.d(TAG, "Recognized ${labels.size} tiles: $labels (${filtered.map { "%.2f".format(it.third) }})")
        return labels
    }

    /**
     * 定位雀魂手牌区域
     * Android 雀魂: 手牌在画面下部 65%~85% 高度
     */
    private fun locateHandRegion(gray: Mat, width: Int, height: Int): Mat {
        // 雀魂手牌区: 画面底部往上 15-30% 区域
        val top = (height * 0.68).toInt()
        val h = minOf((height * 0.22).toInt(), height - top)
        return Mat(gray, Rect(0, top, width, h))
    }
}
