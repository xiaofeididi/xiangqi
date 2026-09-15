package com.xqassist.book

import android.database.sqlite.SQLiteDatabase
import com.xqassist.core.Position
import java.io.File

data class LocalBookMove(
    val move: String,
    val score: Int,
    val winRate: Float,
    val drawRate: Float,
)

/**
 * 本地 .obk 开局库（Pro 同款 SQLite：表 bhobk）。
 * vkey = Zobrist(局面)；vmove = (from<<8)|to，from/to 为 51..203 的方格码。
 */
class LocalBook(path: String) {
    private val db: SQLiteDatabase = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)

    fun close() = runCatching { db.close() }

    fun query(pos: Position): List<LocalBookMove> {
        // Pro t2.c：board[0] = a9（红底），我们的 Position rank9 = 红底 → 需倒序
        val board = Array(10) { r ->
            CharArray(9) { f ->
                fenChar(pos.pieceAt(9 - r, f) ?: " ")
            }
        }
        val sideIsRed = pos.sideToMove == "w"
        val moves = mutableListOf<LocalBookMove>()
        moves += queryKey(positionKey(board, sideIsRed, mirror = false), mirror = false)
        moves += queryKey(positionKey(board, sideIsRed, mirror = true), mirror = true)
        return moves.sortedByDescending { it.score }
    }

    private fun queryKey(key: Long, mirror: Boolean): List<LocalBookMove> {
        val sql = if (key < 0) {
            "SELECT vmove,vwin,vdraw,vlost,vscore FROM bhobk WHERE cast(vkey as double)=? AND vvalid=1"
        } else {
            "SELECT vmove,vwin,vdraw,vlost,vscore FROM bhobk WHERE cast(vkey as integer)=? AND vvalid=1"
        }
        val arg = if (key < 0) java.lang.Double.longBitsToDouble(key).toString() else key.toString()
        val out = mutableListOf<LocalBookMove>()
        db.rawQuery(sql, arrayOf(arg)).use { c ->
            val iMove = c.getColumnIndex("vmove")
            val iWin = c.getColumnIndex("vwin")
            val iDraw = c.getColumnIndex("vdraw")
            val iLost = c.getColumnIndex("vlost")
            val iScore = c.getColumnIndex("vscore")
            while (c.moveToNext()) {
                val move = decodeMove(c.getInt(iMove), mirror) ?: continue
                val win = c.getInt(iWin).toFloat()
                val draw = c.getInt(iDraw).toFloat()
                val lost = c.getInt(iLost).toFloat()
                val total = win + draw + lost
                out += LocalBookMove(
                    move = move,
                    score = c.getInt(iScore),
                    winRate = if (total == 0f) 0f else win * 100f / total,
                    drawRate = if (total == 0f) 0f else draw * 100f / total,
                )
            }
        }
        return out
    }

    companion object {
        /** Pro t2.c.a：方格码 51..203（每行 16 跳） */
        private val SQUARE = intArrayOf(
            51, 52, 53, 54, 55, 56, 57, 58, 59,
            67, 68, 69, 70, 71, 72, 73, 74, 75,
            83, 84, 85, 86, 87, 88, 89, 90, 91,
            99, 100, 101, 102, 103, 104, 105, 106, 107,
            115, 116, 117, 118, 119, 120, 121, 122, 123,
            131, 132, 133, 134, 135, 136, 137, 138, 139,
            147, 148, 149, 150, 151, 152, 153, 154, 155,
            163, 164, 165, 166, 167, 168, 169, 170, 171,
            179, 180, 181, 182, 183, 184, 185, 186, 187,
            195, 196, 197, 198, 199, 200, 201, 202, 203,
        )
        /** Pro 方格码 → 标准 ICCS（Pro 序号 0=a9红底，标准 a0=红底） */
        private val SQUARE_TO_ICCS = SQUARE.mapIndexed { i, code ->
            val file = Position.FILE_NAMES[i % 9]
            val iccsRank = i / 9
            code to "$file$iccsRank"
        }.toMap()

        private fun fenChar(code: String): Char = when (code) {
            "wk" -> 'K'; "wa" -> 'A'; "wb" -> 'B'; "wn" -> 'N'
            "wr" -> 'R'; "wc" -> 'C'; "wp" -> 'P'
            "bk" -> 'k'; "ba" -> 'a'; "bb" -> 'b'; "bn" -> 'n'
            "br" -> 'r'; "bc" -> 'c'; "bp" -> 'p'
            else -> ' '
        }

        private fun pieceIndex(c: Char): Int = when (c) {
            'K' -> 0; 'A' -> 1; 'B' -> 2; 'N' -> 3; 'R' -> 4; 'C' -> 5; 'P' -> 6
            'k' -> 7; 'a' -> 8; 'b' -> 9; 'n' -> 10; 'r' -> 11; 'c' -> 12; 'p' -> 13
            else -> -1
        }

        /** Pro t2.c.b：board[rank][file]，rank0=红底线 */
        fun positionKey(board: Array<CharArray>, sideIsRed: Boolean, mirror: Boolean): Long {
            var j = 0L
            for (r in 0 until 10) {
                for (f in 0 until 9) {
                    val ch = board[r][f]
                    if (ch == ' ') continue
                    val p = pieceIndex(ch)
                    if (p < 0) continue
                    val file = if (mirror) 8 - f else f
                    j = j xor Zobrist.table[p * 256 + SQUARE[r * 9 + file]]
                }
            }
            // Pro: if (z) j ^= -6859497933297602728L  — z 即 side 含 "w"
            if (sideIsRed) j = j xor -6859497933297602728L
            return j
        }

        fun decodeMove(packed: Int, mirror: Boolean): String? {
            var from = packed shr 8
            var to = packed and 0xff
            if (mirror) {
                from = (14 - (from % 16)) or (from and -16)
                to = (to and 0xf0) or (14 - (to % 16))
            }
            val a = SQUARE_TO_ICCS[from] ?: return null
            val b = SQUARE_TO_ICCS[to] ?: return null
            return a + b
        }

        fun listBooks(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.extension.equals("obk", true) }?.toList() ?: emptyList()
    }
}
