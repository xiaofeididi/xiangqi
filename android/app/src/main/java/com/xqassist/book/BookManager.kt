package com.xqassist.book

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.xqassist.core.Notation
import com.xqassist.core.Position
import com.xqassist.engine.CloudBook
import java.io.File

data class BookEntry(
    val move: String,
    val score: Int,
    val winRate: Float = 0f,
    val drawRate: Float = 0f,
    val source: String = "",
) {
    fun chinese(pos: Position): String = try {
        Notation.moveToChinese(pos, move)
    } catch (_: Throwable) {
        move
    }
}

/**
 * 开局库管理：本地 .obk + chessdb 在线。
 * 对齐 Pro：开局阶段（前 15 步）优先查库，命中则显示/出子。
 */
object BookManager {
    private const val TAG = "Book"
    private const val PREFS = "book_manager"
    private const val KEY_MODE = "mode" // 0=chessdb 1=local
    private const val KEY_NAME = "name"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MAX_PLY = "max_ply"

    @Volatile
    var enabled: Boolean = true

    @Volatile
    var mode: Int = 0

    @Volatile
    var bookName: String = ""

    @Volatile
    var maxPly: Int = 15

    @Volatile
    var lastHit: BookEntry? = null

    @Volatile
    var lastHits: List<BookEntry> = emptyList()

    fun clearHit() {
        lastHit = null
        lastHits = emptyList()
    }

    private var local: LocalBook? = null
    private val cloud = CloudBook()

    fun booksDir(context: Context): File =
        File(context.filesDir, "books").apply { mkdirs() }

    fun loadPrefs(context: Context) {
        val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = p.getBoolean(KEY_ENABLED, true)
        mode = p.getInt(KEY_MODE, 0)
        bookName = p.getString(KEY_NAME, "") ?: ""
        maxPly = p.getInt(KEY_MAX_PLY, 15)
        if (mode == 1 && bookName.isNotBlank()) {
            openLocal(context, bookName)
        }
    }

    fun savePrefs(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_MODE, mode)
            .putString(KEY_NAME, bookName)
            .putInt(KEY_MAX_PLY, maxPly)
            .apply()
    }

    fun listLocal(context: Context): List<File> = LocalBook.listBooks(booksDir(context))

    fun openLocal(context: Context, name: String): Boolean {
        val f = File(booksDir(context), name)
        if (!f.exists()) {
            Log.w(TAG, "book not found: ${f.absolutePath}")
            return false
        }
        return try {
            local?.close()
            local = LocalBook(f.absolutePath)
            bookName = name
            mode = 1
            savePrefs(context)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "open book failed", t)
            false
        }
    }

    fun useCloud(context: Context) {
        local?.close()
        local = null
        mode = 0
        bookName = ""
        savePrefs(context)
    }

    fun close() {
        local?.close()
        local = null
    }

    /** 是否应在该局面查库（开局阶段；无精确步数时默认查） */
    fun shouldQuery(moveCount: Int = 0): Boolean = enabled && moveCount <= maxPly

    suspend fun query(
        context: Context,
        pos: Position,
        moveCount: Int = 0,
    ): List<BookEntry> {
        lastHit = null
        lastHits = emptyList()
        if (!enabled) return emptyList()

        val hits = mutableListOf<BookEntry>()
        val book = local
        if (mode == 1 && book != null) {
            try {
                val label = bookName.ifBlank { "本地库" }
                book.query(pos).forEach {
                    hits += BookEntry(it.move, it.score, it.winRate, it.drawRate, label)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "local query failed", t)
            }
        }
        if (hits.isEmpty()) {
            val fen = pos.toFen()
            when (val r = cloud.query(fen)) {
                is CloudBook.Result.Moves -> r.list.forEach {
                    hits += BookEntry(it.move, it.score, it.winrate.toFloat(), 0f, "云库")
                }
                is CloudBook.Result.Error -> Log.d(TAG, "cloud: ${r.message}")
            }
        }
        val sorted = hits.sortedByDescending { it.score }
        lastHits = sorted
        lastHit = sorted.firstOrNull()
        return sorted
    }

    fun summary(): String = when {
        !enabled -> "开局库关"
        mode == 1 && bookName.isNotBlank() -> "本地:$bookName"
        else -> "云库(chessdb)"
    }
}
