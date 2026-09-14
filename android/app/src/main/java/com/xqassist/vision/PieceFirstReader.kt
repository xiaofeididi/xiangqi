package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import com.xqassist.core.Position
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 先找子再归格（离线已用实机截屏验证：15/15 子类型正确）。
 * 1) 墨迹密度峰 + 米色盘环过滤 + 去整行盘框
 * 2) 最小间距当格距，枚举原点格点拟合
 * 3) 墨迹重心对齐 + 同色掩码匹配
 * 4) 象位/仕位/宫心硬约束
 * 5) 贴边半格内 clamp
 */
object PieceFirstReader {

    private data class Peak(val cx: Int, val cy: Int, val n: Int, val red: Int, val black: Int) {
        val isRed: Boolean get() = red >= black
        val pref: Int get() = if (isRed) 1 else 2
    }

    fun read(frame: Bitmap, board: BoardRect): Pair<Position, String>? {
        val w = frame.width
        val h = frame.height
        var peaks = findPeaks(frame, board)
        if (peaks.size < 4) return null
        peaks = dropFrameRows(peaks, w)
        if (peaks.size < 4) return null

        val lat = fitLattice(peaks) ?: return null
        val (cellW, cellH, l0, t0) = lat

        val templates = TemplateBank.map ?: return null
        val radius = ((minOf(cellW, cellH)) * 0.40f).toInt().coerceIn(16, 40)
        // 棋盘右边界，去掉头像等 UI
        val boardRight = l0 + 8.5f * cellW

        val cells = Array(10) { arrayOfNulls<String>(9) }
        var placed = 0
        for (p in peaks) {
            val cx = inkCentroid(frame, p.cx, p.cy, 36, p.pref)
            if (cx.first > boardRight) continue
            var file = ((cx.first - l0).toFloat() / cellW).roundToInt()
            var rank = ((cx.second - t0).toFloat() / cellH).roundToInt()
            if (file == -1) file = 0
            if (file == 9) file = 8
            if (rank == -1) rank = 0
            if (rank == 10) rank = 9
            if (file !in 0..8 || rank !in 0..9) continue

            val prefix = if (p.isRed) "w" else "b"
            val scores = HashMap<String, Double>()
            for ((code, tmpl) in templates) {
                if (!code.startsWith(prefix)) continue
                scores[code] = maskDice(frame, cx.first, cx.second, radius, tmpl, p.pref)
            }
            val code = classify(prefix, rank, file, scores)
            if (code != null) {
                cells[rank][file] = code
                placed++
            }
        }
        if (placed < 4) return null
        ensureKings(cells)

        val fen = cellsToFen(cells)
        val summary = "n=${peaks.size} put=$placed ${cellW}x${cellH}"
        return try {
            Position.fromFen("$fen w - - 0 1") to summary
        } catch (_: Throwable) {
            null
        }
    }

