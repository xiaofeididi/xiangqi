package com.xqassist.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.xqassist.core.Position
import com.xqassist.core.Quad
import com.xqassist.game.GameController

/** 自绘象棋棋盘：网格、楚河汉界、棋子贴图、选中/最近一步高亮、最优着法绿箭头 */
class BoardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    var controller: GameController? = null
    var flipped: Boolean = false
    var mirrored: Boolean = false
    /** 是否显示最优着法箭头 */
    var showArrow: Boolean = true
    /** 是否画坐标数字 */
    var showCoords: Boolean = true
    var selected: Quad? = null
    var listener: ((rank: Int, file: Int) -> Unit)? = null

    private val pieceBitmap = mutableMapOf<String, Bitmap>()

    private val density = resources.displayMetrics.density
    private val bgPaint = Paint().apply { color = Color.rgb(0xE8, 0xC7, 0x8F) }
    private val linePaint = Paint().apply {
        color = Color.rgb(0x5A, 0x3A, 0x1A); strokeWidth = 2f * density; style = Paint.Style.STROKE
    }
    private val thickPaint = Paint().apply {
        color = Color.rgb(0x5A, 0x3A, 0x1A); strokeWidth = 4f * density; style = Paint.Style.STROKE
    }
    private val lastPaint = Paint().apply {
        color = Color.rgb(0x00, 0x99, 0x66); strokeWidth = 8f * density; style = Paint.Style.STROKE
    }
    private val selPaint = Paint().apply {
        color = Color.rgb(0xE6, 0x7E, 0x22); strokeWidth = 5f * density; style = Paint.Style.STROKE
    }
    private val hintPaint = Paint().apply {
        color = Color.argb(220, 0x2E, 0x9E, 0x2E); strokeWidth = 7f * density
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE
    }
    private val riverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x7A, 0x50, 0x20); textSize = 30f * density
        textAlign = Paint.Align.CENTER
    }

    private var cell = 0f
    private var pad = 0f
    private var boardHeight = 0f

    private fun pos(): Position = controller?.displayPos ?: Position.fromStartpos()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        computeMetrics()
        val top = pad
        val left = pad
        drawGrid(canvas, top, left)

        // 楚河汉界
        val midTop = top + 4 * cell
        val midBot = top + 5 * cell
        val midY = (midTop + midBot) / 2 - 6 * density
        canvas.drawText("楚河", left + 2.5f * cell, midY, riverPaint)
        canvas.drawText("汉界", left + 5.5f * cell, midY, riverPaint)

        if (showCoords) drawCoords(canvas, top, left)
        drawMarkers(canvas, top, left)
        drawPieces(canvas, top, left)
        drawHint(canvas, top, left)
    }

    private fun computeMetrics() {
        val hPad = width * 0.05f
        // 上下预留坐标文字空间，避免 1-9 / 九-一 被裁掉
        val vPad = height * 0.08f
        cell = minOf((width - 2 * hPad) / 8f, (height - 2 * vPad) / 9f)
        pad = minOf(hPad, vPad)
        boardHeight = cell * 9
    }

    private fun gridX(file: Int, left: Float): Float = left + screenFile(file) * cell
    private fun gridY(rank: Int, top: Float): Float = top + screenRank(rank) * cell
    private fun screenRank(rank: Int): Int = if (flipped) 9 - rank else rank
    private fun screenFile(file: Int): Int = if (mirrored) 8 - file else file

    private fun drawGrid(canvas: Canvas, top: Float, left: Float) {
        val right = left + 8 * cell
        val bottom = top + boardHeight
        for (r in 0..9) {
            val y = top + r * cell
            if (r == 0 || r == 9) canvas.drawLine(left, y, right, y, thickPaint)
            else canvas.drawLine(left, y, right, y, linePaint)
        }
        for (f in 0..8) {
            val x = left + f * cell
            if (f == 0 || f == 8) {
                canvas.drawLine(x, top, x, bottom, thickPaint)
            } else {
                canvas.drawLine(x, top, x, top + 4 * cell, linePaint)
                canvas.drawLine(x, top + 5 * cell, x, bottom, linePaint)
            }
        }
        val path = Path()
        path.moveTo(left + 3 * cell, top); path.lineTo(left + 5 * cell, top + 2 * cell)
        path.moveTo(left + 5 * cell, top); path.lineTo(left + 3 * cell, top + 2 * cell)
        path.moveTo(left + 3 * cell, bottom); path.lineTo(left + 5 * cell, bottom - 2 * cell)
        path.moveTo(left + 5 * cell, bottom); path.lineTo(left + 3 * cell, bottom - 2 * cell)
        canvas.drawPath(path, linePaint)
    }

    private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0x5A, 0x3A, 0x1A); textAlign = Paint.Align.CENTER
    }

    /** 棋盘坐标：上方黑方 1-9，下方红方 九-一（随翻转交换） */
    private fun drawCoords(canvas: Canvas, top: Float, left: Float) {
        val blackNum = arrayOf("1", "2", "3", "4", "5", "6", "7", "8", "9")
        val redNum = arrayOf("九", "八", "七", "六", "五", "四", "三", "二", "一")
        coordPaint.textSize = cell * 0.26f
        for (sf in 0..8) {
            val file = if (mirrored) 8 - sf else sf
            val x = left + sf * cell
            val topLabel = if (flipped) redNum[file] else blackNum[file]
            val bottomLabel = if (flipped) blackNum[file] else redNum[file]
            canvas.drawText(topLabel, x, top - cell * 0.18f, coordPaint)
            canvas.drawText(bottomLabel, x, top + boardHeight + cell * 0.42f, coordPaint)
        }
    }
    private fun drawMarkers(canvas: Canvas, top: Float, left: Float) {
        val m = controller ?: return
        val last = m.displayLastMove
        if (last != null) {
            mark(canvas, gridX(last.fromFile, left), gridY(last.fromRank, top), lastPaint)
            mark(canvas, gridX(last.toFile, left), gridY(last.toRank, top), lastPaint)
        }
        val sel = selected
        if (sel != null) {
            mark(canvas, gridX(sel.fromFile, left), gridY(sel.fromRank, top), selPaint)
        }
    }

    /** 提示箭头画在棋子之上，避免被棋子遮住（对齐网页版效果） */
    private fun drawHint(canvas: Canvas, top: Float, left: Float) {
        if (!showArrow) return
        val m = controller ?: return
        if (m.browseIndex >= 0) return
        val hint = m.hintMove ?: return
        drawHintArrow(canvas,
            gridX(hint.fromFile, left), gridY(hint.fromRank, top),
            gridX(hint.toFile, left), gridY(hint.toRank, top))
    }

    private fun mark(canvas: Canvas, x: Float, y: Float, paint: Paint) {
        canvas.drawCircle(x, y, cell * 0.44f, paint)
    }

    private fun drawHintArrow(canvas: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        val r = cell * 0.62f
        val dx = x1 - x0; val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy)
        if (len < 1f) return
        val ux = dx / len; val uy = dy / len
        val sx = x0 + ux * r
        val sy = y0 + uy * r
        val ex = x1 - ux * r * 0.2f
        val ey = y1 - uy * r * 0.2f
        canvas.drawLine(sx, sy, ex, ey, hintPaint)
        val a0 = kotlin.math.atan2(ey - sy, ex - sx)
        val wing = 0.5f * cell
        val p1x = ex - wing * kotlin.math.cos(a0 - 0.4f)
        val p1y = ey - wing * kotlin.math.sin(a0 - 0.4f)
        val p2x = ex - wing * kotlin.math.cos(a0 + 0.4f)
        val p2y = ey - wing * kotlin.math.sin(a0 + 0.4f)
        val shape = Path()
        shape.moveTo(ex, ey); shape.lineTo(p1x, p1y); shape.lineTo(p2x, p2y); shape.close()
        canvas.drawPath(shape, Paint(hintPaint).apply { style = Paint.Style.FILL })
    }

    private fun drawPieces(canvas: Canvas, top: Float, left: Float) {
        for (r in 0..9) for (f in 0..8) {
            val code = pos().pieceAt(r, f) ?: continue
            val bmp = loadPiece(code) ?: continue
            val x = gridX(f, left) - cell * 0.46f
            val y = gridY(r, top) - cell * 0.46f
            canvas.drawBitmap(bmp, x, y, null)
        }
    }

    private fun loadPiece(code: String): Bitmap? {
        pieceBitmap[code]?.let { return it }
        val bmp = try {
            context.assets.open("pieces/$code.webp").use { BitmapFactory.decodeStream(it) }
        } catch (_: Throwable) {
            null
        } ?: return null
        val side = (cell * 0.92f).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, side, side, true)
        pieceBitmap[code] = scaled
        return scaled
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return true
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        val x = event.x - pad
        val y = event.y - pad
        if (x < -cell * 0.5f || y < -cell * 0.5f || x > 8.5f * cell || y > boardHeight + cell * 0.5f) return true
        var file = kotlin.math.round(x / cell).toInt()
        val srank = kotlin.math.round(y / cell).toInt()
        if (mirrored) file = 8 - file
        if (file in 0..8 && srank in 0..9) {
            val rank = if (flipped) 9 - srank else srank
            listener?.invoke(rank, file)
        }
        return true
    }
}
