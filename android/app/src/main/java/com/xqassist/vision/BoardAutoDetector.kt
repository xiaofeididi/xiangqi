package com.xqassist.vision

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 自动检测棋盘外框。
 * Pro：YOLO 棋子中心包围盒外扩 1 格。
 * 我们：在木盘区域内找「非木色」棋子像素聚类，再同样外扩。
 * 注意：黑子字色可能是深灰而非纯黑，不能用 lum<60 卡死。
 */
object BoardAutoDetector {

    data class PieceBlob(val cx: Int, val cy: Int, val pixels: Int)

    fun detect(frame: Bitmap): BoardRect? {
        val w = frame.width
        val h = frame.height
        if (w < 50 || h < 50) return null

        val step = maxOf(2, minOf(w, h) / 280)
        val wood = detectByWood(frame, step)
        // 先限制在木盘附近，避免把 UI 红色按钮当成棋子
        val search = wood ?: BoardRect(0, 0, w - 1, h - 1)
        val padX = ((search.right - search.left) / 12).coerceAtLeast(8)
        val padY = ((search.bottom - search.top) / 12).coerceAtLeast(8)
        val region = BoardRect(
            left = (search.left - padX).coerceAtLeast(0),
            top = (search.top - padY).coerceAtLeast(0),
            right = (search.right + padX).coerceAtMost(w - 1),
            bottom = (search.bottom + padY).coerceAtMost(h - 1),
        )

        val blobs = findPieceBlobs(frame, step, region)
        if (blobs.size < 6) return wood

        // 去掉离主簇太远的离群点（如半截在盘外的子）
        val cxList = blobs.map { it.cx }.sorted()
        val cyList = blobs.map { it.cy }.sorted()
        val medX = cxList[cxList.size / 2]
        val medY = cyList[cyList.size / 2]
        val filtered = blobs.filter {
            val dx = abs(it.cx - medX)
            val dy = abs(it.cy - medY)
            dx <= medX / 2 + 200 && dy <= medY / 2 + 300
        }
        val use = if (filtered.size >= 6) filtered else blobs

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (b in use) {
            if (b.cx < minX) minX = b.cx
            if (b.cy < minY) minY = b.cy
            if (b.cx > maxX) maxX = b.cx
            if (b.cy > maxY) maxY = b.cy
        }
        val cell = estimateCell(use) ?: ((maxX - minX).coerceAtLeast(1) / 8f)
        val byPieces = BoardRect(
            left = (minX - cell).toInt().coerceAtLeast(0),
            top = (minY - cell).toInt().coerceAtLeast(0),
            right = (maxX + cell).toInt().coerceAtMost(w - 1),
            bottom = (maxY + cell).toInt().coerceAtMost(h - 1),
        )
        if (byPieces.right - byPieces.left < 80 || byPieces.bottom - byPieces.top < 80) {
            return wood
        }
        return byPieces
    }

    private fun findPieceBlobs(frame: Bitmap, step: Int, region: BoardRect): List<PieceBlob> {
        val pts = ArrayList<IntArray>(400)
        var y = region.top
        while (y <= region.bottom) {
            var x = region.left
            while (x <= region.right) {
                if (isPiecePixel(frame.getPixel(x, y))) {
                    pts.add(intArrayOf(x, y))
                }
                x += step
            }
            y += step
        }
        if (pts.size < 20) return emptyList()

        val cell = maxOf(step * 2, minOf(region.right - region.left, region.bottom - region.top) / 40)
        val buckets = HashMap<Long, ArrayList<IntArray>>()
        fun key(x: Int, y: Int): Long = ((x / cell).toLong() shl 32) or (y / cell).toLong()
        for (p in pts) {
            buckets.getOrPut(key(p[0], p[1])) { ArrayList() }.add(p)
        }
        val used = HashSet<Long>()
        val blobs = ArrayList<PieceBlob>()
        for (k0 in buckets.keys.toList()) {
            if (k0 in used) continue
            val queue = ArrayDeque<Long>()
            queue.add(k0)
            used.add(k0)
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
            if (n >= 3) blobs.add(PieceBlob((sx / n).toInt(), (sy / n).toInt(), n))
        }
        return blobs
    }

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
                if (d in 10f..400f && d < best) best = d
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

    /**
     * 天天象棋实机：红字约 (180-220, 40-90, 40-70)；黑字约 (55-70,55-70,55-70)；
     * 棋盘/桌板木色 (200-240, 150-190, 100-140) 不能当成棋子。
     */
    fun isPiecePixel(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        val maxC = maxOf(r, g, b)
        val minC = minOf(r, g, b)
        // 暖色木纹/米色盘：G 很高
        if (r > 170 && g > 130 && r > g && g >= b - 15 && r - b > 15) return false
        // 红字：G 明显低于米色木
        if (r >= 140 && r >= g + 60 && r >= b + 60 && g <= 150) return true
        // 黑字
        if (maxC < 100 && maxC - minC < 35) return true
        // 更暗也算
        if (maxC < 70) return true
        return false
    }

    private fun isBoardColor(pixel: Int): Boolean {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        // 天天象棋浅色棋盘：偏亮米色，不是深棕桌板
        if (r < 180 || r > 250) return false
        if (g < 140 || g > 220) return false
        if (b < 90 || b > 180) return false
        if (r - b < 25) return false
        if (r - g < 8) return false
        return true
    }
}
