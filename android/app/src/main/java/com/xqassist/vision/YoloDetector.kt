package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xqassist.core.Position
import java.io.File

/**
 * Pro YOLO（已导出 param+bin）封装。
 * 输出 22×8400：ch0-3=cx,cy,w,h（640 画布），ch4-18=15 类概率。
 */
object YoloDetector {

    private const val TAG = "Yolo"
    const val BOARD_CLASS = 14

    data class Det(val cls: Int, val score: Float, val cx: Float, val cy: Float, val w: Float, val h: Float)

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    var lastDetectSummary: String = ""
        private set

    fun init(context: Context): Boolean {
        if (isReady) return true
        return try {
            val dir = File(context.filesDir, "yolo")
            if (!dir.exists()) dir.mkdirs()
            val param = File(dir, "yolov13.param")
            val bin = File(dir, "yolov13.bin")
            if (!param.exists() || param.length() == 0L) {
                copyAsset(context, "yolo/yolov13.param", param)
            }
            if (!bin.exists() || bin.length() == 0L) {
                copyAsset(context, "yolo/yolov13.bin", bin)
            }
            val ok = YoloNcnn.init(param.absolutePath, bin.absolutePath)
            isReady = ok
            lastError = if (ok) "" else YoloNcnn.lastError.ifBlank { "init false" }
            Log.i(TAG, "init ok=$ok err=$lastError")
            ok
        } catch (t: Throwable) {
            lastError = t.message ?: "init"
            Log.e(TAG, "init failed", t)
            false
        }
    }

