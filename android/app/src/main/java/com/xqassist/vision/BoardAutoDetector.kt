package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 天天象棋实机找盘：
 * 1) 在浅色盘面上找「墨字」像素
 * 2) 用同行子的水平间距中位数估格距，拟合原点（对齐 Pro：子框 → 外框）
 * 3) 退回木色 bbox 仅作兜底
 */
object BoardAutoDetector {

    data class PieceBlob(val cx: Int, val cy: Int, val pixels: Int)

    fun detect(frame: Bitmap): BoardRect? {
        val w = frame.width
        val h = frame.height
        if (w < 50 || h < 50) return null
        val step = maxOf(2, minOf(w, h) / 300)

        // 避开顶部悬浮窗
        val topSkip = h / 5
        val bottomSkip = h / 8

        val peaks = findPiecePeaks(frame, step, topSkip, h - bottomSkip)
        if (peaks.size >= 6) {
            fitBoardFromPieces(peaks, w, h)?.let { return it }
        }

        val wood = detectByWood(frame, step, topSkip, h - bottomSkip)
        return wood
    }

    /** 墨字密度峰 = 棋子中心候选（比 flood-fill 更抗粘连） */
    private fun findPiecePeaks(frame: Bitmap, step: Int, y0: Int, y1: Int): List<PieceBlob> {
        val w = frame.width
        val cell = maxOf(step * 3, minOf(w, y1 - y0) / 28)
        val accX = HashMap<Int, Int>()
        val accY = HashMap<Int, Int>()
        val accN = HashMap<Int, Int>()
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < w) {
                if (isInk(frame.getPixel(x, y))) {
                    val bx = x / cell
                    val by = y / cell
                    val key = bx * 10000 + by
                    accX[key] = (accX[key] ?: 0) + x
                    accY[key] = (accY[key] ?: 0) + y
                    accN[key] = (accN[key] ?: 0) + 1
                }
                x += step
            }
            y += step
        }
        val peaks = ArrayList<PieceBlob>()
        for ((key, n) in accN) {
            if (n < 6) continue
            val cx = accX.getValue(key) / n
            val cy = accY.getValue(key) / n
            peaks.add(PieceBlob(cx, cy, n))
        }
        // 非极大值抑制：半径约 0.6 cell 内只留最强
        peaks.sortByDescending { it.pixels }
        val kept = ArrayList<PieceBlob>()
        val minDist = (cell * 0.55f)
        for (p in peaks) {
            var ok = true
            for (k in kept) {
                if (hypot(p.cx - k.cx, p.cy - k.cy) < minDist) {
                    ok = false
                    break
                }
            }
            if (ok) kept.add(p)
            if (kept.size >= 40) break
        }
        return kept
    }

    private fun hypot(dx: Int, dy: Int): Float {
        val a = dx.toFloat(); val b = dy.toFloat()
        return sqrt(a * a + b * b)
    }

    /**
     * 格点拟合：已知 cellW/cellH 后，枚举「某子落在 (file,rank)」，
     * 选让最多子落在格点上的原点，再生成 /10、/11 外框。
     */
    private fun fitBoardFromPieces(peaks: List<PieceBlob>, imgW: Int, imgH: Int): BoardRect? {
        val sorted = peaks.sortedBy { it.cy }
        val rows = ArrayList<MutableList<PieceBlob>>()
        for (p in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(p.cy - (last.map { it.cy }.average())) < 50) {
                last.add(p)
            } else {
                rows.add(mutableListOf(p))
            }
        }
        val colSpacings = ArrayList<Int>()
        for (row in rows) {
            if (row.size < 2) continue
            val xs = row.map { it.cx }.sorted()
            for (i in 1 until xs.size) {
                val d = xs[i] - xs[i - 1]
                if (d in 80..200) colSpacings.add(d)
            }
        }
        val rowYs = rows.map { row -> row.map { it.cy }.average().toInt() }.sorted()
        val rowSpacings = ArrayList<Int>()
        for (i in 1 until rowYs.size) {
            val d = rowYs[i] - rowYs[i - 1]
            if (d in 80..200) rowSpacings.add(d)
        }
        if (colSpacings.size < 2 || rowSpacings.isEmpty()) return null
        // 真实格距用最小间距，避免 2 格跨度把中位数拉大
        val cellW = colSpacings.min()
        val cellH = rowSpacings.min()
        if (cellW < 80 || cellH < 80) return null

        var bestScore = -1
        var bestLeft = 0 // file0 的 x
        var bestTop = 0  // rank0 的 y
        val tolX = (cellW * 0.28f).toInt()
        val tolY = (cellH * 0.28f).toInt()
        for (p in peaks) {
            for (file in 0..8) {
                for (rank in 0..9) {
                    val left0 = p.cx - file * cellW
                    val top0 = p.cy - rank * cellH
                    var score = 0
                    for (q in peaks) {
                        val fx = (q.cx - left0).toFloat() / cellW
                        val fy = (q.cy - top0).toFloat() / cellH
                        if (fx in -0.2f..8.2f && fy in -0.2f..9.2f) {
                            val ix = Math.round(fx)
                            val iy = Math.round(fy)
                            if (ix in 0..8 && iy in 0..9 &&
                                abs(left0 + ix * cellW - q.cx) <= tolX &&
                                abs(top0 + iy * cellH - q.cy) <= tolY
                            ) score++
                        }
                    }
                    if (score > bestScore) {
                        bestScore = score
                        bestLeft = left0
                        bestTop = top0
                    }
                }
            }
        }
        if (bestScore < maxOf(4, peaks.size / 3)) return null

        // 外框 = 格点区再外扩 1 格（对齐 Pro）；不强夹到屏幕，否则格距会变
        val left = bestLeft - cellW
        val top = bestTop - cellH
        val right = bestLeft + 9 * cellW
        val bottom = bestTop + 10 * cellH
        if (right - left < 200 || bottom - top < 200) return null
        return BoardRect(left, top, right, bottom)
    }

    private fun detectByWood(frame: Bitmap, step: Int, y0: Int, y1: Int): BoardRect? {
        val w = frame.width
        val h = frame.height
        // 只统计「浅色棋盘」而非深棕桌板
        val buckets = HashMap<Int, IntArray>() // y/80 -> [minX,maxX,count]
        var minY = h
        var maxY = 0
        var minX = w
        var maxX = 0
        var count = 0
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < w) {
                if (isLightBoard(frame.getPixel(x, y))) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    count++
                }
                x += step
            }
            y += step
        }
        if (count < 400) return null
        // 收掉一点边，去掉外框高光
        val mx = (maxX - minX) / 30
        val my = (maxY - minY) / 40
        val rect = BoardRect(
            left = (minX + mx).coerceAtLeast(0),
            top = (minY + my).coerceAtLeast(0),
            right = (maxX - mx).coerceAtMost(w - 1),
            bottom = (maxY - my).coerceAtMost(h - 1),
        )
        if (rect.right - rect.left < 200 || rect.bottom - rect.top < 200) return null
        return rect
    }

    /** 浅米色棋盘面 */
    private fun isLightBoard(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r < 195 || r > 250) return false
        if (g < 155 || g > 225) return false
        if (b < 100 || b > 185) return false
        if (r - b < 30) return false
        if (r - g < 10) return false
        return true
    }

    /** 红/黑字墨迹 */
    private fun isInk(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        // 米色盘排除
        if (r > 170 && g > 130 && r > g && g >= b - 15 && r - b > 15) return false
        // 红字
        if (r >= 140 && r >= g + 55 && r >= b + 55 && g <= 155) return true
        // 黑字
        if (maxC < 110 && maxC - minC < 40) return true
        return false
    }
}
