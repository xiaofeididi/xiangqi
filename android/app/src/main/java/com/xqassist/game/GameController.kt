package com.xqassist.game

import com.xqassist.core.Position
import com.xqassist.core.Quad

/** 棋局状态机：对弈、悔棋、提示、编辑、FEN 导入导出 */
class GameController {

    var pos: Position = Position.fromStartpos()
        private set
    private val history = ArrayDeque<Position>()
    private val moveHistory = ArrayDeque<Quad>()

    var lastMove: Quad? = null
        private set
    var lastPreMove: Position? = null
        private set
    var hintMove: Quad? = null

    /** 编辑模式关闭时才允许正常走子 */
    var editMode = false
    /** 编辑时棋盘残留选中棋子，再次点击空点移动；点相同格子取消 */
    var editPiece: String? = null
    /** 删除模式：点击棋子清除 */
    var editErase = false

    var humanSide: String = "w"
    var autoReply: Boolean = true
    var thinking: Boolean = false

    val sideToMove: String get() = pos.sideToMove
    val fen: String get() = pos.toFen()
    val isGameOver: Boolean get() = winner() != null

    fun winner(): String? {
        var redKing = false
        var blackKing = false
        for (rank in 0..9) for (file in 0..8) {
            when (pos.pieceAt(rank, file)) {
                "wk" -> redKing = true
                "bk" -> blackKing = true
            }
        }
        return when {
            !blackKing -> "w"
            !redKing -> "b"
            else -> null
        }
    }

    fun newGame() {
        importFen(Position.START_FEN)
    }

    fun canMove(side: String) = !editMode && !isGameOver && sideToMove == side

    fun tryHumanMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        if (editMode || !canMove(humanSide)) return false
        val piece = pos.pieceAt(fromRank, fromFile) ?: return false
        if (piece.first().toString() != humanSide) return false
        if (!Rules.isLegal(pos, fromRank, fromFile, toRank, toFile)) return false
        applyMove(fromRank, fromFile, toRank, toFile)
        return true
    }

    fun applyEngineMove(iccs: String): Boolean {
        if (editMode || iccs.isBlank()) return false
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return false
        val fromFile = Position.FILE_NAMES.indexOf(match.groupValues[1])
        val fromRank = match.groupValues[2].toInt()
        val toFile = Position.FILE_NAMES.indexOf(match.groupValues[3])
        val toRank = match.groupValues[4].toInt()
        if (!Rules.isLegal(pos, fromRank, fromFile, toRank, toFile)) return false
        applyMove(fromRank, fromFile, toRank, toFile)
        return true
    }

    fun applyMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int) {
        history.addLast(pos.copy())
        val move = Quad(fromRank, fromFile, toRank, toFile)
        pos.applyIccs(move.iccs())
        moveHistory.addLast(move)
        lastMove = move
        lastPreMove = history.last()
        hintMove = null
    }

    fun undo(): Boolean {
        if (history.isEmpty()) return false
        pos = history.removeLast()
        moveHistory.removeLastOrNull()
        lastMove = moveHistory.lastOrNull()
        lastPreMove = history.lastOrNull()
        hintMove = null
        return true
    }

    fun hintFromIccs(iccs: String) {
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return
        val fromFile = Position.FILE_NAMES.indexOf(match.groupValues[1])
        val fromRank = match.groupValues[2].toInt()
        val toFile = Position.FILE_NAMES.indexOf(match.groupValues[3])
        val toRank = match.groupValues[4].toInt()
        hintMove = Quad(fromRank, fromFile, toRank, toFile)
    }

    fun clearHint() {
        hintMove = null
    }

    fun importFen(value: String): Boolean {
        val clean = value.trim().removePrefix("FEN:").trim()
        return try {
            pos = Position.fromFen(clean)
            history.clear()
            moveHistory.clear()
            lastMove = null
            lastPreMove = null
            hintMove = null
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun exportFen(): String = pos.toFen()

    fun startEditMode() {
        editMode = true
        selectedInternal = null
    }

    fun exitEditMode() {
        editMode = false
        editPiece = null
        editErase = false
        selectedInternal = null
    }

    private var selectedInternal: Quad? = null
    var selected: Quad? = null
        private set

    fun select(rank: Int, file: Int) {
        selected = Quad(rank, file, rank, file)
    }

    fun clearSelection() {
        selected = null
    }

    fun editTap(rank: Int, file: Int): String {
        if (editErase) {
            val removed = pos.pieceAt(rank, file)
            pos.setPiece(rank, file, null)
            return if (removed == null) "该位置没有棋子" else "已删除棋子"
        }
        val selectedPiece = editPiece
        if (selectedPiece == null) {
            val piece = pos.pieceAt(rank, file)
            if (piece == null) return "空点：请先选择要放的棋子"
            editPiece = piece
            return "已选中 ${pieceText(piece)}，点击目标格"
        }
        pos.setPiece(rank, file, selectedPiece)
        editPiece = null
        return "已放置棋子"
    }

    fun clearBoard() {
        for (rank in 0..9) for (file in 0..8) pos.setPiece(rank, file, null)
    }

    fun setSideToMove(side: String) {
        if (side in setOf("w", "b")) pos.sideToMove = side
    }

    fun pieceText(piece: String): String {
        val red = mapOf('r' to "车", 'n' to "马", 'b' to "相", 'a' to "仕", 'k' to "帅", 'c' to "炮", 'p' to "兵")
        val black = mapOf('r' to "车", 'n' to "马", 'b' to "象", 'a' to "士", 'k' to "将", 'c' to "炮", 'p' to "卒")
        val names = if (piece.first() == 'w') red else black
        return names[piece[1]] ?: piece
    }
}