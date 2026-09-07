package com.xqassist

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xqassist.core.Notation
import com.xqassist.core.Quad
import com.xqassist.engine.CloudBook
import com.xqassist.engine.EngineInstaller
import com.xqassist.engine.EngineResult
import com.xqassist.engine.FallbackEngine
import com.xqassist.engine.UcciEngine
import com.xqassist.game.GameController
import com.xqassist.ui.BoardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 本地人机对练主页面：新局/悔棋/皮卡鱼提示/云库/设置，棋盘自绘并显示最优连线 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: GameController
    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var titleView: TextView
    private var engine: UcciEngine? = null
    private var fallbackEngine: FallbackEngine? = null
    private var engineReady = false
    private val engineMutex = Mutex()
    private val cloudBook = CloudBook()
    private var thinking = false
    private var selected: Quad? = null
    private var thinkMs = 1000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GameController()
        buildUi()
        refreshBoard()
        setStatus("正在启动引擎…")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = EngineInstaller.install(this@MainActivity)
                if (file != null) {
                    val e = UcciEngine(file, EngineInstaller.nnueFile(this@MainActivity), 2, 128)
                    e.start()
                    if (e.isReady) {
                        engine = e
                        fallbackEngine = null
                        engineReady = true
                        android.util.Log.i(TAG, "Pikafish ready")
                    } else {
                        e.stop()
                        android.util.Log.w(TAG, "Pikafish not ready, using fallback")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "Pikafish startup failed", t)
            }

            if (!engineReady) {
                fallbackEngine = FallbackEngine()
                engineReady = true
            }
            withContext(Dispatchers.Main) {
                setStatus(if (engine != null) "皮卡鱼已就绪，红方先行" else "内置引擎已就绪，红方先行")
                maybeAiMove()
            }
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF5, 0xF0, 0xE4))
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
        }

        titleView = TextView(this).apply {
            text = "皮卡鱼 · 本地对练"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(0x33, 0x22, 0x11))
            gravity = Gravity.CENTER
            setPadding(0, (6 * density).toInt(), 0, (8 * density).toInt())
        }

        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        fun menuButton(text: String, action: () -> Unit): Button =
            Button(this).apply {
                this.text = text
                isAllCaps = false
                setPadding((12 * density).toInt(), (4 * density).toInt(), (12 * density).toInt(), (4 * density).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
                setOnClickListener { action() }
            }

        barRow.addView(menuButton("新局") { newGame() })
        barRow.addView(menuButton("悔棋") { undoMove() })
        barRow.addView(menuButton("皮卡鱼") { askHint() })
        barRow.addView(menuButton("云库") { queryCloud() })
        barRow.addView(menuButton("设置") { showSettings() })
        bar.addView(barRow)

        board = BoardView(this).apply {
            controller = this@MainActivity.controller
            listener = { rank, file -> onBoardTap(rank, file) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ).apply { setMargins(0, (6 * density).toInt(), 0, (6 * density).toInt()) }
        }

        status = TextView(this).apply {
            text = "请走子"
            textSize = 14f
            setTextColor(Color.rgb(0x44, 0x33, 0x22))
            setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.rgb(0xFF, 0xFA, 0xEC))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(titleView)
        root.addView(bar)
        root.addView(board)
        root.addView(status)
        setContentView(root)
    }

    private fun onBoardTap(rank: Int, file: Int) {
        val sel = selected
        if (sel != null && (sel.fromRank != rank || sel.fromFile != file)) {
            val ok = controller.tryHumanMove(sel.fromRank, sel.fromFile, rank, file)
            selected = null
            if (ok) {
                refreshBoard()
                val moveText = controller.lastMove?.let {
                    Notation.moveToChinese(controller.lastPreMove ?: controller.pos, it.iccs())
                } ?: ""
                setStatus("你走了 $moveText\n${if (controller.isGameOver) "对局结束" else "皮卡鱼思考中…"}")
                maybeAiMove()
                return
            }
            Toast.makeText(this, "这步不合法", Toast.LENGTH_SHORT).show()
            refreshBoard()
            return
        }

        val piece = controller.pos.pieceAt(rank, file)
        if (piece != null && piece[0].toString() == controller.humanSide && controller.canMove(controller.humanSide)) {
            selected = Quad(rank, file, rank, file)
        } else {
            selected = null
        }
        refreshBoard()
    }

    private fun maybeAiMove() {
        if (!engineReady || !controller.autoReply || thinking || controller.isGameOver) return
        val aiSide = if (controller.humanSide == "w") "b" else "w"
        if (controller.sideToMove != aiSide) return
        thinking = true
        controller.thinking = true
        refreshBoard()
        val fen = controller.pos.toFen()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = analyzeNow(fen, thinkMs)
            withContext(Dispatchers.Main) {
                thinking = false
                controller.thinking = false
                val applied = controller.applyEngineMove(result.bestmove)
                if (applied) {
                    val cn = controller.lastMove?.let {
                        Notation.moveToChinese(controller.lastPreMove ?: controller.pos, it.iccs())
                    } ?: ""
                    setStatus("${if (engine != null) "皮卡鱼" else "内置引擎"}走了 $cn\n${formatEngineResult(result)}\n${gameStatusText()}")
                } else {
                    setStatus(gameStatusText() + "\n引擎着法无效：${result.bestmove}")
                }
                refreshBoard()
            }
        }
    }

    private fun askHint() {
        if (!engineReady || thinking || controller.isGameOver) {
            Toast.makeText(this, "引擎还未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        if (thinking || controller.isGameOver) return
        thinking = true
        setStatus("正在请求皮卡鱼连线…")
        val fen = controller.pos.toFen()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = analyzeNow(fen, thinkMs)
            withContext(Dispatchers.Main) {
                thinking = false
                controller.hintFromIccs(result.bestmove)
                setStatus("${if (engine != null) "皮卡鱼" else "内置引擎"}建议：${result.chinese(controller.pos)}\n${formatEngineResult(result)}")
                refreshBoard()
            }
        }
    }

    private fun queryCloud() {
        val fen = controller.pos.toFen()
        setStatus("正在查询云库…")
        lifecycleScope.launch {
            try {
                val moves = cloudBook.query(fen)
                setStatus(cloudBook.renderTop(moves, controller.pos))
            } catch (t: Throwable) {
                setStatus("云库查询失败：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun undoMove() {
        val n = controller.undo()
        if (n == 0) {
            Toast.makeText(this, "没有可悔的棋", Toast.LENGTH_SHORT).show()
        }
        selected = null
        refreshBoard()
        setStatus("已悔棋\n${gameStatusText()}")
        maybeAiMove()
    }

    private fun newGame() {
        controller.newGame()
        selected = null
        refreshBoard()
        setStatus("新局开始，${if (controller.humanSide == "w") "你执红" else "你执黑"}\n${gameStatusText()}")
        maybeAiMove()
    }

    private fun showSettings() {
        val items = arrayOf("切换执子", "AI 回招：开", "AI 回招：关", "思考 0.5s", "思考 1s", "思考 2s")
        AlertDialog.Builder(this)
            .setTitle("设置")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        controller.humanSide = if (controller.humanSide == "w") "b" else "w"
                        newGame()
                    }
                    1 -> { controller.autoReply = true; setStatus("已开启 AI 回招") }
                    2 -> { controller.autoReply = false; setStatus("已关闭 AI 回招") }
                    3 -> { thinkMs = 500; setStatus("思考时间 0.5 秒") }
                    4 -> { thinkMs = 1000; setStatus("思考时间 1 秒") }
                    5 -> { thinkMs = 2000; setStatus("思考时间 2 秒") }
                }
            }
            .show()
    }

    private fun formatEngineResult(r: EngineResult): String {
        val score = when {
            r.mateIn != null -> "杀棋 ${r.mateIn}"
            r.scoreCp != null -> (r.scoreCp / 100.0).let { String.format("%.2f 兵", it) }
            else -> "无评分"
        }
        return "深度 ${r.depth} · 评分 $score"
    }

    private suspend fun analyzeNow(fen: String, thinkMs: Int): EngineResult =
        engineMutex.withLock {
            withContext(Dispatchers.IO) {
                val external = engine
                if (external != null) external.analyze(fen, thinkMs)
                else fallbackEngine?.analyze(controller.pos) ?: EngineResult()
            }
        }

    private fun gameStatusText(): String {
        val side = if (controller.sideToMove == "w") "红方" else "黑方"
        return when (val w = controller.winner()) {
            "w" -> "红方胜"
            "b" -> "黑方胜"
            else -> "轮到 $side"
        }
    }

    private fun refreshBoard() {
        board.controller = controller
        board.selected = selected
        board.invalidate()
    }

    private fun setStatus(text: String) {
        status.text = text
    }

    companion object {
        private const val TAG = "XqAssist"
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        engine = null
    }
}
