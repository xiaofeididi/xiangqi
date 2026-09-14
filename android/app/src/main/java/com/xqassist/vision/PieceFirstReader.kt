package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.xqassist.core.Position
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 先找子再归格（对齐 Pro：YOLO 框 → 格点）。
 * 1) 墨迹密度峰 = 棋子中心
 * 2) 同行间距估格距，枚举原点做格点拟合
 * 3) 颜色过滤 + 实机模板认字
 * 4) 宫内位置规则兜底将/帅/士
 */
object PieceFirstReader {

    private data class Peak(val cx: Int, val cy: Int, val n: Int, val red: Int, val black: Int) {
        val isRed: Boolean get() = red >= black
    }

    fun read(frame: Bitmap, board: BoardRect): Pair<Position, String>? {
        val w = frame.width
        val h = frame.height
        val peaks = findPeaks(frame)
        if (peaks.isEmpty()) return null

        val cellW = ((board.right - board.left) / 10).coerceAtLeast(8)
        val cellH = ((board.bottom - board.top) / 11).coerceAtLeast(8)
        val l0 = board.left + cellW
        val t0 = board.top + cellH

        val radius = ((minOf(cellW, cellH)) * 0.38f).toInt().coerceIn(14, 36)
        val templates = TemplateBank.get(frame.contextOrNull())

        val cells = Array(10) { arrayOfNulls<String>(9) }
        var placed = 0
        for (p in peaks) {
            val file = ((p.cx - l0).toFloat() / cellW).roundToInt()
            val rank = ((p.cy - t0).toFloat() / cellH).roundToInt()
            if (file !in 0..8 || rank !in 0..9) continue
            val prefix = if (p.isRed) "w" else "b"
            var best: String? = null
            var bestScore = -1.0
            if (templates != null) {
                for ((code, tmpl) in templates) {
                    if (!code.startsWith(prefix)) continue
                    val s = maskDice(frame, p.cx, p.cy, radius, tmpl)
                    if (s > bestScore) {
                        bestScore = s
                        best = code
                    }
                }
            }
            var code = if (bestScore >= 0.22) best else null
            // 宫内规则兜底
            code = palaceOverride(code, prefix, rank, file) ?: code
            if (code == null) {
                // 至少保留颜色，用最像的同色模板
                code = best
            }
            if (code != null) {
                cells[rank][file] = code
                placed++
            }
        }
        if (placed < 4) return null

        val fen = cellsToFen(cells)
        val summary = "peaks=${peaks.size} placed=$placed cell=${cellW}x${cellH}"
        return try {
            Position.fromFen("$fen w - - 0 1") to summary
        } catch (_: Throwable) {
            null
        }
    }

    private fun Bitmap.contextOrNull(): android.content.Context? = null

    /** 宫内：帅/将只在 file3-5 rank0-2 / 7-9；士在对角线 */
    private fun palaceOverride(typed: String?, prefix: String, rank: Int, file: Int): String? {
        val inPalaceFile = file in 3..5
        if (prefix == "b") {
            val inTop = rank in 0..2
            if (!inTop || !inPalaceFile) {
                // 宫外不能是将/士
                if (typed == "bk" || typed == "ba") return null
                return typed
            }
            // 士位：(0,3)(0,5)(1,4)
            val isShi = (rank == 0 && (file == 3 || file == 5)) || (rank == 1 && file == 4)
            val isJiang = (rank == 0 && file == 4) || (rank == 1 && file == 4)
            if (isShi && typed != "bk") return "ba"
            if (rank == 0 && file == 4) return "bk"
            if (typed == null && isJiang) return if (rank == 0 && file == 4) "bk" else typed
            return typed
        } else {
            val inBot = rank in 7..9
            if (!inBot || !inPalaceFile) {
                if (typed == "wk" || typed == "wa") return null
                return typed
            }
            val isShi = (rank == 9 && (file == 3 || file == 5)) || (rank == 8 && file == 4)
            if (isShi && typed != "wk") return "wa"
            if (rank == 9 && file == 4) return "wk"
            return typed
        }
    }