    private fun copyAsset(context: Context, name: String, dest: File) {
        context.assets.open(name).use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    /** 解码检测：JNI 已统一输出 anchor-major [N*22]，直接按 stride=22 解码 */
    fun detectPieces(bitmap: Bitmap): List<Det>? {
        val raw = YoloNcnn.detect(bitmap) ?: return null
        val total = raw.size
        if (total < 22) return null

        // JNI 保证 anchor-major：每 22 个 float 一个检测
        val stride = 22
        val n = total / stride
        if (n <= 0) return null
        val nCls = 15  // ch4..18 = 15 类

        // letterbox params must match JNI
        val W = bitmap.width
        val H = bitmap.height
        val S = 640
        val sc = S.toFloat() / maxOf(W, H)
        val nw = (W * sc).toInt()
        val nh = (H * sc).toInt()
        val ox = (S - nw) / 2f
        val oy = (S - nh) / 2f

        fun at(det: Int, ch: Int): Float = raw[det * stride + ch]

        val dets = ArrayList<Det>()
        for (i in 0 until n) {
            val bw = at(i, 2)
            val bh = at(i, 3)
            val sz = minOf(bw, bh)
            if (sz < 18f || sz > 90f) continue
            var best = -1f
            var bestC = -1
            for (k in 0 until nCls) {
                val s = at(i, 4 + k)
                if (s > best) {
                    best = s
                    bestC = k
                }
            }
            if (best < 0.5f || bestC < 0) continue
            val cx = at(i, 0)
            val cy = at(i, 1)
            // 640 → 原图
            val mx = (cx - ox) / sc
            val my = (cy - oy) / sc
            val mw = bw / sc
            val mh = bh / sc
            dets.add(Det(bestC, best, mx, my, mw, mh))
        }
        Log.i(TAG, "detectPieces n=$n kept0=${dets.size}")
        // NMS
        val kept = nms(dets)
        // Pro j.g：按像素颜色校正红黑（classId +7 = 黑）
        val corrected = colorCorrect(bitmap, kept)
        lastDetectSummary = "yolo n=${corrected.size} raw=${dets.size}"
        Log.i(TAG, "detectPieces kept=${corrected.size} raw=${dets.size}")
        return corrected
    }

    /**
     * Pro w2.j.g 颜色校正：
     * - 跳过 classId ∈ {2,3,4,6,9,11,13,14}（模型已定色）
     * - 采样棋子边缘像素判红/黑
     * - 黑：classId+7；classId==10 且红：classId-7
     */
    private fun colorCorrect(bitmap: Bitmap, dets: List<Det>): List<Det> {
        val out = ArrayList<Det>(dets.size)
        for (d in dets) {
            var cls = d.cls
            if (cls != 2 && cls != 3 && cls != 4 && cls != 6 &&
                cls != 9 && cls != 11 && cls != 13 && cls != 14
            ) {
                val isRed = sampleIsRed(bitmap, d)
                if (cls == 10) {
                    if (isRed) cls -= 7
                } else if (!isRed) {
                    cls += 7
                }
            }
            if (cls in 0..14) out.add(d.copy(cls = cls))
        }
        return out
    }

    /** 采样棋子外框 1/3–2/3 区域边缘像素，红多则 true */
    private fun sampleIsRed(bitmap: Bitmap, d: Det): Boolean {
        val x1 = (d.cx - d.w / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val x2 = (d.cx + d.w / 2f).toInt().coerceIn(0, bitmap.width - 1)
        val y1 = (d.cy - d.h / 2f).toInt().coerceIn(0, bitmap.height - 1)
        val y2 = (d.cy + d.h / 2f).toInt().coerceIn(0, bitmap.height - 1)
        if (x2 - x1 < 3 || y2 - y1 < 3) return true
        val xa = x1 + (x2 - x1) / 3
        val xb = x1 + (x2 - x1) * 2 / 3
        val ya = y1 + (y2 - y1) / 3
        val yb = y1 + (y2 - y1) * 2 / 3
        val ymid = (y1 + y2) / 2
        var red = 0
        var black = 0
        fun count(px: Int) {
            val r = android.graphics.Color.red(px)
            val g = android.graphics.Color.green(px)
            val b = android.graphics.Color.blue(px)
            val lum = (r * 299 + g * 587 + b * 114) / 1000f
            if (r > 120 && r > g * 1.5f && r > b * 1.5f && r - g > 60 && r - b > 60) red++
            else if (lum < 60f && kotlin.math.abs(r - g) < 30 && kotlin.math.abs(r - b) < 30) black++
        }
        var x = xa
        while (x < xb) { count(bitmap.getPixel(x, ya)); count(bitmap.getPixel(x, yb)); x++ }
        var y = ya
        while (y < yb) { count(bitmap.getPixel(xa, y)); count(bitmap.getPixel(xb, y)); y++ }
        var x2s = xa
        while (x2s < xb) { count(bitmap.getPixel(x2s, ymid)); x2s++ }
        return red > black
    }

    private fun nms(dets: List<Det>, iouTh: Float = 0.4f): List<Det> {
        val sorted = dets.sortedByDescending { it.score }
        val keep = ArrayList<Det>()
        for (d in sorted) {
            var ok = true
            for (k in keep) {
                if (iou(d, k) > iouTh) {
                    ok = false
                    break
                }
            }
            if (ok) keep.add(d)
        }
        return keep
    }

    private fun iou(a: Det, b: Det): Float {
        val ax1 = a.cx - a.w / 2; val ay1 = a.cy - a.h / 2
        val ax2 = a.cx + a.w / 2; val ay2 = a.cy + a.h / 2
        val bx1 = b.cx - b.w / 2; val by1 = b.cy - b.h / 2
        val bx2 = b.cx + b.w / 2; val by2 = b.cy + b.h / 2
        val ix = maxOf(0f, minOf(ax2, bx2) - maxOf(ax1, bx1))
        val iy = maxOf(0f, minOf(ay2, by2) - maxOf(ay1, by1))
        val inter = ix * iy
        val ua = a.w * a.h + b.w * b.h - inter
        return if (ua <= 0f) 0f else inter / ua
    }

    /** 外框 /10、/11 与 Pro 一致 */
    fun cellOf(outer: BoardRect, cx: Float, cy: Float): Pair<Int, Int>? {
        val w = (outer.right - outer.left).coerceAtLeast(1)
        val h = (outer.bottom - outer.top).coerceAtLeast(1)
        val cellW = w / 10f
        val cellH = h / 11f
        if (cellW < 4f || cellH < 4f) return null
        val centerX = (outer.left + outer.right) / 2f
        val file = Math.round(4 - (centerX - cx) / cellW)
        val first = outer.top + cellH
        val last = outer.bottom - cellH
        val rank = if (kotlin.math.abs(first - cy) < kotlin.math.abs(last - cy)) {
            Math.round(kotlin.math.abs(cy - first) / cellH)
        } else {
            9 - Math.round(kotlin.math.abs(cy - last) / cellH)
        }
        if (rank !in 0..9 || file !in 0..8) return null
        return rank to file
    }

    /** 从棋子中心拟合外框（对齐 Pro：子区外扩 1 格） */
    fun outerFromPieces(dets: List<Det>): BoardRect? {
        val pieces = dets.filter { it.cls != BOARD_CLASS }
        if (pieces.size < 4) return null
        val minX = pieces.minOf { it.cx }
        val maxX = pieces.maxOf { it.cx }
        val minY = pieces.minOf { it.cy }
        val maxY = pieces.maxOf { it.cy }
        // 估格距：同行最小水平间距
        val sorted = pieces.sortedBy { it.cy }
        val colSp = ArrayList<Float>()
        var i = 0
        while (i < sorted.size) {
            var j = i + 1
            val row = ArrayList<Det>()
            row.add(sorted[i])
            while (j < sorted.size && kotlin.math.abs(sorted[j].cy - sorted[i].cy) < 50f) {
                row.add(sorted[j]); j++
            }
            val xs = row.map { it.cx }.sorted()
            for (k in 1 until xs.size) {
                val d = xs[k] - xs[k - 1]
                if (d in 70f..200f) colSp.add(d)
            }
            i = j
        }
        val cell = if (colSp.isNotEmpty()) colSp.min() else (maxX - minX) / 8f
        if (cell < 70f) return null
        return BoardRect(
            left = (minX - cell).toInt(),
            top = (minY - cell).toInt(),
            right = (maxX + cell).toInt(),
            bottom = (maxY + cell).toInt(),
        )
    }

    /**
     * Pro w2.j.b：优先用 classId==14（棋盘）检测框作为 rect。
     * 返回 null 表示没检测到棋盘框，需 fallback 到 outerFromPieces。
     */
    fun findBoardRect(dets: List<Det>, minScore: Float = 0.5f): BoardRect? {
        // 从后往前扫，和 Pro 一致
        for (i in dets.indices.reversed()) {
            val d = dets[i]
            if (d.cls == BOARD_CLASS && d.score > minScore) {
                val x1 = (d.cx - d.w / 2f).toInt()
                val y1 = (d.cy - d.h / 2f).toInt()
                val x2 = (d.cx + d.w / 2f).toInt()
                val y2 = (d.cy + d.h / 2f).toInt()
                if (x2 - x1 > 50 && y2 - y1 > 50) {
                    return BoardRect(left = x1, top = y1, right = x2, bottom = y2)
                }
            }
        }
        return null
    }

    fun toGrid(dets: List<Det>, outer: BoardRect): Array<IntArray> {
        val grid = Array(10) { IntArray(9) { -1 } }
        val conf = Array(10) { FloatArray(9) { -1f } }
        for (d in dets) {
            if (d.cls == BOARD_CLASS) continue
            val cell = cellOf(outer, d.cx, d.cy) ?: continue
            val (rank, file) = cell
            if (d.score >= conf[rank][file]) {
                grid[rank][file] = d.cls
                conf[rank][file] = d.score
            }
        }
        return grid
    }

    private val FEN_CHARS = charArrayOf('R', 'N', 'B', 'A', 'K', 'C', 'P', 'r', 'n', 'b', 'a', 'k', 'c', 'p')

    fun gridToFen(grid: Array<IntArray>): String {
        val sb = StringBuilder()
        for (rank in 0 until 10) {
            var empty = 0
            for (file in 0 until 9) {
                val id = grid[rank][file]
                if (id < 0) empty++
                else {
                    if (empty > 0) { sb.append(empty); empty = 0 }
                    sb.append(FEN_CHARS[id])
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank < 9) sb.append('/')
        }
        return sb.toString()
    }

    fun orient(grid: Array<IntArray>): Array<IntArray> {
        var redKingRank = -1
        for (r in 0 until 10) for (f in 0 until 9) if (grid[r][f] == 4) redKingRank = r
        return if (redKingRank in 0..4) rotate180(grid) else grid
    }

    private fun rotate180(grid: Array<IntArray>): Array<IntArray> {
        val out = Array(10) { IntArray(9) }
        for (r in 0 until 10) for (f in 0 until 9) out[r][f] = grid[9 - r][8 - f]
        return out
    }

    fun gridToPosition(grid: Array<IntArray>): Position {
        return Position.fromFen(gridToFen(grid) + " w - - 0 1")
    }

    fun countPieces(grid: Array<IntArray>): Int {
        var n = 0
        for (r in 0 until 10) for (f in 0 until 9) if (grid[r][f] >= 0) n++
        return n
    }

    fun hasKing(grid: Array<IntArray>, red: Boolean): Boolean {
        val id = if (red) 4 else 11
        for (r in 0 until 10) for (f in 0 until 9) if (grid[r][f] == id) return true
        return false
    }
}
