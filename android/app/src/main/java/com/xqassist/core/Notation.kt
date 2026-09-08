package com.xqassist.core

import com.xqassist.core.Position.Companion.FILE_NAMES

/** 中文着法（炮二平五式），在给定局面上把 ICCS 着法转成中文描述 */
object Notation {
    private val CN = arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
    private fun num(n: Int): String = CN[(n - 1).coerceIn(0, 8)]

    private val RED = mapOf(
        'r' to "车", 'n' to "马", 'b' to "相", 'a' to "仕",
        'k' to "帅", 'c' to "炮", 'p' to "兵"
    )
    private val BLK = mapOf(
        'r' to "车", 'n' to "马", 'b' to "象", 'a' to "士",
        'k' to "将", 'c' to "炮", 'p' to "卒"
    )

    /** ICCS 着法（如 h2e2）转中文记谱，pos 为走子之前的局面 */
    fun moveToChinese(pos: Position, move: String): String {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(move.trim().lowercase())
            ?: return move
        val fromFile = FILE_NAMES.indexOf(m.groupValues[1])
        val fromRank = 9 - m.groupValues[2].toInt()
        val toFile = FILE_NAMES.indexOf(m.groupValues[3])
        val toRank = 9 - m.groupValues[4].toInt()
        if (fromRank !in 0..9 || toRank !in 0..9 || fromFile !in 0..8 || toFile !in 0..8) return move
        val piece = pos.pieceAt(fromRank, fromFile) ?: return move
        val isRed = piece[0] == 'w'
        val name = (if (isRed) RED else BLK)[piece[1]] ?: return move
        val fromLabel = if (isRed) num(9 - fromFile) else num(fromFile + 1)
        val toLabel = if (isRed) num(9 - toFile) else num(toFile + 1)
        if (fromRank == toRank) return name + fromLabel + "平" + toLabel
        val forward = (isRed && toRank < fromRank) || (!isRed && toRank > fromRank)
        val verb = if (forward) "进" else "退"
        val kind = piece[1]
        return if (kind == 'r' || kind == 'k' || kind == 'c' || kind == 'p') {
            // 直线棋子：进退记步数
            name + fromLabel + verb + num(kotlin.math.abs(toRank - fromRank))
        } else {
            // 马/相/士：进退记落点纵线
            name + fromLabel + verb + toLabel
        }
    }
}