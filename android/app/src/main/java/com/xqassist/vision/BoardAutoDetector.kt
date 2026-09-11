package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 自动检测棋盘外框（对齐 Pro：先拿到棋子分布，再推出外框）。
 * Pro 用 YOLO class14 找盘 + 外扩 1 格；我们用红/黑色块聚类代替 YOLO。
 */
object BoardAutoDetector {

    data class PieceBlob(val cx: Int, val cy: Int, val pixels: Int)

    fun detect(frame: Bitmap): BoardRect? {
        val w = frame.width
        val h = frame.height
        if (w < 50 || h < 50) return null

        val step = maxOf(2, minOf(w, h) / 280)
        val blobs = findPieceBlobs(frame, step)
        if (blobs.size < 8) {
            // 棋子太少时退回木色外框
            return detectByWood(frame, step)
        }

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (b in blobs) {
            if (b.cx < minX) minX = b.cx
            if (b.cy < minY) minY = b.cy
            if (b.cx > maxX) maxX = b.cx
            if (b.cy > maxY) maxY = b.cy
        }

        val cell = estimateCell(blobs) ?: ((maxX - minX).coerceAtLeast(1) / 8f)
        // 棋子中心包围盒 ≈ 首末交叉点；外扩 1 格得到外框（Pro 同款）
        val rect = BoardRect(
            left = (minX - cell).toInt().coerceAtLeast(0),
            top = (minY - cell).toInt().coerceAtLeast(0),
            right = (maxX + cell).toInt().coerceAtMost(w - 1),
            bottom = (maxY + cell).toInt().coerceAtMost(h - 1),
        )
        if (rect.right - rect.left < 80 || rect.bottom - rect.top < 80) {
            return detectByWood(frame, step)
        }
        return rect
    }

    /** 红/黑棋子像素聚类 */
    private fun findPieceBlobs(frame: Bitmap, step: Int): List<PieceBlob> {
        val w = frame.width
        val h = frame.height
        val pts = ArrayList<IntArray>(400)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val kind = pieceKind(frame.getPixel(x, y))
                if (kind >= 0) pts.add(intArrayOf(x, y, kind))
                x += step
            }
            y += step
        }
        if (pts.size < 20) return emptyList()

        // 简单网格哈希聚类：邻近同色点归为一簇
        val cell = maxOf(step * 2, minOf(w, h) / 40)
        val buckets = HashMap<Long, ArrayList<IntArray>>()
        fun key(x: Int, y: Int): Long = ((x / cell).toLong() shl 32) or (y / cell).toLong()
        for (p in pts) {
            buckets.getOrPut(key(p[0], p[1])) { ArrayList() }.add(p)
        }
        val used = HashSet<Long>()
        val blobs = ArrayList<PieceBlob>()
        for ((k, list) in buckets) {
            if (k in used) continue
            val queue = ArrayDeque<Long>()
            queue.add(k)
            used.add(k)
            var sx = 0L
            var sy = 0L
            var n = 0
            while (queue.isNotEmpty()) {
                val ck = queue.removeFirst()
                val cluster = buckets[ck] ?: continue
                for (p in cluster) {
                    sx += p[0]; sy += p[1]; n++
                }
                val bx = (ck shr 32).toInt()
                val by = (ck and 0xffffffffL).toInt()
                for (dx in -1..1) for (dy in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nk = ((bx + dx).toLong() shl 32) or (by + dy).toLong()
                    if (nk in buckets && nk !in used) {
                        used.add(nk)
                        queue.add(nk)
                    }
                }
            }
            if (n >= 4) {
                blobs.add(PieceBlob((sx / n).toInt(), (sy / n).toInt(), n))
            }
        }
        return blobs
    }

    /** 相邻棋子中心距的中位数 ≈ 格距 */
    private fun estimateCell(blobs: List<PieceBlob>): Float? {
        if (blobs.size < 4) return null
        val dists = ArrayList<Float>(blobs.size * 4)
        for (i in blobs.indices) {
            var best = Float.MAX_VALUE
            for (j in blobs.indices) {
                if (i == j) continue
                val dx = (blobs[i].cx - blobs[j].cx).toFloat()
                val dy = (blobs[i].cy - blobs[j].cy).toFloat()
                val d = sqrt(dx * dx + dy * dy)
                if (d in 8f..400f && d < best) best = d
            }
            if (best < Float.MAX_VALUE) dists.add(best)
        }
        if (dists.isEmpty()) return null
        dists.sort()
        return dists[dists.size / 2]
    }

    private fun detectByWood(frame: Bitmap, step: Int): BoardRect? {
        val w = frame.width
        val h = frame.height
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
        val marginX = (maxX - minX) / 50
        val marginY = (maxY - minY) / 50
        val rect = BoardRect(
            left = (minX + marginX).coerceAtLeast(0),
            top = (minY + marginY).coerceAtLeast(0),
            right = (maxX - marginX).coerceAtMost(w - 1),
            bottom = (maxY - marginY).coerceAtMost(h - 1),
        )
        if (rect.right - rect.left < 80 || rect.bottom - rect.top < 80) return null
        return rect
    }

    /** Pro w2.j.c：1=红 0=黑 -1=其它 */
    private fun pieceKind(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r > 120 && r > g * 1.5f && r > b * 1.5f && r - g > 60 && r - b > 60) return 1
        val lum = (r * 299 + g * 587 + b * 114) / 1000f
        if (lum < 60f && abs(r - g) < 30 && abs(r - b) < 30) return 0
        return -1
    }

    private fun isBoardColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r < 120 || r > 240) return false
        if (g < 90 || g > 210) return false
        if (b < 60 || b > 180) return false
        if (r - b < 30) return false
        if (r - g < 10) return false
        return true
    }
}
