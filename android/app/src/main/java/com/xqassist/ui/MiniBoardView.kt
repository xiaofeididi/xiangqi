package com.xqassist.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.xqassist.core.Position
import com.xqassist.core.Quad

/** 悬浮窗迷你棋盘：只读展示识别局面与最优着法箭头 */
class MiniBoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var position: Position? = null
        set(value) {
            field = value
            invalidate()
        }
    var hintMove: Quad? = null
        set(value) {
            field = value
            invalidate()
        }
    var statusLine: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val pieceBitmap = mutableMapOf<String, Bitmap>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xF4, 0xE4, 0xC1) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x77, 0x4F, 0x2C)
        strokeWidth = 1f * density
        style = Paint.Style.STROKE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0x2E, 0x9E, 0x2E)
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 10f * density
    }

    private fun piece(code: String): Bitmap? {
        pieceBitmap[code]?.let { return it }
        return try {
            context.assets.open("pieces/$code.webp").use { input ->
                BitmapFactory.decodeStream(input)?.also { pieceBitmap[code] = it }
            }
        } catch (_: Throwable) {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pos = position
        val status = statusLine
        val statusH = if (status.isBlank()) 0f else 14f * density
        val boardTop = 0f
        val boardH = height - statusH
        if (boardH <= 0 || width <= 0) return

        // 等比适配 9x10 交叉点棋盘（含一点边距）
        val pad = 4f * density
        val contentW = width - pad * 2
        val contentH = boardH - pad * 2
        val cell = minOf(contentW / 8f, contentH / 9f)
        val left = (width - cell * 8f) / 2f
        val top = boardTop + (boardH - cell * 9f) / 2f

        canvas.drawRect(0f, boardTop, width.toFloat(), boardTop + boardH, bgPaint)
        for (r in 0..9) {
            val y = top + r * cell
            canvas.drawLine(left, y, left + 8 * cell, y, linePaint)
        }
        for (f in 0..8) {
            val x = left + f * cell
            canvas.drawLine(x, top, x, top + 9 * cell, linePaint)
        }
        // 河界横线加粗
        val riverY0 = top + 4 * cell
        val riverY1 = top + 5 * cell
        canvas.drawLine(left, riverY0, left + 8 * cell, riverY0, linePaint)
        canvas.drawLine(left, riverY1, left + 8 * cell, riverY1, linePaint)

        if (pos != null) {
            val radius = cell * 0.42f
            for (rank in 0 until 10) {
                for (file in 0 until 9) {
                    val code = pos.pieceAt(rank, file) ?: continue
                    val bmp = piece(code) ?: continue
                    val cx = left + file * cell
                    val cy = top + rank * cell
                    val size = radius * 2f
                    val dst = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
                    canvas.drawBitmap(bmp, null, dst, null)
                }
            }
        }

        hintMove?.let { m ->
            val x0 = left + m.fromFile * cell
            val y0 = top + m.fromRank * cell
            val x1 = left + m.toFile * cell
            val y1 = top + m.toRank * cell
            drawArrow(canvas, x0, y0, x1, y1)
        }

        if (statusH > 0f) {
            canvas.drawRect(0f, boardTop + boardH, width.toFloat(), height.toFloat(), Paint().apply {
                color = 0xE618202A.toInt()
            })
            canvas.drawText(status, 6f * density, height - 4f * density, textPaint)
        }
    }

    private fun drawArrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1f) return
        val ux = dx / len
        val uy = dy / len
        val shrink = minOf(len * 0.18f, 10f * density)
        val sx = x0 + ux * shrink
        val sy = y0 + uy * shrink
        val ex = x1 - ux * shrink
        val ey = y1 - uy * shrink
        canvas.drawLine(sx, sy, ex, ey, hintPaint)
        val head = 8f * density
        val path = Path().apply {
            moveTo(ex, ey)
            lineTo(ex - ux * head - uy * head * 0.55f, ey - uy * head + ux * head * 0.55f)
            lineTo(ex - ux * head + uy * head * 0.55f, ey - uy * head - ux * head * 0.55f)
            close()
        }
        canvas.drawPath(path, Paint(hintPaint).apply { style = Paint.Style.FILL })
    }
}
