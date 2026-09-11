package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color

/**
 * 自动检测棋盘区域（天天象棋木色棋盘）。
 * Pro 用 NCNN YOLO 检测棋子再推导棋盘；我们用颜色扫描找最大木色矩形。
 */
object BoardAutoDetector {

    fun detect(frame: Bitmap): BoardRect? {
        val w = frame.width
        val h = frame.height
        if (w < 50 || h < 50) return null

        // 采样步长，性能与精度折中
        val step = maxOf(2, minOf(w, h) / 200)
        var minX = w
        var minY = h
        var maxX = 0
        var maxY = 0
        var count = 0

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                if (isBoardColor(frame.getPixel(x, y))) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    count++
                }
            }
        }

        if (count < 200) return null

        // 收紧到有效区域，并留一点边距（棋盘四角交叉点在外框内侧）
        val marginX = (maxX - minX) / 40
        val marginY = (maxY - minY) / 40
        val rect = BoardRect(
            left = (minX + marginX).coerceAtLeast(0),
            top = (minY + marginY).coerceAtLeast(0),
            right = (maxX - marginX).coerceAtMost(w - 1),
            bottom = (maxY - marginY).coerceAtMost(h - 1),
        )
        if (rect.right - rect.left < 80 || rect.bottom - rect.top < 80) return null
        return rect
    }

    /** 天天象棋木色棋盘：偏黄棕，饱和度中等 */
    private fun isBoardColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // 木色：R>G>B，且不接近黑/白/纯红纯蓝
        if (r < 120 || r > 240) return false
        if (g < 90 || g > 210) return false
        if (b < 60 || b > 180) return false
        if (r - b < 30) return false
        if (r - g < 10) return false
        return true
    }
}
