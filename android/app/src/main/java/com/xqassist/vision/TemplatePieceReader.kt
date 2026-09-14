package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.xqassist.core.Position
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 格点识子。
 * Pro：YOLO 直接给 classId。
 * 我们：在交叉点附近与全部模板做相关匹配取最高分。
 * 不做严格红/黑预过滤——黑子字色常是灰（RGB≈130），Pro 的 lum<60 会全漏。
 */
class TemplatePieceReader(
    context: Context,
    private val mode: Int = MODE_BASIC,
) : ChessboardReader {

    private val templates: Map<String, Bitmap> = loadTemplates(context)
    private val matchThreshold = if (mode == MODE_WIDE) 0.38 else 0.42

    override fun readBoard(frame: Bitmap, board: BoardRect): Position {
        val cells = MutableList(10) { arrayOfNulls<String>(9) }
        val cellW = ((board.right - board.left) / 10).coerceAtLeast(8)
        val cellH = ((board.bottom - board.top) / 11).coerceAtLeast(8)
        // 采样半径 ≈ 半颗子，覆盖字心与盘沿
        val radius = ((minOf(cellW, cellH)) * 0.42f).toInt().coerceIn(7, 30)
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
        // 木色空点：中心明显是暖木则直接空
        if (looksLikeWood(frame, px, py, radius / 2)) return null

        var best: String? = null
        var bestScore = -1.0
        for ((code, tmpl) in templates) {
            val score = correlate(frame, px, py, tmpl, radius)
            if (score > bestScore) {
                bestScore = score
                best = code
            }
        }
        return if (bestScore >= matchThreshold) best else null
    }

    private fun looksLikeWood(frame: Bitmap, px: Int, py: Int, radius: Int): Boolean {
        var wood = 0
        var n = 0
        val r = radius.coerceAtLeast(3)
        for (dy in -r..r step 2) {
            for (dx in -r..r step 2) {
                val x = px + dx
                val y = py + dy
                if (x !in 0 until frame.width || y !in 0 until frame.height) continue
                val c = frame.getPixel(x, y)
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                // 米色盘/木板：G 高
                if (rr > 170 && gg > 130 && rr > gg && gg >= bb - 15) wood++
                n++
            }
        }
        return n > 0 && wood * 100 / n >= 82
    }

    /**
     * 将画面中以 (px,py) 为中心、半径 radius 的区域，
     * 与 150×150 模板的中心同尺度区域做平均色相似度。
     */
    private fun correlate(frame: Bitmap, px: Int, py: Int, tmpl: Bitmap, radius: Int): Double {
        var sum = 0.0
        var n = 0
        val step = if (radius >= 16) 2 else 1
        val scale = tmpl.width / 2f / radius // 模板像素 / 画面像素
        for (dy in -radius..radius step step) {
            for (dx in -radius..radius step step) {
                val fx = px + dx
                val fy = py + dy
                if (fx !in 0 until frame.width || fy !in 0 until frame.height) continue
                val tx = (tmpl.width / 2 + dx * scale).toInt()
                val ty = (tmpl.height / 2 + dy * scale).toInt()
                if (tx !in 0 until tmpl.width || ty !in 0 until tmpl.height) continue
                val fc = frame.getPixel(fx, fy)
                val tc = tmpl.getPixel(tx, ty)
                // 忽略模板透明像素
                if (Color.alpha(tc) < 32) continue
                sum += colorSimilarity(fc, tc)
                n++
            }
        }
        return if (n < 12) -1.0 else sum / n
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
        // 优先实机截屏抽的模板（天天象棋皮肤）；缺的回退旧 webp
        for (code in codes) {
            val live = openAsset(ctx, "pieces_live/$code.webp")
                ?: openAsset(ctx, "pieces/$code.webp")
                ?: continue
            map[code] = live
        }
        return map
    }

    private fun openAsset(ctx: Context, path: String): Bitmap? = try {
        ctx.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val MODE_BASIC = 0
        const val MODE_WIDE = 1
    }
}