    private fun findPeaks(frame: Bitmap): List<Peak> {
        val w = frame.width
        val h = frame.height
        val step = 2
        val y0 = h / 5
        val y1 = h - h / 10
        val cell = maxOf(16, minOf(w, y1 - y0) / 32)
        val acc = HashMap<Int, IntArray>() // sx,sy,n,red
        var y = y0
        while (y < y1) {
            var x = 0
            while (x < w) {
                val kind = inkKind(frame.getPixel(x, y))
                if (kind != 0) {
                    val key = (x / cell) * 10000 + (y / cell)
                    val a = acc.getOrPut(key) { IntArray(4) }
                    a[0] += x; a[1] += y; a[2] += 1
                    if (kind == 1) a[3] += 1
                }
                x += step
            }
            y += step
        }
        val raw = ArrayList<Peak>()
        for (a in acc.values) {
            if (a[2] < 8) continue
            raw.add(Peak(a[0] / a[2], a[1] / a[2], a[2], a[3], a[2] - a[3]))
        }
        raw.sortByDescending { it.n }
        val kept = ArrayList<Peak>()
        val minD = cell * 0.6f
        for (p in raw) {
            var ok = true
            for (k in kept) {
                if (hypot(p.cx - k.cx, p.cy - k.cy) < minD) {
                    ok = false
                    break
                }
            }
            if (ok) kept.add(p)
            if (kept.size >= 36) break
        }
        return kept
    }

    private fun hypot(dx: Int, dy: Int): Float {
        val a = dx.toFloat(); val b = dy.toFloat()
        return sqrt(a * a + b * b)
    }

    /** 1=红字 2=黑字 0=非字 */
    private fun inkKind(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (r > 170 && g > 130 && r > g && g >= b - 15 && r - b > 15) return 0
        if (r >= 140 && r >= g + 50 && r >= b + 50 && g <= 160) return 1
        if (maxC < 115 && maxC - minC < 45) return 2
        return 0
    }

    private fun maskDice(frame: Bitmap, x: Int, y: Int, radius: Int, tmpl: Bitmap): Double {
        val n = 24
        val fa = Array(n) { IntArray(n) }
        for (j in 0 until n) {
            for (i in 0 until n) {
                val fx = x + ((i - n / 2 + 0.5) * 2 * radius / n).toInt()
                val fy = y + ((j - n / 2 + 0.5) * 2 * radius / n).toInt()
                if (fx in 0 until frame.width && fy in 0 until frame.height && inkKind(frame.getPixel(fx, fy)) != 0) {
                    fa[j][i] = 1
                }
            }
        }
        val tw = tmpl.width
        val th = tmpl.height
        var inter = 0
        var ua = 0
        var ub = 0
        for (j in 0 until n) {
            for (i in 0 until n) {
                val va = fa[j][i]
                val tx = (tw * 0.18 + (i + 0.5) * tw * 0.64 / n).toInt()
                val ty = (th * 0.18 + (j + 0.5) * th * 0.64 / n).toInt()
                val vb = if (tx in 0 until tw && ty in 0 until th && inkKind(tmpl.getPixel(tx, ty)) != 0) 1 else 0
                inter += va * vb
                ua += va
                ub += vb
            }
        }
        return if (ua + ub == 0) -1.0 else 2.0 * inter / (ua + ub)
    }

    private fun cellsToFen(cells: Array<Array<String?>>): String {
        val sb = StringBuilder()
        for (rank in 0 until 10) {
            var empty = 0
            for (file in 0 until 9) {
                val code = cells[rank][file]
                if (code == null) {
                    empty++
                } else {
                    if (empty > 0) {
                        sb.append(empty)
                        empty = 0
                    }
                    sb.append(if (code.startsWith("w")) code[1].uppercaseChar() else code[1])
                }
            }
            if (empty > 0) sb.append(empty)
            if (rank < 9) sb.append('/')
        }
        return sb.toString()
    }
}

/** 模板缓存，由 Overlay/Connect 注入 assets 加载结果 */
object TemplateBank {
    @Volatile
    var map: Map<String, Bitmap>? = null

    fun get(@Suppress("UNUSED_PARAMETER") ctx: Any?): Map<String, Bitmap>? = map
}
