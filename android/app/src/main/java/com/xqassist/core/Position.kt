package com.xqassist.core

/** Xiangqi board position: FEN parse/serialize, apply ICCS moves, diff two boards. */
class Position private constructor(
    private val cells: MutableList<MutableList<String?>>,
    var sideToMove: String,
    var moveNum: Int,
    var halfmoveClock: Int,
) {
    companion object {
        val FILE_NAMES = "abcdefghi"

        fun fromStartpos(): Position = fromFen(START_FEN)

        fun fromFen(fen: String): Position {
            val parts = fen.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) throw IllegalArgumentException("empty FEN")
            val rows = parts[0].split("/")
            require(rows.size == 10) { "FEN must have 10 ranks, got ${rows.size}" }
            val cells = mutableListOf<MutableList<String?>>()
            for (rank in rows) {
                val row = mutableListOf<String?>()
                for (ch in rank) {
                    when {
                        ch.isDigit() -> repeat(ch.digitToInt()) { row.add(null) }
                        ch in "RNBAKCP" -> row.add("w" + ch.lowercaseChar())
                        ch in "rnbakcp" -> row.add("b" + ch)
                        else -> throw IllegalArgumentException("bad FEN piece token: $ch")
                    }
                }
                require(row.size == 9) { "FEN rank must have 9 files, got ${row.size}" }
                cells.add(row)
            }
            val side = if (parts.size > 1 && parts[1] in "wb") parts[1] else "w"
            val halfmove = if (parts.size > 4 && parts[4].all { it.isDigit() }) parts[4].toInt() else 0
            val moveNo = if (parts.size > 5 && parts[5].all { it.isDigit() }) parts[5].toInt() else 1
            return Position(cells, side, moveNo, halfmove)
        }

        const val START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RNBAKABNR w"
    }

    fun toFen(): String {
        val rows = cells.map { row ->
            val buf = StringBuilder()
            var empty = 0
            for (cell in row) {
                if (cell == null) {
                    empty++
                    continue
                }
                if (empty > 0) {
                    buf.append(empty); empty = 0
                }
                if (cell.length == 2 && cell[0] in "wb" && cell[1] in "rnbakcp") {
                    buf.append(if (cell[0] == 'w') cell[1].uppercaseChar() else cell[1])
                }
            }
            if (empty > 0) buf.append(empty)
            buf.toString()
        }
        return rows.joinToString("/") + " $sideToMove - - $halfmoveClock $moveNum"
    }

    fun pieceAt(rank: Int, file: Int): String? =
        if (rank in 0..9 && file in 0..8) cells[rank][file] else null

    fun setPiece(rank: Int, file: Int, piece: String?) {
        if (rank in 0..9 && file in 0..8) cells[rank][file] = piece
    }

    fun copy(): Position = Position(
        cells.map { it.toMutableList() }.toMutableList(),
        sideToMove, moveNum, halfmoveClock,
    )

    /** Apply ICCS move like 'h2e2' (fromFile, fromRank, toFile, toRank). */
    fun applyIccs(move: String) {
        val m = Regex("([a-i])([0-9])([a-i])([0-9])").matchEntire(move.trim().lowercase())
            ?: throw IllegalArgumentException("bad ICCS move: $move")
        val fromFile = FILE_NAMES.indexOf(m.groupValues[1])
        val fromRank = m.groupValues[2].toInt()
        val toFile = FILE_NAMES.indexOf(m.groupValues[3])
        val toRank = m.groupValues[4].toInt()
        val piece = pieceAt(fromRank, fromFile)
            ?: throw IllegalArgumentException("no piece at ${m.groupValues[1]}${m.groupValues[2]} in FEN ${toFen()}")
        setPiece(fromRank, fromFile, null)
        setPiece(toRank, toFile, piece)
        if (sideToMove == "b") moveNum++
        sideToMove = if (sideToMove == "w") "b" else "w"
    }

    /** Infer moves (fromRank,fromFile,toRank,toFile) by comparing to `other`. */
    fun diffFrom(other: Position): List<Quad> {
        val disappeared = mutableListOf<Coord>()
        val appeared = mutableListOf<Coord>()
        for (r in 0 until 10) for (f in 0 until 9) {
            val prev = other.pieceAt(r, f)
            val cur = pieceAt(r, f)
            when {
                prev != null && cur == null -> disappeared.add(Coord(r, f))
                prev == null && cur != null -> appeared.add(Coord(r, f))
            }
        }
        val moves = mutableListOf<Quad>()
        val used = mutableSetOf<Coord>()
        for ((fr, ff) in disappeared) {
            val moving = other.pieceAt(fr, ff)
            var best: Coord? = null
            for (to in appeared) {
                if (to in used) continue
                if (pieceAt(to.rank, to.file) == moving) { best = to; break }
            }
            if (best == null) continue
            used.add(best)
            moves.add(Quad(fr, ff, best.rank, best.file))
        }
        return moves
    }

    override fun toString(): String =
        cells.joinToString("\n") { row -> row.joinToString(" ") { it ?: ".." } }
}

data class Coord(val rank: Int, val file: Int)
data class Quad(val fromRank: Int, val fromFile: Int, val toRank: Int, val toFile: Int) {
    fun iccs(): String =
        "${Position.FILE_NAMES[fromFile]}$fromRank${Position.FILE_NAMES[toFile]}$toRank"
}