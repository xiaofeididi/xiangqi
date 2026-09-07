package com.xqassist

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
import com.xqassist.engine.UcciEngine
import com.xqassist.game.GameController
import com.xqassist.ui.BoardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 皮卡鱼象棋助手：对弈、持续分析、红黑方分析、翻转、FEN 编辑、云库切换 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: GameController
    private lateinit var board: BoardView
    private lateinit var status: TextView
    private lateinit var analysisPanel: TextView
    private lateinit var engineTab: Button
    private lateinit var cloudTab: Button
    private lateinit var analyzeButton: Button
    private lateinit var titleView: TextView

    private var engine: UcciEngine? = null
    private val engineMutex = Mutex()
    private val cloudBook = CloudBook()
    private var engineReady = false
    private var analysisMode = false
    private var analysisSide = ""
    private var bottomTab = TAB_ENGINE
    private var lastResult = EngineResult()

    private var thinkMs = 1000
    private var searchDepth = 0
    private var flipped = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GameController()
        buildUi()
        refreshUi()
        setStatus("正在启动皮卡鱼…")
        lifecycleScope.launch(Dispatchers.IO) {
            val file = EngineInstaller.install(this@MainActivity)
            if (file == null) {
                postStatus("皮卡鱼文件缺失")
                return@launch
            }
            val installed = UcciEngine(file, EngineInstaller.nnueFile(this@MainActivity), 2, 128)
            installed.start()
            withContext(Dispatchers.Main) {
                engine = installed
                engineReady = installed.isReady
                setStatus(if (engineReady) "皮卡鱼就绪，红方先行" else "皮卡鱼启动失败，请查看日志")
                maybeAutoMove()
            }
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F7F1E5"))
            setPadding(dp(14), dp(8), dp(14), dp(10))
        }

        titleView = TextView(this).apply {
            text = "皮卡鱼象棋助手"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#3A2A1A"))
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        }

        val menuBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val menus = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun item(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            setPadding(dp(10), dp(2), dp(10), dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(7)
            }
            setOnClickListener { action() }
        }

        menus.addView(item("新局") { newGame() })
        menus.addView(item("悔棋") { undo() })
        menus.addView(item("红方分析") { analyzeFor("w") })
        menus.addView(item("黑方分析") { analyzeFor("b") })
        analyzeButton = item("开始分析") { toggleAnalysisMode() }
        menus.addView(analyzeButton)
        menus.addView(item("立即出招") { playBestNow() })
        menus.addView(item("翻转") { flipped = !flipped; refreshUi() })
        menus.addView(item("编辑") { editDialog() })
        menus.addView(item("设置") { settingsDialog() })
        menuBar.addView(menus)

        board = BoardView(this).apply {
            controller = this@MainActivity.controller
            listener = { rank, file -> onBoardTap(rank, file) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.92f).apply {
                setMargins(0, dp(4), 0, dp(4))
            }
        }

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#E9D9BC"))
            setPadding(dp(6), dp(3), dp(6), dp(3))
        }
        engineTab = bottomTabButton("皮卡鱼") { switchTab(TAB_ENGINE) }
        cloudTab = bottomTabButton("云库") { switchTab(TAB_CLOUD) }
        tabs.addView(engineTab)
        tabs.addView(cloudTab)

        analysisPanel = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#3F3125"))
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(Color.parseColor("#FFF8E7"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.28f)
            movementMethod = null
        }

        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#6B543D"))
            setPadding(dp(8), dp(2), dp(8), dp(4))
            setSingleLine(false)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        root.addView(titleView)
        root.addView(menuBar)
        root.addView(board)
        root.addView(tabs)
        root.addView(analysisPanel)
        root.addView(status)
        setContentView(root)
        switchTab(TAB_ENGINE)
    }

    private fun bottomTabButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 12f
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action() }
    }

    private fun switchTab(tab: Int) {
        bottomTab = tab
        if (tab == TAB_ENGINE) {
            engineTab.setBackgroundColor(Color.parseColor("#D7B98A"))
            cloudTab.setBackgroundColor(Color.parseColor("#F0E7D6"))
            renderEnginePanel(lastResult, live = analysisMode)
        } else {
            engineTab.setBackgroundColor(Color.parseColor("#F0E7D6"))
            cloudTab.setBackgroundColor(Color.parseColor("#D7B98A"))
            analysisPanel.text = "云库加载中…"
            queryCloud()
        }
    }

    private fun onBoardTap(rank: Int, file: Int) {
        if (controller.editMode) {
            setStatus(controller.editTap(rank, file))
            refreshUi()
            return
        }

        val selected = controller.selected
        if (selected != null && (selected.fromRank != rank || selected.fromFile != file)) {
            val from = selected
            controller.clearSelection()
            if (controller.tryHumanMove(from.fromRank, from.fromFile, rank, file)) {
                refreshUi()
                setStatus("你走了 ${lastChinese()}\n${statusText()}")
                maybeAutoMove()
            } else {
                setStatus("这步不合法")
                refreshUi()
            }
            return
        }

        val piece = controller.pos.pieceAt(rank, file)
        if (piece != null && piece.first().toString() == controller.humanSide && controller.canMove(controller.humanSide)) {
            controller.select(rank, file)
        } else {
            controller.clearSelection()
        }
        refreshUi()
    }

    private fun maybeAutoMove() {
        if (!engineReady || !controller.autoReply || controller.thinking || controller.isGameOver) return
        val aiSide = if (controller.humanSide == "w") "b" else "w"
        if (controller.sideToMove != aiSide) return
        analyzeAndMove(aiSide)
    }

    private fun analyzeAndMove(side: String) {
        controller.thinking = true
        setStatus("${sideName(side)}思考中…")
        refreshUi()
        val fen = controller.fen
        lifecycleScope.launch(Dispatchers.IO) {
            val result = requestEngine(fen, side)
            withContext(Dispatchers.Main) {
                controller.thinking = false
                val applied = controller.applyEngineMove(result.bestmove)
                lastResult = result
                if (applied) {
                    setStatus("${sideName(side)}走了 ${lastChinese()}\n${formatResult(result)}\n${statusText()}")
                } else {
                    setStatus("引擎着法无效：${result.bestmove.ifBlank { "空" }}")
                }
                renderEnginePanel(result, live = analysisMode)
                refreshUi()
                if (analysisMode) continueAnalysis()
            }
        }
    }

    private fun analyzeFor(side: String) {
        if (!engineReady) {
            toast("皮卡鱼还没就绪")
            return
        }
        if (controller.thinking) {
            toast("当前正在分析")
            return
        }
        val fen = controller.fen
        setStatus("${sideName(side)}分析中…")
        lifecycleScope.launch(Dispatchers.IO) {
            val result = requestEngine(fen, side)
            withContext(Dispatchers.Main) {
                lastResult = result
                controller.hintFromIccs(result.bestmove)
                setStatus("${sideName(side)}建议：${result.chinese(controller.pos)}\n${formatResult(result)}")
                renderEnginePanel(result, live = false)
                refreshUi()
            }
        }
    }

    private fun toggleAnalysisMode() {
        if (analysisMode) {
            analysisMode = false
            analysisSide = ""
            analyzeButton.text = "开始分析"
            sendStop()
            setStatus("持续分析已停止")
            return
        }
        if (!engineReady) {
            toast("皮卡鱼还没就绪")
            return
        }
        analysisMode = true
        analysisSide = controller.sideToMove
        analyzeButton.text = "停止分析"
        setStatus("持续分析中：${sideName(analysisSide)}")
        continueAnalysis()
    }

    private fun continueAnalysis() {
        if (!analysisMode || controller.thinking) return
        val fen = controller.fen
        val side = controller.sideToMove
        lifecycleScope.launch(Dispatchers.IO) {
            val result = requestEngine(fen, side)
            withContext(Dispatchers.Main) {
                lastResult = result
                renderEnginePanel(result, live = true)
                if (analysisMode && result.bestmove.isNotBlank()) {
                    controller.hintFromIccs(result.bestmove)
                    refreshUi()
                }
                if (analysisMode) continueAnalysis()
            }
        }
    }

    private fun playBestNow() {
        if (!engineReady || controller.thinking || controller.isGameOver) {
            toast("当前不能出招")
            return
        }
        analyzeAndMove(controller.sideToMove)
    }

    private suspend fun requestEngine(fen: String, side: String): EngineResult = engineMutex.withLock {
        val current = engine ?: return@withLock EngineResult()
        if (!current.isReady || fen != controller.fen) return@withLock EngineResult()
        current.analyze(
            fen,
            movetimeMs = thinkMs,
            depth = searchDepth,
            onInfo = { partial ->
                runOnUiThread {
                    if (bottomTab == TAB_ENGINE) renderEnginePanel(partial, live = true)
                }
            },
        )
    }

    private fun sendStop() {
        lifecycleScope.launch(Dispatchers.IO) { engine?.send("stop") }
    }

    private fun queryCloud() {
        val fen = controller.fen
        lifecycleScope.launch(Dispatchers.IO) {
            val result = cloudBook.query(fen)
            withContext(Dispatchers.Main) {
                if (bottomTab != TAB_CLOUD) return@withContext
                analysisPanel.text = when (result) {
                    is CloudBook.Result.Moves -> cloudBook.renderTop(result.list, controller.pos)
                    is CloudBook.Result.Error -> result.message
                }
            }
        }
    }

    private fun editDialog() {
        val options = arrayOf("进入编辑模式", "选红子", "选黑子", "删除模式 开/关", "清空棋盘", "红方行棋", "黑方行棋", "导入 FEN", "导出 FEN", "完成编辑")
        AlertDialog.Builder(this).setTitle("编辑棋局").setItems(options) { _, which ->
            when (which) {
                0 -> { controller.startEditMode(); setStatus("编辑模式：先选棋子，再点棋盘放置") }
                1 -> { selectEditPiece("w") }
                2 -> { selectEditPiece("b") }
                3 -> { controller.editErase = !controller.editErase; setStatus(if (controller.editErase) "删除模式开启" else "删除模式关闭") }
                4 -> { controller.clearBoard(); setStatus("已清空棋盘") }
                5 -> { controller.setSideToMove("w"); setStatus("已设为红方行棋") }
                6 -> { controller.setSideToMove("b"); setStatus("已设为黑方行棋") }
                7 -> importFenDialog()
                8 -> exportFenDialog()
                9 -> { controller.exitEditMode(); setStatus("编辑完成\n${statusText()}") }
            }
            refreshUi()
        }.show()
    }

    private fun selectEditPiece(side: String) {
        val names = if (side == "w") arrayOf("帅", "仕", "相", "马", "车", "炮", "兵") else arrayOf("将", "士", "象", "马", "车", "炮", "卒")
        val types = arrayOf("k", "a", "b", "n", "r", "c", "p")
        AlertDialog.Builder(this).setTitle(if (side == "w") "选择红子" else "选择黑子").setItems(names) { _, which ->
            controller.editPiece = side + types[which]
            setStatus("已选择${controller.pieceText(controller.editPiece!!)}，点击棋盘放置")
        }.show()
    }

    private fun importFenDialog() {
        val input = EditText(this).apply { setText(controller.exportFen()) }
        AlertDialog.Builder(this).setTitle("导入 FEN").setView(input).setPositiveButton("导入") { _, _ ->
            val ok = controller.importFen(input.text.toString())
            setStatus(if (ok) "FEN 导入成功" else "FEN 格式错误")
            refreshUi()
        }.setNegativeButton("取消", null).show()
    }

    private fun exportFenDialog() {
        val input = EditText(this).apply { setText(controller.exportFen()); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("导出 FEN").setView(input)
            .setPositiveButton("复制") { _, _ -> copyText(controller.exportFen()); toast("已复制 FEN") }
            .setNegativeButton("关闭", null).show()
    }

    private fun settingsDialog() {
        val timeLabels = arrayOf("0.5 秒", "1 秒", "2 秒", "5 秒", "10 秒")
        val timeValues = intArrayOf(500, 1000, 2000, 5000, 10000)
        val depthLabels = arrayOf("不限", "6 层", "8 层", "10 层", "12 层")
        val depthValues = intArrayOf(0, 6, 8, 10, 12)
        val options = arrayOf(
            "切换执子", "AI 回招：${if (controller.autoReply) "开" else "关"}",
            "思考时间：当前 ${thinkMs}ms", "搜索深度：当前 ${if (searchDepth == 0) "不限" else searchDepth.toString()}",
        )
        AlertDialog.Builder(this).setTitle("设置").setItems(options) { _, which ->
            when (which) {
                0 -> { controller.humanSide = if (controller.humanSide == "w") "b" else "w"; newGame() }
                1 -> controller.autoReply = !controller.autoReply
                2 -> AlertDialog.Builder(this).setTitle("思考时间").setItems(timeLabels) { _, ti -> thinkMs = timeValues[ti] }.show()
                3 -> AlertDialog.Builder(this).setTitle("搜索深度").setItems(depthLabels) { _, di -> searchDepth = depthValues[di] }.show()
            }
            refreshUi()
        }.show()
    }

    private fun newGame() {
        controller.newGame()
        analysisMode = false
        analyzeButton.text = "开始分析"
        setStatus("新局开始，${if (controller.humanSide == "w") "红方" else "黑方"}先行")
        refreshUi()
        maybeAutoMove()
    }

    private fun undo() {
        if (!controller.undo()) toast("没有可悔的棋")
        selectedClear()
        refreshUi()
        setStatus("已悔棋\n${statusText()}")
        maybeAutoMove()
    }

    private fun selectedClear() {
        controller.clearSelection()
    }

    private fun refreshUi() {
        board.controller = controller
        board.selected = controller.selected
        board.flipped = flipped
        board.invalidate()
    }

    private fun renderEnginePanel(result: EngineResult, live: Boolean) {
        lastResult = result
        if (bottomTab != TAB_ENGINE) return
        val lines = mutableListOf(if (live) "持续分析中…" else "皮卡鱼建议")
        if (result.bestmove.isNotBlank()) {
            lines += "最佳：${result.chinese(controller.pos)}  [${result.bestmove}]"
        }
        lines += "评分：${result.scoreText()} · 深度：${result.depth}"
        if (result.pv.isNotEmpty()) lines += "线路：${result.pv.take(5).joinToString(" ")}"
        lines += "设置：${timeText()} · ${depthText()} · ${if (engineReady) "皮卡鱼" else "未启动"}"
        analysisPanel.text = lines.joinToString("\n")
    }

    private fun formatResult(result: EngineResult): String =
        "深度 ${result.depth} · 评分 ${result.scoreText()}"

    private fun lastChinese(): String {
        val pre = controller.lastPreMove ?: controller.pos
        val move = controller.lastMove ?: return ""
        return Notation.moveToChinese(pre, move.iccs())
    }

    private fun statusText(): String = when (val winner = controller.winner()) {
        "w" -> "红方胜"
        "b" -> "黑方胜"
        else -> "轮到${sideName(controller.sideToMove)}"
    }

    private fun sideName(side: String): String = if (side == "w") "红方" else "黑方"

    private fun timeText(): String = when (thinkMs) {
        500 -> "0.5 秒"
        2000 -> "2 秒"
        5000 -> "5 秒"
        10000 -> "10 秒"
        else -> "1 秒"
    }

    private fun depthText(): String = if (searchDepth == 0) "不限深度" else "$searchDepth 层"

    private fun setStatus(text: String) {
        status.text = text
    }

    private fun postStatus(text: String) {
        runOnUiThread { setStatus(text) }
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun copyText(value: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FEN", value))
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisMode = false
        engine?.stop()
        engine = null
    }

    companion object {
        private const val TAB_ENGINE = 0
        private const val TAB_CLOUD = 1
    }
}