package com.xqassist.game

import com.xqassist.core.Notation
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

    /** 变招：从主线第 fork 步之后的局面分出的分支 */
    class Variation(val fork: Int, first: Quad) {
        val moves = mutableListOf(first)
    }

    val variations = mutableListOf<Variation>()
    var activeVariation: Variation? = null
        private set

    /** 当前激活线路（主线或变招）的走子与局面快照缓存 */
    private val lineMoves = mutableListOf<Quad>()
    private val lineSnaps = mutableListOf<Position>()

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
        get() = if (browseIndex >= 0 && browseIndex < lineSnaps.size) lineSnaps[browseIndex] else lineSnaps.lastOrNull() ?: pos

    /** 回看时该步的走子（供画最后一步标记） */
    val displayLastMove: Quad?
        get() = if (browseIndex > 0 && browseIndex - 1 < lineMoves.size) lineMoves[browseIndex - 1]
                else if (browseIndex == -1) lineMoves.lastOrNull() else null

    /** 当前线路全部走子记录（棋谱用） */
    val moves: List<Quad> get() = lineMoves.toList()

    /** 当前线路第 i 步走子前的局面（棋谱记谱用） */
    fun preMovePos(i: Int): Position = if (i in lineSnaps.indices) lineSnaps[i] else lineSnaps.first()

    /** 回看局面的行棋方 */
    val displaySideToMove: String get() = displayPos.sideToMove

    /** 变招分支的全部中文记谱 */
    fun variationNotation(branch: Variation): String {
        val base = snapshots.getOrNull(branch.fork) ?: snapshots.first()
        val work = base.copy()
        val sb = StringBuilder()
        branch.moves.forEach { move ->
            sb.append(Notation.moveToChinese(work, move.iccs())).append(' ')
            work.applyIccs(move.iccs())
        }
        return sb.toString().trim()
    }

    fun activateVariation(index: Int) {
        val branch = variations.getOrNull(index) ?: return
        activeVariation = branch
        syncLine()
        browseIndex = -1
        lastMove = lineMoves.lastOrNull()
        lastPreMove = lineSnaps.getOrNull(lineSnaps.size - 2)
    }

    fun activateMainline() {
        if (activeVariation == null) return
        activeVariation = null
        syncLine()
        browseIndex = -1
        lastMove = lineMoves.lastOrNull()
        lastPreMove = lineSnaps.getOrNull(lineSnaps.size - 2)
    }

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

    /** 防长将/重复走法：记录前 6 步 ICCS，出现明显循环时给 UI 提示 */
    fun recentIccs(count: Int = 6): List<String> =
        (if (activeVariation != null) lineMoves else moveHistory).takeLast(count).map { it.iccs() }

    /** 最近一手是否与更早一手完全相同 */
    fun lastMoveRepeated(): Boolean {
        val list = (if (activeVariation != null) lineMoves else moveHistory).toList()
        if (list.size < 4) return false
        return list[list.size - 1].iccs() == list[list.size - 3].iccs()
    }

    fun applyMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int) {
        val move = Quad(fromRank, fromFile, toRank, toFile)
        val active = activeVariation
        if (active != null) {
            pos.applyIccs(move.iccs())
            active.moves.add(move)
            syncLine()
            browseIndex = -1
            lastMove = move
            lastPreMove = lineSnaps.getOrNull(lineSnaps.size - 2)
            hintMove = null
            return
        }
        val onMainEnd = browseIndex == -1
        if (onMainEnd) {
            history.addLast(pos.copy())
            pos.applyIccs(move.iccs())
            moveHistory.addLast(move)
            snapshots.add(pos.copy())
            syncLine()
            browseIndex = -1
            lastMove = move
            lastPreMove = history.last()
            hintMove = null
            return
        }
        val fork = browseIndex
        val base = snapshots.getOrNull(fork) ?: return
        activeVariation = Variation(fork, move)
        pos = base.copy()
        pos.applyIccs(move.iccs())
        history.clear()
        syncLine()
        browseIndex = -1
        lastMove = move
        lastPreMove = base
        hintMove = null
    }

    fun undo(): Boolean {
        if (editMode || browseIndex >= 0) return false
        val active = activeVariation
        if (active != null) {
            if (active.moves.isEmpty()) {
                activeVariation = null
            } else {
                active.moves.removeAt(active.moves.size - 1)
                pos = run {
                    val base = snapshots.getOrNull(active.fork)?.copy() ?: pos
                    for (m in active.moves.take(active.moves.size)) base.applyIccs(m.iccs())
                    base
                }
                if (active.moves.isEmpty()) activeVariation = null
            }
            syncLine()
            lastMove = lineMoves.lastOrNull()
            lastPreMove = lineSnaps.getOrNull(lineSnaps.size - 2)
            hintMove = null
            return true
        }
        if (history.isEmpty()) return false
        pos = history.removeLast()
        moveHistory.removeLastOrNull()
        if (snapshots.size > 1) snapshots.removeLast()
        syncLine()
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
            activeVariation = null
            resetHistory(pos)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun exportFen(): String = pos.toFen()

    fun startEditMode() {
        editMode = true
        activeVariation = null
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
            if (piece == null) return "空点：请先从下方选择棋子"
            editPiece = piece
            pos.setPiece(rank, file, null)
            resetHistory(pos)
            return "已拿起 " + pieceText(piece) + "，点击目标格放置"
        }
        val existed = pos.pieceAt(rank, file)
        pos.setPiece(rank, file, selectedPiece)
        resetHistory(pos)
        editPiece = null
        return "已放置 " + pieceText(selectedPiece) + (if (existed == null) "" else "，覆盖了 " + pieceText(existed))
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
        variations.clear()
        activeVariation = null
        syncLine()
        browseIndex = -1
        lastMove = null
        lastPreMove = null
        hintMove = null
    }

    private fun syncLine() {
        lineMoves.clear()
        lineSnaps.clear()
        val active = activeVariation
        if (active == null) {
            lineMoves.addAll(moveHistory)
            lineSnaps.addAll(snapshots)
        } else {
            val base = snapshots.getOrNull(active.fork) ?: snapshots.first()
            for (i in 0..active.fork) lineSnaps.add(snapshots.getOrNull(i)?.copy() ?: base.copy())
            val work = base.copy()
            for (m in active.moves) {
                lineMoves.add(m)
                work.applyIccs(m.iccs())
                lineSnaps.add(work.copy())
            }
        }
    }

    /** 回看导航：0=初始局面，-1=最新 */
    fun browseTo(index: Int): Boolean {
        browseIndex = when {
            index < 0 -> -1
            index > lineSnaps.size - 1 -> -1
            else -> index
        }
        return true
    }

    val browseMax: Int get() = lineSnaps.size - 1
    fun pieceText(piece: String): String {
        val red = mapOf('r' to "车", 'n' to "马", 'b' to "相", 'a' to "仕", 'k' to "帅", 'c' to "炮", 'p' to "兵")
        val black = mapOf('r' to "车", 'n' to "马", 'b' to "象", 'a' to "士", 'k' to "将", 'c' to "炮", 'p' to "卒")
        val names = if (piece.first() == 'w') red else black
        return names[piece[1]] ?: piece
    }
}
