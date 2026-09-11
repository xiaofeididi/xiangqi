package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.xqassist.core.Position
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 格点识子：先用 Pro 的红/黑像素规则判断「有没有子、什么颜色」，
 * 再在对应颜色的模板里做缩放相关匹配。
 * 采样半径随格距自适应，避免大分辨率下只打到木纹。
 */
class TemplatePieceReader(
    context: Context,
    private val mode: Int = MODE_BASIC,
) : ChessboardReader {

    private val templates: Map<String, Bitmap> = loadTemplates(context)
    private val matchThreshold = if (mode == MODE_WIDE) 0.42 else 0.48

    override fun readBoard(frame: Bitmap, board: BoardRect): Position {
        val cells = MutableList(10) { arrayOfNulls<String>(9) }
        val cellW = ((board.right - board.left) / 10).coerceAtLeast(8)
        val cellH = ((board.bottom - board.top) / 11).coerceAtLeast(8)
        val radius = ((cellW + cellH) / 6).coerceIn(6, 28)
        for (rank in 0 until 10) {
            for (file in 0 until 9) {
                val (x, y) = GridGeometry.intersection(board, rank, file)
                cells[rank][file] = matchAt(frame, x, y, radius)
            }
        }
        val token = cells.joinToString("/") { row ->
            val r = StringBuilder()
            var empty = 0
            for (cell in row) {
                if (cell == null) { empty++; continue }
                if (empty > 0) { r.append(empty); empty = 0 }
                r.append(pieceCode(cell))
            }
            if (empty > 0) r.append(empty)
            r.toString()
        }
        return Position.fromFen("$token w - - 0 1")
    }

    private fun matchAt(frame: Bitmap, px: Int, py: Int, radius: Int): String? {
        val kind = samplePieceKind(frame, px, py, radius)
        if (kind == 0) return null
        val colorPrefix = if (kind == 1) "w" else "b"
        var best: String? = null
        var bestScore = -1.0
        for ((code, tmpl) in templates) {
            if (!code.startsWith(colorPrefix)) continue
            val score = correlate(frame, px, py, tmpl, radius)
            if (score > bestScore) {
                bestScore = score
                best = code
            }
        }
        return if (bestScore >= matchThreshold) best else null
    }

    /** Pro w2.j.c 环采样：多数红 → 1，多数黑 → 0，否则无子 */
    private fun samplePieceKind(frame: Bitmap, px: Int, py: Int, radius: Int): Int {
        var red = 0
        var black = 0
        val r1 = (radius * 0.55f).toInt().coerceAtLeast(3)
        val r2 = (radius * 0.95f).toInt().coerceAtLeast(r1 + 2)
        for (r in r1..r2 step maxOf(1, (r2 - r1) / 3)) {
            for (a in 0 until 16) {
                val ang = a * Math.PI / 8.0
                val x = px + (r * kotlin.math.cos(ang)).toInt()
                val y = py + (r * kotlin.math.sin(ang)).toInt()
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                when (pieceKind(frame.getPixel(x, y))) {
                    1 -> red++
                    0 -> black++
                }
            }
        }
        val total = red + black
        if (total < 4) return 0
        return if (red > black) 1 else 0
    }

    private fun pieceKind(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        if (r > 120 && r > g * 1.5f && r > b * 1.5f && r - g > 60 && r - b > 60) return 1
        val lum = (r * 299 + g * 587 + b * 114) / 1000f
        if (lum < 60f && abs(r - g) < 30 && abs(r - b) < 30) return 0
        return -1
    }

    private fun correlate(frame: Bitmap, px: Int, py: Int, tmpl: Bitmap, radius: Int): Double {
        var sum = 0.0
        var n = 0
        val step = if (radius >= 14) 2 else 1
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                val fx = px + dx
                val fy = py + dy
                if (fx !in 0 until frame.width || fy !in 0 until frame.height) continue
                val tx = tmpl.width / 2 + dx * tmpl.width / (radius * 2 + 1)
                val ty = tmpl.height / 2 + dy * tmpl.height / (radius * 2 + 1)
                if (tx !in 0 until tmpl.width || ty !in 0 until tmpl.height) continue
                val fc = frame.getPixel(fx, fy)
                val tc = tmpl.getPixel(tx, ty)
                sum += colorSimilarity(fc, tc)
                n++
            }
        }
        return if (n == 0) -1.0 else sum / n
    }

    private fun colorSimilarity(a: Int, b: Int): Double {
        val r = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val g = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val bl = (a and 0xff) - (b and 0xff)
        return sqrt(1.0 / (1.0 + (r * r + g * g + bl * bl) / 255.0))
    }

    private fun pieceCode(code: String): Char =
        if (code.startsWith("w")) code[1].uppercaseChar() else code[1]

    private fun loadTemplates(ctx: Context): Map<String, Bitmap> {
        val codes = listOf("wa", "wb", "wc", "wk", "wn", "wp", "wr", "ba", "bb", "bc", "bk", "bn", "bp", "br")
        val map = mutableMapOf<String, Bitmap>()
        for (code in codes) {
            ctx.assets.open("pieces/$code.webp").use { input ->
                map[code] = BitmapFactory.decodeStream(input)
            }
        }
        return map
    }

    companion object {
        const val MODE_BASIC = 0
        const val MODE_WIDE = 1
    }
}
