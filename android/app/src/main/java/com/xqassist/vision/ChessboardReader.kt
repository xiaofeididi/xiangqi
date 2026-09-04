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

/** Grid math: 9 files x 10 ranks evenly across the board rect. */
object GridGeometry {
    fun intersection(board: BoardRect, rank: Int, file: Int): Pair<Int, Int> {
        val x = board.left + (board.right - board.left) * file / 8
        val y = board.top + (board.bottom - board.top) * rank / 9
        return x to y
    }
}