package com.xqassist.core

import com.xqassist.core.Position.Companion.FILE_NAMES

/** Chinese notation (炮二平五 style) for xiangqi moves. */
object Notation {
    private val CN = arrayOf("一","二","三","四","五","六","七","八","九")
    private val RED = mapOf('r' to "车",'n' to "马",'b' to "相",'a' to "仕",'k' to "帅",'c' to "炮",'p' to "兵")
    private val BLK = mapOf('r' to "车",'n' to "马",'b' to "象",'a' to "士",'k' to "将",'c' to "炮",'p' to "卒")

    private fun rankLabel(rank: Int, side: String): String =
        if (side == "w") CN[9 - rank] else CN[rank]

    private fun fileLabel(file: Int, side: String): String =
        if (side == "w") CN[8 - file] else CN[file]

    /** Convert ICCS 'h2e2' to Chinese notation based on board before the move. */
    fun moveToChinese(pos: Position, move: String): String {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(move.trim().lowercase())
            ?: return move
        val fromFile = FILE_NAMES.indexOf(m.groupValues[1])
        val fromRank = m.groupValues[2].toInt()
        val toFile = FILE_NAMES.indexOf(m.groupValues[3])
        val toRank = m.groupValues[4].toInt()
        val piece = pos.pieceAt(fromRank, fromFile) ?: return move
        val side = piece[0].toString()
        val name = (if (side == "w") RED else BLK)[piece[1]] ?: return move
        val fromLabel = rankLabel(fromRank, side)
        if (fromRank == toRank) return "$name${fromLabel}平${fileLabel(toFile, side)}"
        val forward = (side == "w" && toRank < fromRank) || (side == "b" && toRank > fromRank)
        val verb = if (forward) "进" else "退"
        return "$name$fromLabel$verb${fileLabel(toFile, side)}"
    }
}