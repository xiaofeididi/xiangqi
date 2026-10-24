package com.xqassist.vision

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 网格定位（0.2.99 新增）。
 *
 * 适用场景：木纹/彩色棋盘皮肤上的"深色细线"网格（实测于第三方棋类 App 截图）。
 *
 * 与 [BoardAutoDetector] 的区别：后者靠"木色块"求包围盒，遇到深色底/渐变/棋盘外框不一致时会偏；
 * 本类直接找**网格线**——
 *   1. 降采样后取灰度；
 *   2. 逐像素算"细线分"：本像素比左右(上下) ±3px 明显更暗，才算线（这样棋子的圆盘不会被误判成线）；
 *   3. 对列/行分别取峰，再用"等距约束"挑出 9 条竖线 / 10 条横线（变异系数最小）；
 *   4. 返回的 BoardRect 是**棋盘外框**（宽 10 格、高 11 格，与 ChessboardReader 的 w/10、h/11 约定一致），
 *      由交叉点范围向四周各外扩一格得到。
 *
 * 实测样例（1080×2400 木纹棋盘）：竖线 x=80,196,310,426,540,654,770,884,1010；
 * 横线 y=692,806,920,1032,1146,1264,1378,1490,1604,1730，格距 116×115。
 */
object LineGridLocator {

    private const val DOWNSCALE = 2          // 降采样倍率
    private const val LINE_DELTA = 22        // 细线判定：两侧亮度差阈值
    private const val LINE_OFFSET = 3        // 细线判定：左右/上下偏移
    private const val MAX_CV = 0.12f         // 允许的间距变异系数

    fun locate(frame: Bitmap): BoardRect? {
        if (frame.width < 200 || frame.height < 200) return null

        val small = Bitmap.createScaledBitmap(
            frame,
            max(1, frame.width / DOWNSCALE),
            max(1, frame.height / DOWNSCALE),
            true,
        )
        val cw = small.width
        val ch = small.height
        if (cw < 100 || ch < 100) { if (small !== frame) small.recycle(); return null }

        val pixels = IntArray(cw * ch)
        small.getPixels(pixels, 0, cw, 0, 0, cw, ch)
        if (small !== frame) small.recycle()

        // 灰度
        val g = IntArray(cw * ch)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val gr = (c shr 8) and 0xFF
            val b = c and 0xFF
            g[i] = (r * 77 + gr * 151 + b * 28) shr 8
        }

        // 细线评分
        val colScore = IntArray(cw)
        val rowScore = IntArray(ch)
        val o = LINE_OFFSET
        for (y in o until ch - o) {
            val b0 = (y - o) * cw
            val b1 = y * cw
            val b2 = (y + o) * cw
            for (x in o until cw - o) {
                val v = g[b1 + x]
                if (g[b1 + x - o] - v > LINE_DELTA && g[b1 + x + o] - v > LINE_DELTA) colScore[x]++
                if (g[b0 + x] - v > LINE_DELTA && g[b2 + x] - v > LINE_DELTA) rowScore[y]++
            }
        }

        val colPeaks = topPeaks(colScore, 12, 14)
        val rowPeaks = topPeaks(rowScore, 12, 16)
        if (colPeaks.size < 9 || rowPeaks.size < 10) return null

        val cols = bestEvenSubset(colPeaks, 9) ?: return null
        val rows = bestEvenSubset(rowPeaks, 10) ?: return null

        val stepX = (cols.last() - cols.first()) / 8f
        val stepY = (rows.last() - rows.first()) / 9f
        if (stepX < 12f || stepY < 12f) return null
        val ratio = stepX / stepY
        if (ratio < 0.75f || ratio > 1.35f) return null

        // 棋盘至少要占画面 1/4
        if (cols.last() - cols.first() < cw * 0.25f) return null
        if (rows.last() - rows.first() < ch * 0.25f) return null

        // 下游（ChessboardReader / TemplatePieceReader / YoloDetector.cellOf / autoPlay）
        // 统一按"棋盘外框"约定：宽 = 10 格、高 = 11 格，第一条线在 left+cellW / top+cellH。
        // 这里拿到的是 9 竖 10 横（8×9 格）的交叉点范围，必须四周各外扩一格。
        // 外扩后可能为负（棋盘贴屏幕边时），下游采样都做了越界判断，不会崩。
        fun outer(v: Float) = (v * DOWNSCALE).toInt()
        return BoardRect(
            left = outer(cols.first() - stepX),
            top = outer(rows.first() - stepY),
            right = outer(cols.last() + stepX),
            bottom = outer(rows.last() + stepY),
        )
    }

    /** 取前 n 个峰（按分值），峰之间至少隔 minGap，返回升序索引。 */
    private fun topPeaks(arr: IntArray, minGap: Int, n: Int): List<Int> {
        val order = arr.indices.sortedByDescending { arr[it] }
        val picked = ArrayList<Int>(n)
        for (i in order) {
            if (arr[i] <= 0) break
            var ok = true
            for (p in picked) if (abs(p - i) < minGap) { ok = false; break }
            if (ok) {
                picked.add(i)
                if (picked.size >= n) break
            }
        }
        picked.sort()
        return picked
    }

    /**
     * 从候选里挑 n 条**间距最均匀**的线：滑窗 + 变异系数。
     * 只要最后一格偏差稍大（透视/边框）也允许，CV 阈值放到 [MAX_CV]。
     */
    private fun bestEvenSubset(candidates: List<Int>, n: Int): IntArray? {
        if (candidates.size < n) return null
        var best: IntArray? = null
        var bestCv = Float.MAX_VALUE
        for (start in 0..(candidates.size - n)) {
            val win = IntArray(n) { candidates[start + it] }
            val diffs = FloatArray(n - 1) { (win[it + 1] - win[it]).toFloat() }
            val mean = diffs.average().toFloat()
            if (mean <= 0f) continue
            var varr = 0f
            for (d in diffs) varr += (d - mean) * (d - mean)
            varr /= diffs.size
            val cv = sqrt(varr) / mean
            if (cv > MAX_CV) continue
            if (cv < bestCv) { bestCv = cv; best = win }
        }
        return best
    }

    /** 供调试：返回定位所用的阈值说明。 */
    fun describe(): String = "LineGridLocator(ds=$DOWNSCALE, delta=$LINE_DELTA, cv=$MAX_CV)"
}
