package com.xqassist.engine

import com.xqassist.core.Position
import com.xqassist.game.Rules
import kotlin.math.abs

/** Android 私有目录无法直接执行外部引擎时的本地战术评估引擎 */
class FallbackEngine {
    private val pieceValue = mapOf(
        'k' to 10000, 'r' to 900, 'c' to 450, 'n' to 400,
        'b' to 200, 'a' to 200, 'p' to 100,
    )

    fun analyze(position: Position): EngineResult {
        val moves = generateMoves(position)
        if (moves.isEmpty()) return EngineResult()

        var bestScore = Int.MIN_VALUE
        var bestMove = moves.first()
        for (move in moves) {
            val score = moveScore(position, move)
            if (score > bestScore) {
                bestScore = score
                bestMove = move
            }
        }
        val cp = if (position.sideToMove == "w") bestScore else -bestScore
        return EngineResult(bestMove, cp, null, 1, listOf(bestMove))
    }

    private fun generateMoves(position: Position): List<String> {
        val result = mutableListOf<String>()
        val side = position.sideToMove
        for (fromRank in 0..9) for (fromFile in 0..8) {
            val piece = position.pieceAt(fromRank, fromFile) ?: continue
            if (piece[0].toString() != side) continue
            for (toRank in 0..9) for (toFile in 0..8) {
                if (Rules.isLegal(position, fromRank, fromFile, toRank, toFile)) {
                    result += positionQuad(fromRank, fromFile, toRank, toFile)
                }
            }
        }
        return result
    }

    private fun positionQuad(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): String {
        val files = "abcdefghi"
        return "${files[fromFile]}$fromRank${files[toFile]}$toRank"
    }

    private fun moveScore(position: Position, move: String): Int {
        val files = "abcdefghi"
        val fromFile = files.indexOf(move[0])
        val fromRank = move[1].digitToInt()
        val toFile = files.indexOf(move[2])
        val toRank = move[3].digitToInt()
        val moving = position.pieceAt(fromRank, fromFile) ?: return Int.MIN_VALUE
        val captured = position.pieceAt(toRank, toFile)
        var score = captured?.let { (pieceValue[it[1]] ?: 0) * 10 } ?: 0

        position.applyIccs(move)
        score += positionalScore(position, moving[0])
        if (position.pieceAt(toRank, toFile)?.get(1) == 'k' && captured?.get(1) == 'k') score += 100000
        val opponentMoves = generateMoves(position)
        val opponentCanCapture = opponentMoves.any { attackScore(position, it) >= 1000 }
        if (opponentCanCapture) score -= (pieceValue[moving[1]] ?: 0) / 4
        position.undoIccs(move, moving, captured)
        return score
   }

    private fun attackScore(position: Position, move: String): Int {
        val files = "abcdefghi"
        val toFile = files.indexOf(move[2])
        val toRank = move[3].digitToInt()
        val target = position.pieceAt(toRank, toFile) ?: return 0
        if (target[0] == position.sideToMove) return 0
        return pieceValue[target[1]] ?: 0
    }

    private fun positionalScore(position: Position, side: Char): Int {
        var score = 0
        for (rank in 0..9) for (file in 0..8) {
            val piece = position.pieceAt(rank, file) ?: continue
            val multiplier = if (piece[0] == side) 1 else -1
            when (piece[1]) {
                'p' -> if ((piece[0] == 'w' && rank <= 4) || (piece[0] == 'b' && rank >= 5)) score += 10 * multiplier
                'n' -> score += 5 * multiplier
                'c' -> score += 3 * multiplier
                'r' -> score += 8 * multiplier
            }
        }
        return score
    }
}
