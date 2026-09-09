package com.xqassist

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.xqassist.core.Notation
import com.xqassist.connection.LiveLinkService
import com.xqassist.engine.CloudBook
import com.xqassist.engine.BookMove
import com.xqassist.engine.EngineInstaller
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
import com.xqassist.game.GameController
import com.xqassist.overlay.OverlayService
import com.xqassist.ui.BoardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 皮卡鱼象棋助手：仿收费端主界面，顶部图标工具栏 + 棋盘 + 引擎/开局库/棋谱页签 */
class MainActivity : AppCompatActivity(), OverlayService.Actions {

    private lateinit var controller: GameController
    private lateinit var board: BoardView
    private lateinit var enginePage: LinearLayout
    private lateinit var openingPage: LinearLayout
    private lateinit var engineScroll: ScrollView
    private lateinit var openingScroll: ScrollView
    private lateinit var gamePage: ScrollView
    private lateinit var settingsPage: LinearLayout
    private lateinit var editPanel: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var pages: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var moveText: TextView
    private lateinit var engineTab: Button
    private lateinit var openingTab: Button
    private lateinit var gameTab: Button
    private lateinit var settingsTab: Button
    private lateinit var analysisButton: Button
    private lateinit var engineRedButton: Button
    private lateinit var engineBlackButton: Button

    private var engine: UcciEngine? = null
    private val engineMutex = Mutex()
    private val cloudBook = CloudBook()
    private var engineReady = false
    private var lastResult = EngineResult()
    private var lastLive = false
    private var analysisMode = false
    private val engineSides = mutableSetOf<String>()
    private var bottomTab = TAB_ENGINE
    private var statusMessage = "正在启动皮卡鱼…"

    private var thinkMs = 3000
    private var searchDepth = 0
    private var multiPv = 5
    private var flipped = false
    private var overlayOn = false

    private var displayCloud = true
    private var executeCloud = false
    private var backgroundThink = true
    private var playSound = true
    private var showArrowHint = true
    private var overlayAlpha = 1f

    private var cloudMoves = listOf<BookMove>()
    private var cloudLoading = false
    private var cloudMessage = "开局库"
    private var cloudFen = ""

