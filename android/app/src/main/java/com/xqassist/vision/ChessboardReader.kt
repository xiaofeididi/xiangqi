package com.xqassist.vision

import android.graphics.Bitmap
import com.xqassist.core.Position

/**
 * Board recognizer input: the captured frame plus a caller-supplied board rect.
 * Kept vision-engine-agnostic so OpenCV/TFLite can swap in later.
 */
data class BoardRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

interface ChessboardReader {
    /** Sample the board region at 9x10 intersection points. */
    fun readBoard(frame: Bitmap, board: BoardRect): Position
}

/**
 * Grid math aligned with Pro FloatingWindowService:
 * board rect is the OUTER wood/frame area (piece grid + 1 cell margin).
 * Cell size = w/10 x h/11; first intersection is inset half a cell.
 * Same formula as Pro's touchMove and our ConnectSession.proScreen.
 */
object GridGeometry {
    fun intersection(board: BoardRect, rank: Int, file: Int): Pair<Int, Int> {
        val w = (board.right - board.left).coerceAtLeast(1)
        val h = (board.bottom - board.top).coerceAtLeast(1)
        val cellW = (w / 10).coerceAtLeast(1)
        val cellH = (h / 11).coerceAtLeast(1)
        val cx = (board.left + board.right) / 2
        val x = cx - ((4 - file) * cellW)
        val y = if (rank <= 4) {
            board.top + cellH + rank * cellH
        } else {
            board.bottom - cellH - ((9 - rank) * cellH)
        }
        return x to y
    }
}