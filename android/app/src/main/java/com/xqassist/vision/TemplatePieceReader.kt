package com.xqassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.xqassist.core.Position

/**
 * First-pass piece reader: at each grid intersection, crop a small patch and
 * match colors against the bundled piece templates (assets/pieces matching the A-license webp templates).
 * Plan: upgrade to OpenCV template matching / TFLite when accuracy requires.
 */
class TemplatePieceReader(
    context: Context,
    private val mode: Int = MODE_BASIC,
) : ChessboardReader {

    private val templates: Map<String, Bitmap> = loadTemplates(context)
    private val matchThreshold = if (mode == MODE_WIDE) 0.48 else 0.55
    private val sampleRadius = if (mode == MODE_WIDE) 8 else 6
    private val sampleStep = if (mode == MODE_WIDE) 2 else 1

    override fun readBoard(frame: Bitmap, board: BoardRect): Position {
        val cells = MutableList(10) { arrayOfNulls<String>(9) }
        for (rank in 0 until 10) {
            for (file in 0 until 9) {
                val (x, y) = GridGeometry.intersection(board, rank, file)
                cells[rank][file] = matchPiece(frame, x, y)
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

    private fun pieceCode(code: String): Char =
        if (code.startsWith("w")) code[1].uppercaseChar() else code[1]

    private fun matchPiece(frame: Bitmap, px: Int, py: Int): String? {
        var best: String? = null
        var bestScore = -1.0
        for ((code, tmpl) in templates) {
            val score = correlate(frame, px, py, tmpl)
            if (score > bestScore) { bestScore = score; best = code }
        }
        return if (bestScore > matchThreshold) best else null
    }

    private fun correlate(frame: Bitmap, px: Int, py: Int, tmpl: Bitmap): Double {
        val size = sampleRadius
        var sum = 0.0; var n = 0
        val step = sampleStep
        for (dy in -size..size step step) {
            for (dx in -size..size step step) {
                val fx = px + dx; val fy = py + dy
                if (fx !in 0 until frame.width || fy !in 0 until frame.height) continue
                val tx = tmpl.width / 2 + dx
                val ty = tmpl.height / 2 + dy
                if (tx !in 0 until tmpl.width || ty !in 0 until tmpl.height) continue
                val fc = frame.getPixel(fx, fy); val tc = tmpl.getPixel(tx, ty)
                sum += colorSimilarity(fc, tc); n++
            }
        }
        return if (n == 0) -1.0 else sum / n
    }

    private fun colorSimilarity(a: Int, b: Int): Double {
        val r = ((a shr 16) and 0xff) - ((b shr 16) and 0xff)
        val g = ((a shr 8) and 0xff) - ((b shr 8) and 0xff)
        val bl = (a and 0xff) - (b and 0xff)
        return Math.sqrt((1.0) / (1.0 + (r * r + g * g + bl * bl) / 255.0))
    }

    private fun loadTemplates(ctx: Context): Map<String, Bitmap> {
        val codes = listOf("wa","wb","wc","wk","wn","wp","wr","ba","bb","bc","bk","bn","bp","br")
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