    /** 每次局面变更后递增，用于丢弃晚到的旧分析 */
    private var positionToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OverlayService.actions = this
        controller = GameController()
        buildUi()
        refreshUi()
        startEngine()
    }

    private fun startEngine() {
        lifecycleScope.launch(Dispatchers.IO) {
            val file = EngineInstaller.install(this@MainActivity)
        if (file == null) {
            statusMessage = "皮卡鱼文件缺失"
            withContext(Dispatchers.Main) {
                renderInfo()
            }
            return@launch
        }
            val installed = UcciEngine(file, EngineInstaller.nnueFile(this@MainActivity), 2, 128)
            installed.start()
            withContext(Dispatchers.Main) {
                engine = installed
                engineReady = installed.isReady
                statusMessage = if (engineReady) "皮卡鱼就绪 · 红方先行" else "皮卡鱼启动失败"
                renderInfo()
                maybeAutoMove()
            }
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E8D5A9"))
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        fun tool(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(2) }
            setOnClickListener { action() }
        }
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(tool("菜单") { menuDialog() })
        row1.addView(tool("新局") { newGame() })
        row1.addView(tool("编辑") { editDialog() })
        row1.addView(tool("翻转") { flipped = !flipped; refreshUi() })
        row1.addView(tool("悬浮窗") { toggleOverlay() })
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        engineRedButton = tool("引擎执红") { setEngineSide("w") }
        engineBlackButton = tool("引擎执黑") { setEngineSide("b") }
        analysisButton = tool("分析模式") { toggleAnalysisMode() }
        row2.addView(engineRedButton)
        row2.addView(engineBlackButton)
        row2.addView(analysisButton)
        row2.addView(tool("立即出招") { playBestNow() })
        row2.addView(tool("换招") { forceChangeMove() })
        toolbar.addView(row1)
        toolbar.addView(row2)

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#D9E6F2"))
        }
        fun nav(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 15f
            setPadding(0, 0, 0, 0)
            setBackgroundColor(Color.parseColor("#D9E6F2"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { action() }
        }
        navBar.addView(nav("开局") { browseFirst() })
        navBar.addView(nav("后退") { browsePrevious() })
        navBar.addView(nav("前进") { browseNext() })
        navBar.addView(nav("终局") { browseLast() })
        navBar.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))

        board = BoardView(this).apply {
            controller = this@MainActivity.controller
            listener = { rank, file -> onBoardTap(rank, file) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.96f)
        }

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#F7F7F7"))
        }
        engineTab = tabButton("引擎") { switchTab(TAB_ENGINE) }
        openingTab = tabButton("开局库") { switchTab(TAB_OPENING) }
        gameTab = tabButton("棋谱") { switchTab(TAB_GAME) }
        tabBar.addView(engineTab)
        tabBar.addView(openingTab)
        tabBar.addView(gameTab)
        settingsTab = tabButton("设置") { switchTab(TAB_SETTINGS) }
        tabBar.addView(settingsTab)

        enginePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E7"))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        openingPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E7"))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        moveText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        gamePage = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFF8E7"))
            addView(moveText)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFF8E7"))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        settingsScroll = ScrollView(this).apply {
            addView(settingsPage)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val toPx: (Int) -> Int = { value -> (value * resources.displayMetrics.density).toInt() }
        buildEnginePage(toPx)
        buildOpeningPage(toPx)
        buildSettingsPage(toPx)

        editPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#EFEFEF"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        buildEditPanel()

        pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.38f)
        }
        engineScroll = ScrollView(this).apply { addView(enginePage); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        openingScroll = ScrollView(this).apply { addView(openingPage); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        pages.addView(engineScroll)
        pages.addView(openingScroll)
        pages.addView(gamePage)
        pages.addView(settingsScroll)

        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(board)
        root.addView(editPanel)
        root.addView(navBar)
        root.addView(tabBar)
        root.addView(pages)
        setContentView(root)
        switchTab(TAB_ENGINE)
        renderInfo()
    }

    private fun buildEnginePage(toPx: (Int) -> Int) {
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(check("显示云库", displayCloud) { _, value -> displayCloud = value; if (value) queryCloud() })
        controls.addView(check("执行云库", executeCloud) { _, value -> executeCloud = value })
        enginePage.addView(controls)

        val switches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        switches.addView(check("后台思考", backgroundThink) { _, value -> backgroundThink = value })
        switches.addView(check("音效", playSound) { _, value -> playSound = value })
        switches.addView(check("箭头", showArrowHint) { _, value ->
            showArrowHint = value
            board.showArrow = value
            refreshUi()
        })
        enginePage.addView(switches)
    }

    private fun buildOpeningPage(toPx: (Int) -> Int) {
        openingPage.removeAllViews()
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, toPx(2), 0, toPx(2))
        }
        controls.addView(check("显示云库", displayCloud) { _, value -> displayCloud = value; if (value) queryCloud() })
        controls.addView(check("执行云库", executeCloud) { _, value -> executeCloud = value })
        openingPage.addView(controls)
    }

    private fun buildSettingsPage(toPx: (Int) -> Int) {
        settingsPage.removeAllViews()
        val depthLabels = listOf("不限", "6 层", "8 层", "10 层", "12 层", "14 层", "16 层", "18 层", "20 层", "22 层", "24 层")
        val depthValues = listOf(0, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24)
        val depthRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, toPx(3), 0, toPx(3))
        }
        depthRow.addView(TextView(this).apply { text = "搜索深度："; textSize = 13f })
        depthLabels.forEachIndexed { index, label ->
            depthRow.addView(choiceButton(label, depthValues[index] == searchDepth) {
                searchDepth = depthValues[index]
                buildSettingsPage(toPx)
            })
        }
        settingsPage.addView(depthRow)

        val thinkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, toPx(3), 0, toPx(3))
        }
        thinkRow.addView(TextView(this).apply { text = "思考时间："; textSize = 13f })
        val thinkInput = EditText(this).apply {
            setText((thinkMs / 1000).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(toPx(72), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        thinkInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) setThinkSeconds(thinkInput.text.toString()) }
        thinkRow.addView(thinkInput)
        thinkRow.addView(TextView(this).apply { text = " 秒"; textSize = 13f })
        settingsPage.addView(thinkRow)

        val candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, toPx(3), 0, toPx(3))
        }
        candidateRow.addView(TextView(this).apply { text = "候选着法："; textSize = 13f })
        for (count in 1..5) {
            candidateRow.addView(choiceButton("$count", multiPv == count) {
                multiPv = count
                buildSettingsPage(toPx)
            })
        }
        settingsPage.addView(candidateRow)

        val toggles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, toPx(5), 0, toPx(5))
        }
        toggles.addView(check("后台思考", backgroundThink) { _, value -> backgroundThink = value })
        toggles.addView(check("音效", playSound) { _, value -> playSound = value })
        toggles.addView(check("箭头", showArrowHint) { _, value ->
            showArrowHint = value
            refreshUi()
        })
        settingsPage.addView(toggles)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, toPx(4), 0, 0)
        }
        actionRow.addView(actionButton("悔棋") { undo() })
        actionRow.addView(actionButton("悬浮窗") { toggleOverlay() })
        settingsPage.addView(actionRow)
    }

    private fun choiceButton(label: String, active: Boolean, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(if (active) Color.parseColor("#E3F2FD") else Color.parseColor("#F0F0F0"))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
        setOnClickListener { action() }
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 13f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 4 }
        setOnClickListener { action() }
    }

    private fun check(label: String, value: Boolean, changed: (CompoundButton, Boolean) -> Unit): CheckBox =
        CheckBox(this).apply {
            text = label
            isChecked = value
            textSize = 13f
            setOnCheckedChangeListener { button, checked -> changed(button, checked) }
        }

    private fun setThinkSeconds(value: String) {
        val number = value.toIntOrNull()?.coerceIn(1, 60) ?: 3
        thinkMs = number * 1000
        statusMessage = "思考时间已设为 $number 秒"
        renderInfo()
    }

    private fun tabButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action() }
    }

    private fun switchTab(tab: Int) {
        bottomTab = tab
        val active = Color.parseColor("#FFFFFF")
        val inactive = Color.parseColor("#F0F0F0")
        engineTab.setBackgroundColor(if (tab == TAB_ENGINE) active else inactive)
        openingTab.setBackgroundColor(if (tab == TAB_OPENING) active else inactive)
        gameTab.setBackgroundColor(if (tab == TAB_GAME) active else inactive)
        settingsTab.setBackgroundColor(if (tab == TAB_SETTINGS) active else inactive)
        engineScroll.visibility = if (tab == TAB_ENGINE) View.VISIBLE else View.GONE
        openingScroll.visibility = if (tab == TAB_OPENING) View.VISIBLE else View.GONE
        gamePage.visibility = if (tab == TAB_GAME) View.VISIBLE else View.GONE
        settingsScroll.visibility = if (tab == TAB_SETTINGS) View.VISIBLE else View.GONE
        if (tab == TAB_OPENING && displayCloud) queryCloud()
        renderInfo()
    }

    private fun onBoardTap(rank: Int, file: Int) {
        if (controller.editMode) {
            setStatusMessage(controller.editTap(rank, file))
            refreshUi()
            return
        }
        val selected = controller.selected
        if (selected != null && (selected.fromRank != rank || selected.fromFile != file)) {
            val from = selected
            controller.clearSelection()
            if (controller.tryHumanMove(from.fromRank, from.fromFile, rank, file)) {
                afterMoveChanged("你走了 ${lastChinese()}")
            } else {
                setStatusMessage("这步不合法")
                refreshUi()
            }
            return
        }
        val piece = controller.displayPos.pieceAt(rank, file)
        if (controller.browseIndex >= 0) {
            browseLast()
            return
        }
        if (piece != null && piece.first().toString() == controller.sideToMove && controller.canMove(controller.sideToMove)) {
            controller.select(rank, file)
        } else {
            controller.clearSelection()
        }
        refreshUi()
    }

    private fun guardRepetition(): Boolean {
        val list = controller.recentIccs(8)
        if (list.size < 4) return false
        val a = list[list.size - 1]
        val b = list[list.size - 3]
        if (a == b) {
            statusMessage = "检测到重复着法，建议使用换招打破循环"
            renderInfo()
            return true
        }
        return false
    }

    private fun afterMoveChanged(message: String) {
        statusMessage = "$message\n${gameStateText()}"
        lastResult = EngineResult()
        cloudMoves = emptyList()
        positionToken++
        refreshUi()
        renderInfo()
        if (displayCloud) queryCloud()
        maybeAutoMove()
        if (analysisMode && !controller.thinking) {
            engine?.send("stop")
            continueAnalysis()
        }
    }

    private fun setEngineSide(side: String) {
        if (!engineSides.remove(side)) engineSides.add(side)
        statusMessage = if (engineSides.isEmpty()) "引擎已停止执子" else "引擎执" + engineSides.sorted().joinToString("、") { sideName(it) }
        renderInfo()
        maybeAutoMove()
    }

    private fun maybeAutoMove() {
        if (!engineReady || controller.thinking || controller.isGameOver || controller.editMode) return
        if (controller.sideToMove in engineSides) {
            analyzeAndMove(controller.sideToMove)
        }
    }

    private fun analyzeAndMove(side: String) {
        if (!engineReady || controller.thinking || controller.isGameOver) return
        analysisMode = false
        analysisJob?.cancel()
        analysisJob = null
        positionToken++
        controller.thinking = true
        val requestToken = positionToken
        setStatusMessage("${sideName(side)}思考中…")
        val fen = controller.fen
        val history = controller.moves.map { it.iccs() }
        lifecycleScope.launch(Dispatchers.IO) {
            val result = requestEngine(fen, side, history = history)
            withContext(Dispatchers.Main) {
                controller.thinking = false
                if (requestToken != positionToken) return@withContext
                lastResult = result
                if (controller.applyEngineMove(result.bestmove)) {
                    afterMoveChanged("${sideName(side)}走了 ${lastChinese()}")
                } else {
                    statusMessage = "引擎着法无效：${result.bestmove.ifBlank { "空" }}"
                    renderEngine(result, live = false)
                    refreshUi()
                }
            }
        }
    }

    private fun toggleAnalysisMode() {
        if (analysisMode) {
            analysisMode = false
            analysisJob?.cancel()
            analysisJob = null
            statusMessage = "持续分析已停止"
            sendStop()
        } else {
            if (!engineReady) {
                setStatusMessage("皮卡鱼还没就绪")
                renderInfo()
                return
            }
            analysisMode = true
            statusMessage = "持续分析中"
            continueAnalysis()
        }
        renderInfo()
    }

    private var analysisJob: kotlinx.coroutines.Job? = null

    private fun continueAnalysis() {
        if (!analysisMode || controller.thinking) return
        val fen = controller.fen
        val side = controller.sideToMove
        val history = controller.moves.map { it.iccs() }
        val requestToken = positionToken
        analysisJob = lifecycleScope.launch(Dispatchers.IO) {
            val result = requestEngine(fen, side, infinite = true, history = history)
            withContext(Dispatchers.Main) {
                if (requestToken != positionToken) return@withContext
                renderEngine(result, live = true)
                if (analysisMode) continueAnalysis()
            }
        }
    }

    private fun playBestNow() {
        if (!engineReady || controller.thinking || controller.isGameOver) {
            setStatusMessage("当前不能出招")
            renderInfo()
            return
        }
        analyzeAndMove(controller.sideToMove)
    }

    /** 强制换招：清掉当前提示后重新搜索一次 */
    private fun forceChangeMove() {
        if (!engineReady || controller.thinking || controller.isGameOver) return
        controller.clearHint()
        refreshUi()
        analyzeAndMove(controller.sideToMove)
    }

    private suspend fun requestEngine(fen: String, side: String, infinite: Boolean = false, history: List<String> = emptyList()): EngineResult = engineMutex.withLock {
        val current = engine ?: return@withLock EngineResult()
        if (!current.isReady) return@withLock EngineResult()
        current.analyze(
            fen,
            movetimeMs = thinkMs,
            depth = searchDepth,
            multiPv = multiPv,
            infinite = infinite,
            history = history,
            onInfo = { partial ->
                runOnUiThread {
                    if (partial.bestmove.isNotBlank()) renderEngine(partial, live = true)
                }
            },
        )
    }

    private fun sendStop() {
        lifecycleScope.launch(Dispatchers.IO) { engine?.send("stop") }
    }

    private fun queryCloud() {
        val fen = controller.fen
        if (fen == cloudFen || cloudLoading) return
        cloudFen = fen
        cloudLoading = true
        cloudMessage = "开局库查询中…"
        renderInfo()
        lifecycleScope.launch(Dispatchers.IO) {
            val result = cloudBook.query(fen)
            withContext(Dispatchers.Main) {
                cloudLoading = false
                if (controller.fen == fen) {
                    when (result) {
                        is CloudBook.Result.Moves -> {
                            cloudMoves = result.list
                            cloudMessage = "开局库 ${cloudMoves.size} 条"
                            maybePlayCloud()
                        }
                        is CloudBook.Result.Error -> {
                            cloudMoves = emptyList()
                            cloudMessage = result.message
                        }
                    }
                    renderInfo()
                }
            }
        }
    }

    private fun maybePlayCloud() {
        if (!executeCloud || controller.editMode || controller.thinking || controller.isGameOver) return
        val best = cloudMoves.maxByOrNull { it.rank }
        best?.let { playCloudMove(it.move) }
    }

    /** 用云库候选做一步提示，不落子 */
    private fun showCloudHint(iccs: String) {
        controller.hintFromIccs(iccs)
        refreshUi()
    }

    private fun playCloudMove(iccs: String) {
        if (controller.editMode || controller.isGameOver || controller.browseIndex >= 0) return
        val before = controller.displayPos.copy()
        if (controller.applyEngineMove(iccs)) {
            afterMoveChanged("云库走子：${Notation.moveToChinese(before, iccs)}")
        } else {
            Toast.makeText(this, "这步不合法：$iccs", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderEngine(result: EngineResult, live: Boolean) {
        if (result.fen.isNotBlank() && result.fen.substringBefore(' ') != controller.fen.substringBefore(' ')) return
        lastResult = result
        lastLive = live
        if (result.bestmove.isNotBlank()) controller.hintFromIccs(result.bestmove)
        refreshUi()
        renderInfo()
    }

    private fun updateToolStates() {
        val on = Color.parseColor("#C8E6C9")
        val off = Color.parseColor("#F5F5F5")
        if (::engineRedButton.isInitialized) engineRedButton.setBackgroundColor(if ("w" in engineSides) on else off)
        if (::engineBlackButton.isInitialized) engineBlackButton.setBackgroundColor(if ("b" in engineSides) on else off)
        if (::analysisButton.isInitialized) analysisButton.setBackgroundColor(if (analysisMode) on else off)
    }

    private fun renderInfo() {
        updateToolStates()
        updateOverlayState()
        if (bottomTab == TAB_ENGINE) renderEnginePage()
        if (bottomTab == TAB_OPENING) renderOpeningPage()
        if (bottomTab == TAB_GAME) renderGamePage()
    }

    private fun renderEnginePage() {
        enginePage.removeAllViews()
        buildEnginePage { value -> (value * resources.displayMetrics.density).toInt() }
        val header = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            text = if (!engineReady) statusMessage
            else if (lastResult.bestmove.isBlank()) statusMessage
            else "深度:${lastResult.depth}  ${scoreTextFor(lastResult)}  时间:${String.format("%.1f", lastResult.timeMs / 1000.0)}  NPS:${formatNps(lastResult.nps)}"
            setPadding(0, 4, 0, 6)
        }
        enginePage.addView(header)
        if (!engineReady || lastResult.bestmove.isBlank()) return

        val pvText = TextView(this).apply {
            textSize = 13f
            text = pvChinese(lastResult.pv)
            setPadding(0, 0, 0, 8)
        }
        enginePage.addView(pvText)
        lastResult.lines.take(multiPv).forEachIndexed { index, line ->
            val text = TextView(this).apply {
                textSize = 14f
                text = "${index + 1}.  ${Notation.moveToChinese(controller.displayPos, line.move)}  ${scoreTextFor(lastResult.analyzedSide, line.mateIn, line.scoreCp)}  深度:${line.depth}"
                setPadding(0, 8, 0, 8)
                setOnClickListener {
                    controller.hintFromIccs(line.move)
                    refreshUi()
                }
            }
            enginePage.addView(text)
        }
    }

    private fun renderOpeningPage() {
        openingPage.removeAllViews()
        buildOpeningPage { value -> (value * resources.displayMetrics.density).toInt() }
        val header = TextView(this).apply {
            text = cloudMessage
            textSize = 14f
            setPadding(0, 6, 0, 4)
        }
        openingPage.addView(header)
        cloudMoves.sortedWith(compareByDescending<BookMove> { it.rank }.thenByDescending { it.score }).forEach { move ->
            val cn = Notation.moveToChinese(controller.displayPos, move.move)
            val row = TextView(this).apply {
                textSize = 16f
                text = "$cn\t${move.rank}\t${String.format("%.2f", move.winrate)}%\t!(${move.score})"
                setPadding(0, 12, 0, 12)
                setOnClickListener {
                    playCloudMove(move.move)
                }
            }
            openingPage.addView(row)
        }
    }

    private fun renderGamePage() {
        val builder = StringBuilder()
        controller.moves.forEachIndexed { index, move ->
            val pre = controller.preMovePos(index)
            val number = (index / 2) + 1
            if (index % 2 == 0) builder.append("$number. ")
            builder.append(Notation.moveToChinese(pre, move.iccs())).append("  ")
            if (index % 2 == 1) builder.append('\n')
        }
        moveText.text = builder.toString().ifBlank { "暂无棋谱" }
    }

    private fun browseFirst() {
        if (controller.browseMax >= 0) controller.browseTo(0)
        refreshUi(); renderInfo()
    }

    private fun browsePrevious() {
        if (controller.browseIndex < 0) controller.browseTo((controller.browseMax - 1).coerceAtLeast(0))
        else controller.browseTo(controller.browseIndex - 1)
        refreshUi(); renderInfo()
    }

    private fun browseNext() {
        if (controller.browseIndex < 0) return
        val next = controller.browseIndex + 1
        controller.browseTo(if (next >= controller.browseMax) -1 else next)
        refreshUi(); renderInfo()
    }

    private fun browseLast() {
        controller.browseTo(-1)
        refreshUi(); renderInfo()
    }

    private fun refreshUi() {
        board.controller = controller
        board.selected = controller.selected
        board.flipped = flipped
        board.showArrow = showArrowHint && controller.browseIndex < 0
        board.showCoords = true
        board.invalidate()
    }

    private fun menuDialog() {
        val options = arrayOf(
            "新局", "打开局面", "保存局面", "编辑局面", "翻转局面",
            "引擎执黑", "引擎执红", "分析模式", "立即出招", "强制变招",
            "连线", "悬浮窗", "设置",
        )
        AlertDialog.Builder(this).setTitle("菜单").setItems(options) { _, which ->
            when (which) {
                0 -> newGame()
                1 -> importFenDialog()
                2 -> exportFenDialog()
                3 -> editDialog()
                4 -> { flipped = !flipped; refreshUi() }
                5 -> setEngineSide("b")
                6 -> setEngineSide("w")
                7 -> toggleAnalysisMode()
                8 -> playBestNow()
                9 -> forceChangeMove()
                10 -> linkDialog()
                11 -> toggleOverlay()
                12 -> settingsDialog()
            }
        }.show()
    }

    private fun newGame() {
        controller.newGame()
        analysisMode = false
        engineSides.clear()
        statusMessage = "新局开始，红方先行"
        afterMoveChanged("新局")
    }

    private fun undo() {
        if (!controller.undo()) Toast.makeText(this, "没有可悔的棋", Toast.LENGTH_SHORT).show()
        controller.clearSelection()
        refreshUi(); renderInfo()
    }

    private fun editDialog() {
        startBoardEditor()
    }

    private fun startBoardEditor() {
        controller.startEditMode()
        statusMessage = "编辑模式：选下方棋子后点棋盘放置；点已有棋子可直接拿起"
        navBar.visibility = View.GONE
        tabBar.visibility = View.GONE
        pages.visibility = View.GONE
        buildEditPanel()
        editPanel.visibility = View.VISIBLE
        renderInfo()
    }

    private fun finishBoardEditor() {
        controller.exitEditMode()
        editPanel.visibility = View.GONE
        statusMessage = "编辑完成"
        navBar.visibility = View.VISIBLE
        tabBar.visibility = View.VISIBLE
        pages.visibility = View.VISIBLE
        renderInfo()
    }

    private fun buildEditPanel() {
        val toPx: (Int) -> Int = { value -> (value * resources.displayMetrics.density).toInt() }
        editPanel.removeAllViews()
        editPanel.orientation = LinearLayout.VERTICAL
        editPanel.setBackgroundColor(Color.parseColor("#F5F5F5"))
        editPanel.setPadding(toPx(3), toPx(2), toPx(3), toPx(2))

        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        modeRow.addView(editChoice("放子", !controller.editErase) {
            controller.editErase = false
            buildEditPanel()
        })
        modeRow.addView(editChoice("擦除", controller.editErase) {
            controller.editErase = true
            buildEditPanel()
        })
        modeRow.addView(editChoice("红方行棋", controller.sideToMove == "w") {
            controller.setSideToMove("w")
            buildEditPanel()
        })
        modeRow.addView(editChoice("黑方行棋", controller.sideToMove == "b") {
            controller.setSideToMove("b")
            buildEditPanel()
        })
        modeRow.addView(editTool("导入FEN") { importFenDialog() })
        modeRow.addView(editTool("导出FEN") { exportFenDialog() })
        modeRow.addView(editTool("完成") { finishBoardEditor() })
        editPanel.addView(modeRow)

        editPanel.addView(TextView(this).apply {
            text = "红方棋子"
            textSize = 12f
            setPadding(0, toPx(1), 0, 0)
        })
        editPanel.addView(pieceTray("w", toPx))

        editPanel.addView(TextView(this).apply {
            text = "黑方棋子"
            textSize = 12f
            setPadding(0, toPx(1), 0, 0)
        })
        editPanel.addView(pieceTray("b", toPx))
    }

    private fun pieceTray(side: String, toPx: (Int) -> Int): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val counts = pieceCounts(side)
        val names = mapOf("k" to if (side == "w") "帅" else "将", "a" to if (side == "w") "仕" else "士", "b" to if (side == "w") "相" else "象", "n" to "马", "r" to "车", "c" to "炮", "p" to if (side == "w") "兵" else "卒")
        val limits = mapOf("k" to 1, "a" to 2, "b" to 2, "n" to 2, "r" to 2, "c" to 2, "p" to 5)
        for ((type, limit) in limits) {
            val used = counts[type] ?: 0
            val available = used < limit
            val selected = controller.editPiece == side + type && available
            val label = (names[type] ?: type) + " " + used + "/" + limit
            row.addView(editChoice(label, selected) {
                if (!available) {
                    setStatusMessage((names[type] ?: type) + "已满，最多 " + limit + " 个")
                    renderInfo()
                    return@editChoice
                }
                controller.editErase = false
                controller.editPiece = side + type
                buildEditPanel()
            })
        }
        return row
    }

    private fun pieceCounts(side: String): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (rank in 0..9) for (file in 0..8) {
            val piece = controller.displayPos.pieceAt(rank, file) ?: continue
            if (piece.first().toString() == side) counts[piece.substring(1)] = (counts[piece.substring(1)] ?: 0) + 1
        }
        return counts
    }

    private fun editChoice(label: String, active: Boolean, action: () -> Unit): Button =
        editTool(label, action).apply {
            setBackgroundColor(if (active) Color.parseColor("#C8E6C9") else Color.parseColor("#FAFAFA"))
            isEnabled = true
            alpha = if (active) 1f else 0.82f
        }

    private fun editTool(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 10f
        isAllCaps = false
        setPadding(0, 0, 0, 0)
        setBackgroundColor(Color.parseColor("#FAFAFA"))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 2 }
        setOnClickListener { action() }
    }

    private fun importFenDialog() {
        val input = EditText(this).apply { setText(controller.exportFen()) }
        AlertDialog.Builder(this).setTitle("导入 FEN").setView(input).setPositiveButton("导入") { _, _ ->
            val ok = controller.importFen(input.text.toString())
            statusMessage = if (ok) "FEN 导入成功" else "FEN 格式错误"
            refreshUi(); renderInfo()
        }.setNegativeButton("取消", null).show()
    }

    private fun exportFenDialog() {
        val input = EditText(this).apply { setText(controller.exportFen()); setSelection(text.length) }
        AlertDialog.Builder(this).setTitle("保存局面 / FEN").setView(input)
            .setPositiveButton("复制") { _, _ -> copyText(controller.exportFen()); Toast.makeText(this, "已复制 FEN", Toast.LENGTH_SHORT).show() }
            .setNegativeButton("关闭", null).show()
    }

    private fun settingsDialog() {
        val depthLabels = arrayOf("不限", "6 层", "8 层", "10 层", "12 层")
        val depthValues = intArrayOf(0, 6, 8, 10, 12)
        val options = arrayOf(
            "思考时间：${thinkMs / 1000} 秒", "搜索深度：${if (searchDepth == 0) "不限" else "$searchDepth 层"}",
            "候选着法数：$multiPv", "AI 回招：${if (controller.autoReply) "开" else "关"}",
            "悔棋",
        )
        AlertDialog.Builder(this).setTitle("设置").setItems(options) { _, which ->
            when (which) {
                0 -> thinkTimeDialog()
                1 -> AlertDialog.Builder(this).setTitle("搜索深度").setItems(depthLabels) { _, di -> searchDepth = depthValues[di] }.show()
                2 -> AlertDialog.Builder(this).setTitle("候选着法数").setItems(arrayOf("1", "2", "3", "4", "5")) { _, mi -> multiPv = mi + 1 }.show()
                3 -> controller.autoReply = !controller.autoReply
                4 -> undo()
            }
            renderInfo()
        }.show()
    }

    private fun thinkTimeDialog() {
        val input = EditText(this).apply {
            setText((thinkMs / 1000).toString()); inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this).setTitle("思考时间（1-60 秒）").setView(input)
            .setPositiveButton("确定") { _, _ -> setThinkSeconds(input.text.toString()) }
            .setNegativeButton("取消", null).show()
    }

    private fun linkDialog() {
        if (!LiveLinkService.isConnected) {
            android.app.AlertDialog.Builder(this).setTitle("连线")
                .setMessage("需要先开启无障碍服务，才能读取对局界面并模拟走子。是否前往系统设置？")
                .setPositiveButton("去开启") { _, _ ->
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null).show()
            return
        }
        val svc = LiveLinkService.instance
        val summary = try { svc?.dumpNodes()?.take(3000) ?: "无窗口" } catch (e: Throwable) { "读取失败：" + e.message }
        android.app.AlertDialog.Builder(this).setTitle("连线已开启")
            .setMessage("当前界面节点摘要：\n" + summary)
            .setPositiveButton("确定", null).show()
    }

    override fun onLink() {
        runOnUiThread {
            if (LiveLinkService.isConnected) {
                LiveLinkService.disconnect()
                statusMessage = "连线已断开"
                renderInfo()
            } else {
                linkDialog()
            }
        }
    }

    override fun onAnalyze() {
        runOnUiThread { toggleAnalysisMode() }
    }

    override fun onPlayMove() {
        runOnUiThread { playBestNow() }
    }

    override fun onDepthChange(delta: Int) {
        runOnUiThread {
            searchDepth = when {
                delta < 0 -> 0
                searchDepth <= 0 -> 6
                else -> (searchDepth + delta).coerceIn(6, 18)
            }
            statusMessage = if (searchDepth == 0) "深度不限" else "深度 $searchDepth 层"
            renderInfo()
        }
    }

    override fun onTimeChange(delta: Int) {
        runOnUiThread {
            if (delta < 0) thinkMs = (thinkMs - 1000).coerceAtLeast(1000)
            else thinkMs = (thinkMs + 1000).coerceAtMost(60000)
            statusMessage = "思考时间 ${thinkMs / 1000} 秒"
            renderInfo()
        }
    }

    override fun onOpacityChange(delta: Int) {
        runOnUiThread {
            val svc = OverlayService.overlayDisplay ?: return@runOnUiThread
            overlayAlpha = if (delta < 0) {
                (overlayAlpha - 0.1f).coerceAtLeast(0.35f)
            } else {
                (overlayAlpha + 0.1f).coerceAtMost(1f)
            }
            svc.updateOpacity(overlayAlpha)
        }
    }

    override fun onCloseOverlay() {
        runOnUiThread {
            overlayOn = false
            OverlayService.stop(this)
            statusMessage = "悬浮窗已关闭"
            renderInfo()
        }
    }

    private fun toggleOverlay() {
        if (!overlayOn) {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
                Toast.makeText(this, "请允许悬浮窗权限后再点一次", Toast.LENGTH_SHORT).show()
                return
            }
            OverlayService.start(this); overlayOn = true
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
        } else {
            OverlayService.stop(this); overlayOn = false
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateOverlayState() {
        val svc = OverlayService.overlayDisplay ?: return
        svc.updateControls(searchDepth, thinkMs / 1000)
        svc.updateActions(lastLive, analysisMode, controller.thinking)
        val cloudText = if (displayCloud) {
            if (cloudLoading) "查询中" else cloudMoves.firstOrNull()?.let { Notation.moveToChinese(controller.displayPos, it.move) + " " + it.winrate } ?: "无"
        } else "关"
        val engineSummary = when {
            !engineReady -> "未就绪"
            controller.thinking -> "思考中"
            lastResult.bestmove.isBlank() -> "-"
            else -> lastResult.chinese(controller.displayPos)
        }
        val engineDetail = if (lastResult.bestmove.isBlank()) "" else {
            val score = scoreTextFor(lastResult)
            val time = String.format("%.1f秒", lastResult.timeMs / 1000.0)
            val path = pvChinese(lastResult.pv).ifBlank { lastResult.chinese(controller.displayPos) }
            "深度${lastResult.depth} · $score · $time\n$path"
        }
        svc.updateInfo(cloudText, engineSummary, engineDetail)
    }

    private fun setStatusMessage(message: String) {
        statusMessage = message
        renderInfo()
    }

    private fun gameStateText(): String = when (val winner = controller.winner()) {
        "w" -> "红方胜"
        "b" -> "黑方胜"
        else -> "轮到${sideName(controller.sideToMove)}"
    }

    private fun sideName(side: String): String = if (side == "w") "红方" else "黑方"

    private fun scoreTextFor(result: EngineResult): String =
        scoreTextFor(result.analyzedSide.ifBlank { controller.sideToMove }, result.mateIn, result.scoreCp)

    private fun scoreTextFor(analyzedSide: String, mate: Int?, cp: Int?): String {
        val sign = if (analyzedSide == "w") 1 else -1
        mate?.let { value ->
            val redMate = value * sign
            return if (redMate > 0) "红优 #${redMate}" else "黑优 #${kotlin.math.abs(redMate)}"
        }
        val value = cp ?: return "-"
        val red = value * sign / 100.0
        return if (red >= 0) "红优 ${String.format("%.2f", red)}" else "黑优 ${String.format("%.2f", -red)}"
    }

    private fun formatNps(value: Long): String = when {
        value >= 1_000_000 -> String.format("%.0fk", value / 1000.0)
        value > 0 -> value.toString()
        else -> "-"
    }

    private fun pvChinese(pv: List<String>): String {
        if (pv.isEmpty()) return ""
        val position = controller.displayPos.copy()
        val out = StringBuilder()
        pv.take(12).forEach { move ->
            out.append(Notation.moveToChinese(position, move)).append("  ")
            try { position.applyIccs(move) } catch (_: Throwable) { return out.toString().trim() }
        }
        return out.toString().trim()
    }

    private fun lastChinese(): String {
        val pre = controller.lastPreMove ?: controller.displayPos
        val move = controller.lastMove ?: return ""
        return Notation.moveToChinese(pre, move.iccs())
    }

    private fun copyText(value: String) {
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("XiangQi", value))
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisMode = false
        engine?.stop()
        engine = null
    }

    companion object {
        private const val TAB_ENGINE = 0
        private const val TAB_OPENING = 1
        private const val TAB_GAME = 2
        private const val TAB_SETTINGS = 3
    }
}
