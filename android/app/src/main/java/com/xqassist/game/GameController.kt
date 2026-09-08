package com.xqassist.game

import com.xqassist.core.Position
import com.xqassist.core.Quad

/** 棋局状态机：对弈、悔棋、提示、编辑、FEN 导入导出 */
class GameController {

    var pos: Position = Position.fromStartpos()
        private set
    private val history = ArrayDeque<Position>()
    private val moveHistory = ArrayDeque<Quad>()

    /** 每步之后的局面快照，snapshots[0] 为初始局面 */
    private val snapshots = mutableListOf<Position>()

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
    var autoReply: Boolean = false
    var thinking: Boolean = false

    init {
        resetHistory(pos)
    }

    /** 回看中：-1 表示最新局面；>=0 表示查看第 N 步之后的局面 */
    var browseIndex: Int = -1
        private set

    /** 棋盘实际显示的局面（回看或最新） */
    val displayPos: Position
        get() = if (browseIndex >= 0 && browseIndex < snapshots.size) snapshots[browseIndex] else pos

    /** 回看时该步的走子（供画最后一步标记） */
    val displayLastMove: Quad?
        get() = if (browseIndex > 0 && browseIndex - 1 < moveHistory.size) moveHistory.toList()[browseIndex - 1] else null

    /** 全部走子记录（棋谱用） */
    val moves: List<Quad> get() = moveHistory.toList()

    /** 第 i 步走子前的局面（棋谱记谱用） */
    fun preMovePos(i: Int): Position = if (i in snapshots.indices) snapshots[i] else snapshots.first()

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

    /** 走子：轮到哪方就走哪方，红黑都可手动走 */
    fun tryHumanMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        if (editMode || isGameOver || browseIndex >= 0) return false
        val piece = pos.pieceAt(fromRank, fromFile) ?: return false
        if (piece.first().toString() != pos.sideToMove) return false
        if (!Rules.isLegal(pos, fromRank, fromFile, toRank, toFile)) return false
        applyMove(fromRank, fromFile, toRank, toFile)
        return true
    }

    fun applyEngineMove(iccs: String): Boolean {
        if (editMode || browseIndex >= 0 || iccs.isBlank()) return false
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return false
        val fromFile = Position.FILE_NAMES.indexOf(match.groupValues[1])
        val fromRank = 9 - match.groupValues[2].toInt()
        val toFile = Position.FILE_NAMES.indexOf(match.groupValues[3])
        val toRank = 9 - match.groupValues[4].toInt()
        if (!Rules.isLegal(pos, fromRank, fromFile, toRank, toFile)) return false
        applyMove(fromRank, fromFile, toRank, toFile)
        return true
    }

    fun applyMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int) {
        history.addLast(pos.copy())
        val move = Quad(fromRank, fromFile, toRank, toFile)
        pos.applyIccs(move.iccs())
        moveHistory.addLast(move)
        snapshots.add(pos.copy())
        browseIndex = -1
        lastMove = move
        lastPreMove = history.last()
        hintMove = null
    }

    fun undo(): Boolean {
        if (history.isEmpty() || editMode || browseIndex >= 0) return false
        pos = history.removeLast()
        moveHistory.removeLastOrNull()
        if (snapshots.size > 1) snapshots.removeLast()
        browseIndex = -1
        lastMove = moveHistory.lastOrNull()
        lastPreMove = history.lastOrNull()
        hintMove = null
        return true
    }

    fun hintFromIccs(iccs: String) {
        val match = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.trim().lowercase()) ?: return
        val fromFile = Position.FILE_NAMES.indexOf(match.groupValues[1])
        val fromRank = 9 - match.groupValues[2].toInt()
        val toFile = Position.FILE_NAMES.indexOf(match.groupValues[3])
        val toRank = 9 - match.groupValues[4].toInt()
        hintMove = Quad(fromRank, fromFile, toRank, toFile)
    }

    fun clearHint() {
        hintMove = null
    }

    fun importFen(value: String): Boolean {
        val clean = value.trim().removePrefix("FEN:").trim()
        return try {
            pos = Position.fromFen(clean)
            resetHistory(pos)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun exportFen(): String = pos.toFen()

    fun startEditMode() {
        editMode = true
        resetHistory(pos)
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
        if (browseIndex >= 0) browseIndex = -1
        if (editErase) {
            val removed = pos.pieceAt(rank, file)
            pos.setPiece(rank, file, null)
            resetHistory(pos)
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
        resetHistory(pos)
        editPiece = null
        return "已放置棋子"
    }

    fun clearBoard() {
        if (browseIndex >= 0) browseIndex = -1
        for (rank in 0..9) for (file in 0..8) pos.setPiece(rank, file, null)
        pos.sideToMove = "w"
        resetHistory(pos)
    }

    fun setSideToMove(side: String) {
        if (side in setOf("w", "b")) {
            pos.sideToMove = side
            resetHistory(pos)
        }
    }

    private fun resetHistory(newPos: Position) {
        history.clear()
        moveHistory.clear()
        snapshots.clear()
        snapshots.add(newPos.copy())
        browseIndex = -1
        lastMove = null
        lastPreMove = null
        hintMove = null
    }

    /** 回看导航：0=初始局面，-1=最新 */
    fun browseTo(index: Int): Boolean {
        browseIndex = when {
            index < 0 -> -1
            index > snapshots.size - 1 -> -1
            else -> index
        }
        return true
    }

    val browseMax: Int get() = snapshots.size - 1
    fun pieceText(piece: String): String {
        val red = mapOf('r' to "车", 'n' to "马", 'b' to "相", 'a' to "仕", 'k' to "帅", 'c' to "炮", 'p' to "兵")
        val black = mapOf('r' to "车", 'n' to "马", 'b' to "象", 'a' to "士", 'k' to "将", 'c' to "炮", 'p' to "卒")
        val names = if (piece.first() == 'w') red else black
        return names[piece[1]] ?: piece
    }
}
