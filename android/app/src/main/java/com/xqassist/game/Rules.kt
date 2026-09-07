package com.xqassist.game

import com.xqassist.core.Position

/** Minimal chess rules: per-piece movement legality on our (rank,file) coordinates. */
object Rules {

    fun isLegal(pos: Position, fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        if (fromRank !in 0..9 || toRank !in 0..9 || fromFile !in 0..8 || toFile !in 0..8) return false
        val piece = pos.pieceAt(fromRank, fromFile) ?: return false
        val target = pos.pieceAt(toRank, toFile)
        if (target != null && target[0] == piece[0]) return false
        val isRed = piece[0] == 'w'
        return when (piece[1]) {
            'r' -> rookLegal(pos, fromRank, fromFile, toRank, toFile)
            'n' -> knightLegal(pos, fromRank, fromFile, toRank, toFile)
            'b' -> bishopLegal(pos, isRed, fromRank, fromFile, toRank, toFile)
            'a' -> advisorLegal(pos, isRed, fromRank, fromFile, toRank, toFile)
            'k' -> kingLegal(pos, isRed, fromRank, fromFile, toRank, toFile)
            'c' -> cannonLegal(pos, fromRank, fromFile, toRank, toFile)
            'p' -> pawnLegal(pos, isRed, fromRank, fromFile, toRank, toFile)
            else -> false
        }
    }

    private fun rookLegal(pos: Position, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        if (fr != tr && ff != tf) return false
        return clearPathCount(pos, fr, ff, tr, tf) == 0
    }

    private fun cannonLegal(pos: Position, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        if (fr != tr && ff != tf) return false
        val between = clearPathCount(pos, fr, ff, tr, tf)
        return if (pos.pieceAt(tr, tf) == null) between == 0 else between == 1
    }

    private fun knightLegal(pos: Position, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        val dr = kotlin.math.abs(tr - fr); val df = kotlin.math.abs(tf - ff)
        if (dr == 2 && df == 1) return pos.pieceAt(fr + (tr - fr) / 2, ff) == null
        if (dr == 1 && df == 2) return pos.pieceAt(fr, ff + (tf - ff) / 2) == null
        return false
    }

    private fun bishopLegal(pos: Position, red: Boolean, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        val dr = tr - fr; val df = tf - ff
        if (kotlin.math.abs(dr) != 2 || kotlin.math.abs(df) != 2) return false
        if (pos.pieceAt(fr + dr / 2, ff + df / 2) != null) return false
        val row = if (red) tr >= 5 else tr <= 4
        return row
    }

    private fun advisorLegal(pos: Position, red: Boolean, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        if (kotlin.math.abs(tr - fr) != 1 || kotlin.math.abs(tf - ff) != 1) return false
        if (tf !in 3..5) return false
        return if (red) tr in 7..9 else tr in 0..2
    }

    private fun kingLegal(pos: Position, red: Boolean, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        val dr = kotlin.math.abs(tr - fr); val df = kotlin.math.abs(tf - ff)
        if (dr + df == 1) {
            return tf in 3..5 && (if (red) tr in 7..9 else tr in 0..2)
        }
        // flying general: face-to-face capture along a file
        val target = pos.pieceAt(tr, tf)
        if (df == 0 && target != null && target[1] == 'k') {
            val lo = minOf(fr, tr) + 1; val hi = maxOf(fr, tr) - 1
            for (r in lo..hi) if (pos.pieceAt(r, ff) != null) return false
            return true
        }
        return false
    }

    private fun pawnLegal(pos: Position, red: Boolean, fr: Int, ff: Int, tr: Int, tf: Int): Boolean {
        val dr = tr - fr; val df = tf - ff
        val fwd = if (red) -1 else 1
        if (dr == fwd && df == 0) return true
        val acrossRiver = if (red) fr <= 4 else fr >= 5
        return acrossRiver && dr == 0 && kotlin.math.abs(df) == 1
    }

    private fun clearPathCount(pos: Position, fr: Int, ff: Int, tr: Int, tf: Int): Int {
        var count = 0
        if (fr == tr) {
            val lo = minOf(ff, tf) + 1; val hi = maxOf(ff, tf) - 1
            for (f in lo..hi) if (pos.pieceAt(fr, f) != null) count++
        } else {
            val lo = minOf(fr, tr) + 1; val hi = maxOf(fr, tr) - 1
            for (r in lo..hi) if (pos.pieceAt(r, ff) != null) count++
        }
        return count
    }
}
