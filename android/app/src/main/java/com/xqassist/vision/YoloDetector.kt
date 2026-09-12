package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.xqassist.core.Position
import vip.wqby.pro.ncnn.OutResult
import vip.wqby.pro.ncnn.yolov13
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pro YOLO 封装（NCNN yolov13）。
 * classId：0-6 红 RNBAKCP，7-13 黑 rnbakcp，14 棋盘。
 */
object YoloDetector {

    private const val TAG = "Yolo"
    private const val BOARD_CLASS = 14

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var lastError: String = ""
        private set

    private var net: yolov13? = null

    fun init(context: Context): Boolean {
        if (isReady) return true
        return try {
            val y = yolov13()
            val ok = y.initModel(false, context.applicationContext.assets)
            if (ok) {
                net = y
                isReady = true
                lastError = ""
                Log.i(TAG, "yolov13 init ok")
            } else {
                lastError = "initModel=false"
                Log.w(TAG, "initModel returned false")
            }
            ok
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "yolov13 init failed", t)
            false
        }
    }

    fun detect(bitmap: Bitmap): Array<OutResult>? {
        val n = net ?: return null
        return try {
            n.detect(bitmap)
        } catch (t: Throwable) {
            lastError = t.message ?: "detect failed"
            Log.e(TAG, "detect failed", t)
            null
        }
    }

    /** Pro j.b：取 classId==14 的棋盘框 */
    fun boardRect(results: Array<OutResult>, minConf: Float = 0.7f): BoardRect? {
        for (i in results.indices.reversed()) {
            val r = results[i]
            if (r.classId == BOARD_CLASS && r.confidence >= minConf) {
                return BoardRect(r.x1, r.y1, r.x2, r.y2)
            }
        }
        return null
    }

    /**
     * Pro j.b 之后外扩：b 是棋子活动区（8×9 格），外扩 1 格得外框。
     * class14 若已是外框，外扩后仍可用（/10、/11 会略松，映射仍能落到正确格）。
     */
    fun expandToOuter(inner: BoardRect): BoardRect {
        val w = (inner.right - inner.left).coerceAtLeast(1)
        val h = (inner.bottom - inner.top).coerceAtLeast(1)
        val cellW = (w / 8f).coerceAtLeast(1f)
        val cellH = (h / 9f).coerceAtLeast(1f)
        return BoardRect(
            left = (inner.left - cellW).toInt(),
            top = (inner.top - cellH).toInt(),
            right = (inner.right + cellW).toInt(),
            bottom = (inner.bottom + cellH).toInt(),
        )
    }

    /** 棋子中心 → 格点 (rank 0..9, file 0..8)，与 Pro j.e / 本地 GridGeometry 一致 */
    fun cellOf(outer: BoardRect, cx: Int, cy: Int): Pair<Int, Int>? {
        val w = (outer.right - outer.left).coerceAtLeast(1)
        val h = (outer.bottom - outer.top).coerceAtLeast(1)
        val cellW = w / 10f
        val cellH = h / 11f
        if (cellW < 4f || cellH < 4f) return null
        val centerX = (outer.left + outer.right) / 2f
        val file = (4 - (centerX - cx) / cellW).roundToInt()
        val first = outer.top + cellH
        val last = outer.bottom - cellH
        val rank = if (abs(first - cy) < abs(last - cy)) {
            (abs(cy - first) / cellH).roundToInt()
        } else {
            9 - (abs(cy - last) / cellH).roundToInt()
        }
        if (rank !in 0..9 || file !in 0..8) return null
        return rank to file
    }

    /** 把棋子检测落到 10×9 classId 网格；冲突时保留置信度更高者 */
    fun toGrid(results: Array<OutResult>, outer: BoardRect): Array<IntArray> {
        val grid = Array(10) { IntArray(9) { -1 } }
        val conf = Array(10) { FloatArray(9) { -1f } }
        for (r in results) {
            if (r.classId == BOARD_CLASS) continue
            if (r.classId !in 0..13) continue
            val (rank, file) = cellOf(outer, r.centerX(), r.centerY()) ?: continue
            if (r.confidence >= conf[rank][file]) {
                grid[rank][file] = r.classId
                conf[rank][file] = r.confidence
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
                if (id < 0) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(FEN_CHARS[id])
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank < 9) sb.append('/')
        }
        return sb.toString()
    }

    /** Pro m.m：整盘 180° */
    fun rotate180(grid: Array<IntArray>): Array<IntArray> {
        val out = Array(10) { IntArray(9) }
        for (r in 0 until 10) {
            for (f in 0 until 9) {
                out[r][f] = grid[9 - r][8 - f]
            }
        }
        return out
    }

    /** 红帅 classId=4 在上半区 → 黑方视角，翻转 */
    fun orient(grid: Array<IntArray>): Array<IntArray> {
        var redKingRank = -1
        for (r in 0 until 10) {
            for (f in 0 until 9) {
                if (grid[r][f] == 4) redKingRank = r
            }
        }
        return if (redKingRank in 0..4) rotate180(grid) else grid
    }

    @Volatile
    var lastDetectSummary: String = ""
        private set

    /** 在多个候选外框里选识别到棋子/将帅最多的那个 */
    fun bestBoardAndGrid(results: Array<OutResult>, candidates: List<BoardRect>): Pair<BoardRect, Array<IntArray>>? {
        var bestRect: BoardRect? = null
        var bestGrid: Array<IntArray>? = null
        var bestScore = -1
        for (rect in candidates) {
            val grid = toGrid(results, rect)
            val pieces = countPieces(grid)
            val kings = (if (hasKing(grid, true)) 3 else 0) + (if (hasKing(grid, false)) 3 else 0)
            val score = pieces * 2 + kings
            if (score > bestScore) {
                bestScore = score
                bestRect = rect
                bestGrid = grid
            }
        }
        if (bestRect == null || bestGrid == null) return null
        lastDetectSummary = "boxes=${results.size} score=$bestScore pieces=${countPieces(bestGrid)}"
        return bestRect to bestGrid
    }

    fun gridToPosition(grid: Array<IntArray>): Position {
        val fen = gridToFen(grid) + " w - - 0 1"
        return Position.fromFen(fen)
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
