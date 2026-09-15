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

    /** 解码检测：返回棋子列表（去掉盘、按尺寸过滤） */
    fun detectPieces(bitmap: Bitmap): List<Det>? {
        val raw = YoloNcnn.detect(bitmap) ?: return null
        val c = 22
        val n = raw.size / c
        if (n <= 0) return null
        // letterbox params must match JNI
        val W = bitmap.width
        val H = bitmap.height
        val S = 640
        val sc = S.toFloat() / maxOf(W, H)
        val nw = (W * sc).toInt()
        val nh = (H * sc).toInt()
        val ox = (S - nw) / 2f
        val oy = (S - nh) / 2f

        val dets = ArrayList<Det>()
        for (i in 0 until n) {
            val bw = raw[2 * n + i]
            val bh = raw[3 * n + i]
            val sz = minOf(bw, bh)
            if (sz < 18f || sz > 90f) continue
            var best = -1f
            var bestC = -1
            for (k in 0 until 15) {
                val s = raw[(4 + k) * n + i]
                if (s > best) {
                    best = s
                    bestC = k
                }
            }
            if (best < 0.5f || bestC < 0) continue
            val cx = raw[0 * n + i]
            val cy = raw[1 * n + i]
            // 640 → 原图
            val mx = (cx - ox) / sc
            val my = (cy - oy) / sc
            val mw = bw / sc
            val mh = bh / sc
            dets.add(Det(bestC, best, mx, my, mw, mh))
        }
        // NMS
        val kept = nms(dets)
        lastDetectSummary = "yolo n=${kept.size} raw=${dets.size}"
        Log.i(TAG, "detectPieces kept=${kept.size} raw=${dets.size}")
        return kept
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
