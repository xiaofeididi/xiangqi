package com.xqassist.game

import com.xqassist.core.Position
import com.xqassist.core.Quad

/** 人机对练的棋盘状态机：局面、走子、悔棋、提示 */
class GameController {

    var pos: Position = Position.fromStartpos()
        private set
    private val history = ArrayDeque<Position>()
    var lastMove: Quad? = null
        private set
    /** 最近一步走子之前的局面，用于中文记谱 */
    var lastPreMove: Position? = null
        private set
    var hintMove: Quad? = null

    /** 人类执子方："w" 红先行(默认)、"b" 黑 */
    var humanSide: String = "w"

    /** 每走一步后是否自动由引擎回招 */
    var autoReply: Boolean = true

    var thinking: Boolean = false

    val sideToMove: String get() = pos.sideToMove

    val isGameOver: Boolean get() = winner() != null

    /** 通过将/帅是否仍存在判定胜负；返回 "w"/"b"/null */
    fun winner(): String? {
        var redKing = false
        var blackKing = false
        for (r in 0..9) for (f in 0..8) {
            when (pos.pieceAt(r, f)) {
                "wk" -> redKing = true
                "bk" -> blackKing = true
            }
        }
        if (!blackKing) return "w"
        if (!redKing) return "b"
        return null
    }

    fun newGame() {
        pos = Position.fromStartpos()
        history.clear()
        lastMove = null
        lastPreMove = null
        hintMove = null
    }

    fun canMove(side: String): Boolean = !isGameOver && pos.sideToMove == side

    /** 尝试人类走子；合法并走子成功返回 true */
    fun tryHumanMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int): Boolean {
        if (pos.sideToMove != humanSide) return false
        val piece = pos.pieceAt(fromRank, fromFile) ?: return false
        if (piece[0].toString() != humanSide) return false
        if (!Rules.isLegal(pos, fromRank, fromFile, toRank, toFile)) return false
        applyMove(fromRank, fromFile, toRank, toFile)
        return true
    }

    /** 引擎/内部直接落子（仍校验走法合法性） */
    fun applyEngineMove(iccs: String): Boolean {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.lowercase()) ?: return false
        val ff = Position.FILE_NAMES.indexOf(m.groupValues[1])
        val fr = m.groupValues[2].toInt()
        val tf = Position.FILE_NAMES.indexOf(m.groupValues[3])
        val tr = m.groupValues[4].toInt()
        if (!Rules.isLegal(pos, fr, ff, tr, tf)) return false
        applyMove(fr, ff, tr, tf)
        return true
    }

    fun applyMove(fromRank: Int, fromFile: Int, toRank: Int, toFile: Int) {
        val preMove = pos.copy()
        history.addLast(preMove)
        pos.applyIccs(Quad(fromRank, fromFile, toRank, toFile).iccs())
        lastMove = Quad(fromRank, fromFile, toRank, toFile)
        lastPreMove = preMove
        hintMove = null
    }

    /** 悔棋：回退到轮到人类行棋的那一手；返回回退的步数 */
    fun undo(): Int {
        var steps = 0
        while (history.isNotEmpty()) {
            pos = history.removeLast()
            steps++
            if (pos.sideToMove == humanSide) break
        }
        if (steps > 0) {
            lastMove = null
            lastPreMove = null
            hintMove = null
        }
        return steps
    }

    fun hintFromIccs(iccs: String) {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(iccs.lowercase()) ?: return
        val ff = Position.FILE_NAMES.indexOf(m.groupValues[1])
        val fr = m.groupValues[2].toInt()
        val tf = Position.FILE_NAMES.indexOf(m.groupValues[3])
        val tr = m.groupValues[4].toInt()
        hintMove = Quad(fr, ff, tr, tf)
    }

    fun clearHint() { hintMove = null }
}