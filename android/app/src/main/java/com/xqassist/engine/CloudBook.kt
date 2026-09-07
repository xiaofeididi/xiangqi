package com.xqassist.engine

import com.xqassist.core.Notation
import com.xqassist.core.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 云库开局库条目 */
data class BookMove(
    val move: String,
    val winCount: Long = 0,
    val drawCount: Long = 0,
    val lossCount: Long = 0,
) {
    val games: Long get() = winCount + drawCount + lossCount
    val winRate: Double get() = if (games == 0L) 0.0 else winCount.toDouble() / games
}

/** chessdb.cn 云库客户端：action=queryall&board=<FEN> */
class CloudBook(private val baseUrl: String = "http://www.chessdb.cn/cdb.php") {

    suspend fun query(fen: String, timeoutMs: Int = 6000): List<BookMove> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(fen, "UTF-8")
            val url = URL("$baseUrl?action=queryall&board=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.inputStream.bufferedReader().use { source ->
                    parse(source.readText())
                }
            } finally {
                conn.disconnect()
            }
        }

    fun parse(body: String): List<BookMove> {
        val out = mutableListOf<BookMove>()
        for (line in body.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            val move = Regex("([a-i][0-9][a-i][0-9])").find(parts[0])?.groupValues?.get(1) ?: continue
            fun num(k: String): Long =
                parts.firstOrNull { it.startsWith("$k:") }
                    ?.substringAfter(":")?.toLongOrNull() ?: 0L
            out.add(BookMove(move, num("win"), num("draw"), num("loss")))
        }
        return out
    }

    fun renderTop(moves: List<BookMove>, pos: Position, limit: Int = 3): String {
        val top = moves.sortedByDescending { it.winRate }.take(limit)
        if (top.isEmpty()) return "云库无此局面数据"
        return top.joinToString("\n") {
            val cn = Notation.moveToChinese(pos, it.move)
            "$cn  胜率${(it.winRate * 100).toInt()}%（${it.games}局）"
        }
    }
}