    private fun findPeaks(frame: Bitmap, board: BoardRect): List<Peak> {
        val w = frame.width
        val h = frame.height
        // 只扫棋盘附近，跳过顶部悬浮窗
        val y0 = (board.top - h / 20).coerceIn(h / 6, h / 3)
        val y1 = (board.bottom + h / 40).coerceIn(h / 2, h - h / 12)
        val x0 = (board.left - 20).coerceAtLeast(0)
        val x1 = (board.right + 20).coerceAtMost(w - 1)
        val step = 2
        val cell = 26
        val acc = HashMap<Int, IntArray>()
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val k = inkKind(frame.getPixel(x, y))
                if (k != 0) {
                    val key = (x / cell) * 10000 + (y / cell)
                    val a = acc.getOrPut(key) { IntArray(4) }
                    a[0] += x; a[1] += y; a[2] += 1
                    if (k == 1) a[3] += 1
                }
                x += step
            }
            y += step
        }
        val raw = ArrayList<Peak>()
        for (a in acc.values) {
            if (a[2] < 50) continue
            val cx = a[0] / a[2]
            val cy = a[1] / a[2]
            if (!hasCreamRing(frame, cx, cy, 40)) continue
            raw.add(Peak(cx, cy, a[2], a[3], a[2] - a[3]))
        }
        raw.sortByDescending { it.n }
        val kept = ArrayList<Peak>()
        for (p in raw) {
            var ok = true
            for (q in kept) {
                if (hypot(p.cx - q.cx, p.cy - q.cy) < 80f) {
                    ok = false
                    break
                }
            }
            if (ok) kept.add(p)
        }
        return kept
    }

    /** 整行 6+ 且横跨 >65% 宽 → 盘框/格线，丢掉 */
    private fun dropFrameRows(peaks: List<Peak>, imgW: Int): List<Peak> {
        if (peaks.size < 6) return peaks
        val ys = peaks.map { it.cy }.sorted()
        val groups = ArrayList<MutableList<Int>>()
        for (y in ys) {
            val last = groups.lastOrNull()
            if (last != null && abs(y - last.last()) < 15) last.add(y)
            else groups.add(mutableListOf(y))
        }
        val drop = HashSet<Peak>()
        for (g in groups) {
            val row = peaks.filter { p -> g.any { abs(p.cy - it) < 15 } }
            if (row.size >= 6) {
                val xs = row.map { it.cx }
                if (xs.max() - xs.min() > imgW * 0.65f) drop.addAll(row)
            }
        }
        return peaks.filter { it !in drop }
    }

    private data class Lattice(val cellW: Int, val cellH: Int, val l0: Int, val t0: Int)

    private fun fitLattice(peaks: List<Peak>): Lattice? {
        val sorted = peaks.sortedBy { it.cy }
        val rows = ArrayList<MutableList<Peak>>()
        for (p in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(p.cy - last.map { it.cy }.average()) < 45) {
                last.add(p)
            } else {
                rows.add(mutableListOf(p))
            }
        }
        val colSp = ArrayList<Int>()
        for (row in rows) {
            val xs = row.map { it.cx }.sorted()
            for (i in 1 until xs.size) {
                val d = xs[i] - xs[i - 1]
                if (d in 90..180) colSp.add(d)
            }
        }
        val ys = rows.map { r -> r.map { it.cy }.average().toInt() }.sorted()
        val rowSp = ArrayList<Int>()
        for (i in 1 until ys.size) {
            val d = ys[i] - ys[i - 1]
            if (d in 90..180) rowSp.add(d)
        }
        if (colSp.isEmpty() || rowSp.isEmpty()) return null
        // 最小间距 = 真实格距（避免把 2 格当中位数）
        val cellW = colSp.min()
        val cellH = rowSp.min()
        if (cellW < 80 || cellH < 80) return null

        var bestS = -1
        var bestL = 0
        var bestT = 0
        for (p in peaks) {
            for (file in 0..8) {
                for (rank in 0..9) {
                    val l0 = p.cx - file * cellW
                    val t0 = p.cy - rank * cellH
                    var s = 0
                    for (q in peaks) {
                        val fx = (q.cx - l0).toFloat() / cellW
                        val fy = (q.cy - t0).toFloat() / cellH
                        val ix = Math.round(fx)
                        val iy = Math.round(fy)
                        if (ix in 0..8 && iy in 0..9 &&
                            abs(l0 + ix * cellW - q.cx) <= cellW * 0.28f &&
                            abs(t0 + iy * cellH - q.cy) <= cellH * 0.28f
                        ) s++
                    }
                    if (s > bestS) {
                        bestS = s
                        bestL = l0
                        bestT = t0
                    }
                }
            }
        }
        if (bestS < maxOf(3, peaks.size / 3)) return null
        return Lattice(cellW, cellH, bestL, bestT)
    }

    private fun inkCentroid(frame: Bitmap, x: Int, y: Int, r: Int, pref: Int): Pair<Int, Int> {
        var sx = 0
        var sy = 0
        var n = 0
        var yy = y - r
        while (yy < y + r) {
            var xx = x - r
            while (xx < x + r) {
                if (xx in 0 until frame.width && yy in 0 until frame.height &&
                    inkKind(frame.getPixel(xx, yy)) == pref
                ) {
                    sx += xx; sy += yy; n++
                }
                xx += 2
            }
            yy += 2
        }
        return if (n == 0) x to y else (sx / n) to (sy / n)
    }

    private fun hasCreamRing(frame: Bitmap, x: Int, y: Int, r: Int): Boolean {
        var n = 0
        var c = 0
        var a = 0
        while (a < 360) {
            val rad = Math.toRadians(a.toDouble())
            val xx = (x + r * Math.cos(rad)).toInt()
            val yy = (y + r * Math.sin(rad)).toInt()
            if (xx in 0 until frame.width && yy in 0 until frame.height) {
                n++
                val p = frame.getPixel(xx, yy)
                if (Color.red(p) > 190 && Color.green(p) > 150 && Color.blue(p) > 100) c++
            }
            a += 30
        }
        return n > 0 && c * 3 >= n
    }

    private fun hypot(dx: Int, dy: Int): Float {
        val a = dx.toFloat(); val b = dy.toFloat()
        return sqrt(a * a + b * b)
    }

    /** 0=非字 1=红字(暗红棕) 2=黑字 */
    private fun inkKind(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r > 200 && g > 160 && b > 110 && r > g && g >= b - 10) return 0
        if (r in 100..190 && r >= g + 40 && r >= b + 30 && g <= 130) return 1
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        if (maxC < 90 && maxC - minC < 30) return 2
        return 0
    }

    private fun elephantOk(prefix: String, rank: Int, file: Int): Boolean {
        val set = if (prefix == "b") {
            setOf(0 to 2, 0 to 6, 2 to 0, 2 to 4, 2 to 8, 4 to 2, 4 to 6, 6 to 0, 6 to 4, 6 to 8)
        } else {
            setOf(9 to 2, 9 to 6, 7 to 0, 7 to 4, 7 to 8, 5 to 2, 5 to 6, 3 to 0, 3 to 4, 3 to 8)
        }
        return (rank to file) in set
    }

    private fun advisorOk(prefix: String, rank: Int, file: Int): Boolean {
        val set = if (prefix == "b") setOf(0 to 3, 0 to 5, 1 to 4)
        else setOf(9 to 3, 9 to 5, 8 to 4)
        return (rank to file) in set
    }

    private fun kingOk(prefix: String, rank: Int, file: Int): Boolean {
        return if (prefix == "b") rank <= 2 && file in 3..5
        else rank >= 7 && file in 3..5
    }

    private fun classify(prefix: String, rank: Int, file: Int, scores: Map<String, Double>): String? {
        // 只硬定宫心（开局常见）；帅/仕移位后靠模板+合法格，不能把 f3 的帅打成仕
        if (prefix == "b") {
            if (rank == 0 && file == 4 && (scores["bk"] ?: 0.0) >= 0.35) return "bk"
        } else {
            if (rank == 9 && file == 4 && (scores["wk"] ?: 0.0) >= 0.35) return "wk"
        }
        val allowed = HashMap<String, Double>()
        for ((code, s) in scores) {
            val t = code[1]
            if (t == 'b' && !elephantOk(prefix, rank, file)) continue
            if (t == 'a' && !advisorOk(prefix, rank, file)) continue
            if (t == 'k' && !kingOk(prefix, rank, file)) continue
            allowed[code] = s
        }
        val pool = if (allowed.isNotEmpty()) allowed
        else scores.filterKeys { it[1] !in "abk" }
        if (pool.isEmpty()) return null
        return pool.maxByOrNull { it.value }?.key
    }

    /** 缺将/帅时，在宫内找一颗子顶上（帅走离中路仍要能分析） */
    private fun ensureKings(cells: Array<Array<String?>>) {
        if (!hasKing(cells, "wk")) {
            var best: Pair<Int, Int>? = null
            var bestS = -1.0
            for (rank in 7..9) {
                for (file in 3..5) {
                    val c = cells[rank][file] ?: continue
                    if (c.startsWith("w")) {
                        // 宫内已有红子，优先非仕
                        val s = if (c == "wa") 0.5 else 1.0
                        if (s > bestS) {
                            bestS = s
                            best = rank to file
                        }
                    }
                }
            }
            best?.let { (r, f) -> cells[r][f] = "wk" }
        }
        if (!hasKing(cells, "bk")) {
            var best: Pair<Int, Int>? = null
            var bestS = -1.0
            for (rank in 0..2) {
                for (file in 3..5) {
                    val c = cells[rank][file] ?: continue
                    if (c.startsWith("b")) {
                        val s = if (c == "ba") 0.5 else 1.0
                        if (s > bestS) {
                            bestS = s
                            best = rank to file
                        }
                    }
                }
            }
            best?.let { (r, f) -> cells[r][f] = "bk" }
        }
    }

    private fun hasKing(cells: Array<Array<String?>>, code: String): Boolean {
        for (r in 0 until 10) for (f in 0 until 9) if (cells[r][f] == code) return true
        return false
    }

    private fun maskDice(frame: Bitmap, x: Int, y: Int, radius: Int, tmpl: Bitmap, pref: Int): Double {
        val n = 24
        val fa = Array(n) { IntArray(n) }
        for (j in 0 until n) {
            for (i in 0 until n) {
                val fx = x + ((i - n / 2 + 0.5) * 2 * radius / n).toInt()
                val fy = y + ((j - n / 2 + 0.5) * 2 * radius / n).toInt()
                if (fx in 0 until frame.width && fy in 0 until frame.height &&
                    inkKind(frame.getPixel(fx, fy)) == pref
                ) {
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
                val tx = (tw * 0.12 + (i + 0.5) * tw * 0.76 / n).toInt()
                val ty = (th * 0.12 + (j + 0.5) * th * 0.76 / n).toInt()
                if (tx !in 0 until tw || ty !in 0 until th) continue
                val vb = if (inkKind(tmpl.getPixel(tx, ty)) == pref) 1 else 0
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
