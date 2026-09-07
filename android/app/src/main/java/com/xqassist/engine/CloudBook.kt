package com.xqassist.engine

import com.xqassist.core.Notation
import com.xqassist.core.Position
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 云库开局库条目（chessdb.cn /chessdb.php?action=queryall） */
data class BookMove(
    val move: String,
    val score: Int = 0,
    val rank: Int = 0,
    val winrate: Double = 0.0,
    val note: String = "",
)

/**
 * 中国象棋云库客户端：action=queryall&board=<FEN>
 * 返回 "move:c3c4,score:1,rank:2,note:! (44-02),winrate:50.08|..." 或 invalid board / unknown
 */
class CloudBook(private val baseUrl: String = "http://www.chessdb.cn/chessdb.php") {

    sealed class Result {
        data class Moves(val list: List<BookMove>) : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun query(fen: String, timeoutMs: Int = 8000): Result = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(fen, "UTF-8")
            val url = URL("$baseUrl?action=queryall&board=$encoded")
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("User-Agent", "xq-assist/1.0")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body.trim())
        } catch (t: Throwable) {
            android.util.Log.w("CloudBook", "query failed", t)
            Result.Error("网络错误：${t.message ?: t.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    fun parse(body: String): Result {
        return when {
            body.startsWith("invalid board") -> Result.Error("云库判定局面无效（FEN 不合法）")
            body.startsWith("unknown") -> Result.Moves(emptyList())
            body.startsWith("checkmate") -> Result.Error("当前局面已被将死")
            body.startsWith("stalemate") -> Result.Error("当前局面困毙")
            body.isBlank() -> Result.Error("云库无响应")
            else -> {
                val moves = body.split('|').mapNotNull { entry ->
                    val fields = entry.split(',')
                    val move = fields.firstOrNull { it.startsWith("move:") }?.substringAfter(':')
                        ?: return@mapNotNull null
                    fun num(prefix: String) =
                        fields.firstOrNull { it.startsWith(prefix) }?.substringAfter(':')
                    BookMove(
                        move = move,
                        score = num("score:")?.toIntOrNull() ?: 0,
                        rank = num("rank:")?.toIntOrNull() ?: 0,
                        winrate = num("winrate:")?.toDoubleOrNull() ?: 0.0,
                        note = num("note:") ?: "",
                    )
                }
                Result.Moves(moves)
            }
        }
    }

    fun renderTop(moves: List<BookMove>, pos: Position, limit: Int = 5): String {
        val top = moves.sortedWith(compareByDescending<BookMove> { it.rank }.thenByDescending { it.score }).take(limit)
        if (top.isEmpty()) return "云库无此局面数据"
        return top.mapIndexed { i, m ->
            val cn = Notation.moveToChinese(pos, m.move)
            "${i + 1}. $cn  分${m.score}  胜率${String.format("%.1f", m.winrate)}%"
        }.joinToString("\n")
    }
}