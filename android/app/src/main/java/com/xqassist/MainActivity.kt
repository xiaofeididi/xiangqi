package com.xqassist

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xqassist.capture.CaptureService
import com.xqassist.capture.ScreenHelper
import com.xqassist.core.Notation
import com.xqassist.connection.ConnectSession
import com.xqassist.connection.LiveLinkService
import com.xqassist.engine.CloudBook
import com.xqassist.engine.BookMove
import com.xqassist.engine.EngineHolder
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
import com.xqassist.game.GameController
import com.xqassist.overlay.OverlayService
import com.xqassist.ui.BoardView
import com.xqassist.vision.BoardRect
import com.xqassist.vision.TemplatePieceReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 皮卡鱼象棋助手：仿收费端主界面，顶部图标工具栏 + 棋盘 + 引擎/开局库/棋谱页签 */
class MainActivity : AppCompatActivity() {

    private lateinit var controller: GameController
    private lateinit var board: BoardView
    private lateinit var enginePage: LinearLayout
    private lateinit var openingPage: LinearLayout
    private lateinit var engineScroll: ScrollView
    private lateinit var openingScroll: ScrollView
    private lateinit var gamePage: LinearLayout
    private lateinit var settingsPage: LinearLayout
    private lateinit var editPanel: LinearLayout
    private lateinit var navBar: LinearLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var pages: LinearLayout
    private lateinit var settingsScroll: ScrollView
    private lateinit var movesListContainer: LinearLayout
    private lateinit var engineTab: Button
    private lateinit var openingTab: Button
    private lateinit var gameTab: Button
    private lateinit var settingsTab: Button
    private lateinit var analysisButton: Button
    private lateinit var engineRedButton: Button
    private lateinit var engineBlackButton: Button
    private lateinit var statusStrip: TextView

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
    private var capturedBoardRect: BoardRect? = null
    private var capturedBoardFlipped = false
    private var calibrationFrame: Bitmap? = null
    private var calibrationPoints = mutableListOf<Pair<Int, Int>>()
    private var calibrationRoot: android.widget.FrameLayout? = null
    private var lastRecognizedFen = ""
    private var lastRecognizedMs = 0L
    private var developerMode = false
    private var developerTapCount = 0

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

    private val basicReader by lazy { TemplatePieceReader(this, TemplatePieceReader.MODE_BASIC) }
    private val wideReader by lazy { TemplatePieceReader(this, TemplatePieceReader.MODE_WIDE) }
    private var visionMode = TemplatePieceReader.MODE_BASIC
    private val mediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    /** 刚授权成功的时间戳，避免服务异步启动期间被判定“未授权”而无限重弹 */
    @Volatile
    private var captureGrantedAt = 0L
    private var pendingOpenOverlayAfterCapture = false
    private var pendingLaunchCapture = false

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        android.util.Log.i("Capture", "POST_NOTIFICATIONS granted=$granted")
        if (pendingLaunchCapture) {
            pendingLaunchCapture = false
            launchScreenCaptureIntent()
        }
    }

    /** Android 13+：没有通知权限时 startForeground 会失败，必须先申请 */
    private fun ensureNotificationThenCapture() {
        if (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchScreenCaptureIntent()
            return
        }
        pendingLaunchCapture = true
        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun launchScreenCaptureIntent() {
        try {
            bringToFront()
            if (!ScreenHelper.prepare(this)) {
                Toast.makeText(this, "无法读取屏幕尺寸", Toast.LENGTH_LONG).show()
                return
            }
            val intent = ScreenHelper.createScreenCaptureIntent(this)
            if (intent == null) {
                Toast.makeText(this, "无法发起屏幕识别授权", Toast.LENGTH_LONG).show()
                return
            }
            capturePermissionLauncher.launch(intent)
        } catch (e: Throwable) {
            android.util.Log.e("Capture", "capture: permission launch failed", e)
            Toast.makeText(this, "屏幕识别授权失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private val capturePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val ok = ScreenHelper.create(this, result.resultCode, result.data)
        if (ok) {
            captureGrantedAt = System.currentTimeMillis()
            Toast.makeText(this, "屏幕识别已打开", Toast.LENGTH_SHORT).show()
            if (pendingOpenOverlayAfterCapture) {
                pendingOpenOverlayAfterCapture = false
                lifecycleScope.launch {
                    delay(400)
                    if (hasOverlayPermission() && hasAccessibilityPermission()) {
                        OverlayService.start(this@MainActivity)
                        overlayOn = true
                        Toast.makeText(this@MainActivity, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
                        updateOverlayState()
                    }
                }
            }
        } else {
            pendingOpenOverlayAfterCapture = false
            Toast.makeText(this, "截屏启动失败，请再试一次", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = GameController()
        ConnectSession.attach(this)
        // 无障碍连上/断开时立刻刷新悬浮窗按钮文案
        LiveLinkService.onConnectedChanged = {
            runOnUiThread { updateOverlayState() }
        }
        capturedBoardRect = ConnectSession.boardRect
        ConnectSession.onSnapshot = { state, message, fen, _ ->
            runOnUiThread { onConnectSnapshot(state, message, fen) }
        }
        buildUi()
        refreshUi()
        startEngine()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新无障碍/截屏状态
        updateOverlayState()
        renderInfo()
    }

    private fun onConnectSnapshot(state: ConnectSession.State, message: String, fen: String) {
        // 主界面本地棋盘跟随识别结果（编辑中不覆盖）
        if (!controller.editMode && fen.isNotBlank()) {
            val localFen = controller.exportFen().substringBefore(' ')
            val remoteFen = fen.substringBefore(' ')
            if (localFen != remoteFen) {
                controller.importFen(fen)
                refreshUi()
                if (displayCloud) queryCloud()
            }
            val best = ConnectSession.lastResult.bestmove
            if (best.isNotBlank()) {
                controller.hintFromIccs(best)
                lastResult = ConnectSession.lastResult
                refreshUi()
            }
        }
        statusMessage = message
        updateOverlayState()
        renderInfo()
    }

    private fun startEngine() {
        EngineHolder.ensure(this) { installed ->
            runOnUiThread {
                if (installed == null || !installed.isReady) {
                    engine = installed
                    engineReady = false
                    statusMessage = "皮卡鱼文件缺失或启动失败"
                    renderInfo()
                    return@runOnUiThread
                }
                engine = installed
                engineReady = true
                ConnectSession.provideEngine(installed)
                ConnectSession.provideReader(basicReader)
                statusMessage = "皮卡鱼就绪 · 红方先行"
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
            setBackgroundColor(Color.parseColor("#F3F5F7"))
            clipChildren = true
            clipToPadding = true
        }

        // 主色参考 Pro象棋：teal primary #069695 / 淡底 shallowGreen
        val teal = Color.parseColor("#069695")
        val tealDark = Color.parseColor("#047A79")
        val surface = Color.parseColor("#FFFFFF")
        val ink = Color.parseColor("#1F2933")
        val muted = Color.parseColor("#6B7785")

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(6), dp(4), dp(6), dp(6))
            elevation = dp(2).toFloat()
        }
        fun tool(label: String, accent: Boolean = false, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            includeFontPadding = false
            setPadding(dp(2), 0, dp(2), 0)
            setTextColor(if (accent) Color.WHITE else ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (accent) teal else Color.parseColor("#EEF2F5"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                marginEnd = dp(3)
            }
            setOnClickListener { action() }
        }
        statusStrip = TextView(this).apply {
            text = ""
            visibility = View.GONE
            textSize = 11f
            setTextColor(muted)
        }
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row1.addView(tool("菜单") { menuDialog() })
        row1.addView(tool("新局") { newGame() })
        row1.addView(tool("编辑") { editDialog() })
        row1.addView(tool("悔棋") { undo() })
        val overlayTool = tool("悬浮") { toggleOverlay() }
        overlayTool.setOnLongClickListener {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("连线权限检查")
                .setMessage(permissionChecklist() + "\n\n全部✔后，点「悬浮」才会打开悬浮窗。")
                .setPositiveButton("去补全") { _, _ -> ensurePermissionsThenOverlay() }
                .setNegativeButton("关闭", null)
                .show()
            true
        }
        row1.addView(overlayTool)
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(3)
            }
        }
        engineRedButton = tool("执红") { setEngineSide("w") }
        engineBlackButton = tool("执黑") { setEngineSide("b") }
        analysisButton = tool("分析") { toggleAnalysisMode() }
        row2.addView(engineRedButton)
        row2.addView(engineBlackButton)
        row2.addView(analysisButton)
        row2.addView(tool("出招") { playBestNow() })
        row2.addView(tool("换招") { forceChangeMove() })
        toolbar.addView(row1)
        toolbar.addView(row2)
        // 状态条去掉，避免和底部重复显示“屏幕识别未授权”

        navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(surface)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
        }
        fun nav(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            includeFontPadding = false
            setTextColor(tealDark)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor("#E7F6F6"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                marginEnd = dp(3)
            }
            setOnClickListener { action() }
        }
        navBar.addView(nav("开局") { browseFirst() })
        navBar.addView(nav("后退") { browsePrevious() })
        navBar.addView(nav("前进") { browseNext() })
        navBar.addView(nav("终局") { browseLast() })

        board = BoardView(this).apply {
            controller = this@MainActivity.controller
            listener = { rank, file -> onBoardTap(rank, file) }
            minimumWidth = 0
            minimumHeight = 0
        }
        val boardBox = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#F5F1E8"))
            setPadding(dp(6), dp(4), dp(6), dp(4))
            clipToPadding = true
            clipChildren = true
            addView(
                board,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }

        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surface)
            setPadding(dp(4), 0, dp(4), 0)
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
            setBackgroundColor(Color.parseColor("#FBF9F4"))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        openingPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F4"))
            setPadding(dp(8), dp(4), dp(8), dp(8))
        }
        val movesList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F4"))
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        movesListContainer = movesList
        gamePage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F4"))
            addView(ScrollView(this@MainActivity).apply { addView(movesList) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        settingsPage = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FBF9F4"))
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
            setBackgroundColor(Color.parseColor("#F1ECE2"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        buildEditPanel()

        pages = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F3F5F7"))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                0.32f,
            ).apply { weight = 0.32f }
        }
        engineScroll = ScrollView(this).apply { addView(enginePage); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        openingScroll = ScrollView(this).apply { addView(openingPage); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) }
        pages.addView(engineScroll)
        pages.addView(openingScroll)
        pages.addView(gamePage)
        pages.addView(settingsScroll)

        // body 把导航和棋盘包在一层，避免高度分配把导航压扁
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        body.addView(navBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        body.addView(boardBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))

        root.addView(toolbar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(editPanel)
        root.addView(body)
        root.addView(tabBar)
        root.addView(pages)
        setContentView(root)
        switchTab(TAB_ENGINE)
        renderInfo()
    }

    private fun buildEnginePage(toPx: (Int) -> Int) {
        // 开关统一放在设置页，首页只保留引擎分析结果
        enginePage.removeAllViews()
    }

    private fun buildOpeningPage(toPx: (Int) -> Int) {
        openingPage.removeAllViews()
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
        toggles.addView(check("显示云库", displayCloud) { _, value -> displayCloud = value; if (value) queryCloud() })
        toggles.addView(check("执行云库", executeCloud) { _, value -> executeCloud = value })
        toggles.addView(check("箭头", showArrowHint) { _, value ->
            showArrowHint = value
            refreshUi()
        })
        settingsPage.addView(toggles)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, toPx(4), 0, 0)
        }
        actionRow.addView(actionButton("悬浮窗") { toggleOverlay() })
        actionRow.addView(actionButton("开发者模式") {
            developerTapCount++
            if (developerTapCount >= 5) {
                developerTapCount = 0
                developerMode = true
                Toast.makeText(this@MainActivity, "开发者模式已开启", Toast.LENGTH_SHORT).show()
                buildSettingsPage(toPx)
            }
        })
        settingsPage.addView(actionRow)

        if (developerMode) {
            val labRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, toPx(4), 0, toPx(4))
            }
            labRow.addView(actionButton("识别实验室") { developerLabDialog() })
            labRow.addView(actionButton("识别测试") { startScreenRecognition() })
            settingsPage.addView(labRow)
        }
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
        includeFontPadding = false
        textSize = 14f
        setPadding(0, dpToPx(8), 0, dpToPx(8))
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action() }
    }

    private fun switchTab(tab: Int) {
        bottomTab = tab
        val teal = Color.parseColor("#069695")
        val on = Color.parseColor("#E7F6F6")
        val off = Color.parseColor("#FFFFFF")
        val buttons = listOf(
            TAB_ENGINE to engineTab,
            TAB_OPENING to openingTab,
            TAB_GAME to gameTab,
            TAB_SETTINGS to settingsTab,
        )
        buttons.forEach { (id, button) ->
            val active = id == tab
            button.setTextColor(if (active) teal else Color.parseColor("#5B6672"))
            button.background = GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(if (active) on else off)
            }
        }
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
        val teal = Color.parseColor("#069695")
        val on = Color.parseColor("#C8F0EE")
        val off = Color.parseColor("#EEF2F5")
        fun paint(button: Button, active: Boolean) {
            button.setTextColor(if (active) teal else Color.parseColor("#1F2933"))
            button.background = GradientDrawable().apply {
                cornerRadius = 8f * resources.displayMetrics.density
                setColor(if (active) on else off)
            }
        }
        if (::engineRedButton.isInitialized) paint(engineRedButton, "w" in engineSides)
        if (::engineBlackButton.isInitialized) paint(engineBlackButton, "b" in engineSides)
        if (::analysisButton.isInitialized) paint(analysisButton, analysisMode || ConnectSession.isRunning)
    }

    private fun renderInfo() {
        if (::statusStrip.isInitialized) {
            // 顶部状态条已隐藏，避免与底部重复
            statusStrip.visibility = View.GONE
        }
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

    private fun dpToPx(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun renderGamePage() {
        movesListContainer.removeAllViews()
        if (controller.activeVariation != null) {
            val back = TextView(this).apply {
                text = "← 返回主线"
                textSize = 13f
                setTextColor(Color.parseColor("#1B5E20"))
                setPadding(0, this@MainActivity.dpToPx(4), 0, this@MainActivity.dpToPx(6))
                setOnClickListener { controller.activateMainline(); refreshUi(); renderInfo(); renderGamePage() }
            }
            movesListContainer.addView(back)
        }
        val moves = controller.moves
        if (moves.isEmpty()) {
            val empty = TextView(this).apply {
                text = "暂无棋谱"
                textSize = 14f
                setTextColor(Color.parseColor("#777777"))
            }
            movesListContainer.addView(empty)
            return
        }
        moves.forEachIndexed { index, move ->
            val pre = controller.preMovePos(index)
            val label = Notation.moveToChinese(pre, move.iccs())
            val atEnd = index == moves.size - 1 && controller.browseIndex == -1
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val step = TextView(this).apply {
                text = ((index / 2) + 1).toString() + if (index % 2 == 0) ". " else "… "
                textSize = 12f
                setTextColor(Color.parseColor("#888888"))
            }
            val moveView = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (atEnd) Color.parseColor("#0B4E8C") else Color.parseColor("#333333"))
                setPadding(0, this@MainActivity.dpToPx(8), this@MainActivity.dpToPx(4), this@MainActivity.dpToPx(8))
            }
            val branch = TextView(this).apply {
                text = "变"
                textSize = 10f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#D84315"))
                setPadding(this@MainActivity.dpToPx(4), this@MainActivity.dpToPx(1), this@MainActivity.dpToPx(4), this@MainActivity.dpToPx(1))
                visibility = if (controller.activeVariation != null && index == 0) View.VISIBLE else View.GONE
            }
            row.addView(step)
            row.addView(moveView)
            row.addView(branch)
            row.setOnClickListener {
                controller.browseTo(if (controller.browseIndex == index && controller.activeVariation != null) -1 else index)
                refreshUi(); renderInfo(); renderGamePage()
            }
            movesListContainer.addView(row)
        }
        if (controller.variations.isNotEmpty() && controller.activeVariation == null) {
            val head = TextView(this).apply {
                text = "变招"
                textSize = 13f
                setTextColor(Color.parseColor("#B26A00"))
                setPadding(0, this@MainActivity.dpToPx(12), 0, this@MainActivity.dpToPx(4))
            }
            movesListContainer.addView(head)
            controller.variations.forEachIndexed { vi, branch ->
                val row = TextView(this).apply {
                    text = controller.variationNotation(branch)
                    textSize = 14f
                    setTextColor(Color.parseColor("#333333"))
                    setPadding(0, this@MainActivity.dpToPx(5), 0, this@MainActivity.dpToPx(5))
                    setOnClickListener { controller.activateVariation(vi); refreshUi(); renderInfo(); renderGamePage() }
                }
                movesListContainer.addView(row)
            }
        }
    }

    private fun browseFirst() {
        if (controller.browseMax >= 0) controller.browseTo(0)
        refreshUi(); renderInfo(); renderGamePage()
    }

    private fun browsePrevious() {
        if (controller.browseIndex < 0) controller.browseTo((controller.browseMax - 1).coerceAtLeast(0))
        else controller.browseTo(controller.browseIndex - 1)
        refreshUi(); renderInfo(); renderGamePage()
    }

    private fun browseNext() {
        if (controller.browseIndex < 0) return
        val next = controller.browseIndex + 1
        controller.browseTo(if (next >= controller.browseMax) -1 else next)
        refreshUi(); renderInfo(); renderGamePage()
    }

    private fun browseLast() {
        controller.browseTo(-1)
        refreshUi(); renderInfo(); renderGamePage()
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
        // 只保留工具栏上没有的入口，避免和顶栏重复
        val options = arrayOf(
            "打开局面", "保存局面", "翻转局面", "连线", "设置",
        )
        AlertDialog.Builder(this).setTitle("菜单").setItems(options) { _, which ->
            when (which) {
                0 -> importFenDialog()
                1 -> exportFenDialog()
                2 -> { flipped = !flipped; refreshUi() }
                3 -> linkDialog()
                4 -> settingsDialog()
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
        afterMoveChanged("悔棋")
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

    private fun startScreenRecognition() {
        if (!CaptureService.isRunning) {
            bringToFront()
            ensureNotificationThenCapture()
            return
        }
        if (calibrationFrame != null && capturedBoardRect != null) {
            lifecycleScope.launch { recognizeOnce() }
            return
        }
        if (calibrationFrame != null) {
            calibrationPoints.clear()
            showCalibrationOverlay()
            setStatusMessage("屏幕识别：使用已标定的目标画面")
            renderInfo()
            return
        }
        calibrationFrame = CaptureService.copyLatestBitmap()
        if (calibrationFrame == null) {
            setStatusMessage("屏幕识别：请返回目标棋盘后重试")
            Toast.makeText(this, "没有取到目标画面，请等一秒再点识别", Toast.LENGTH_SHORT).show()
            renderInfo()
            return
        }
        calibrationPoints.clear()
        showCalibrationOverlay()
        setStatusMessage("屏幕识别：点击截图中的棋盘左上角")
        renderInfo()
    }

    private fun developerLabDialog() {
        val options = arrayOf(
            "方案A：截图两点标定 + 模板匹配",
            "方案B：宽采样模板匹配",
            "方案C：轻量检测模型（规划中）",
            "复制最近识别 FEN",
            "复制当前 FEN",
        )
        AlertDialog.Builder(this).setTitle("识别实验室").setItems(options) { _, which ->
            when (which) {
                0 -> {
                    visionMode = TemplatePieceReader.MODE_BASIC
                    startScreenRecognition()
                }
                1 -> {
                    visionMode = TemplatePieceReader.MODE_WIDE
                    startScreenRecognition()
                }
                2 -> setStatusMessage("方案C规划中：TFLite轻量检测")
                3 -> if (lastRecognizedFen.isBlank()) {
                        Toast.makeText(this, "还没有识别结果", Toast.LENGTH_SHORT).show()
                    } else {
                        copyText(lastRecognizedFen)
                        Toast.makeText(this, "最近识别 FEN 已复制 · ${lastRecognizedMs}ms", Toast.LENGTH_SHORT).show()
                    }
                4 -> {
                    copyText(controller.exportFen())
                    Toast.makeText(this, "FEN 已复制", Toast.LENGTH_SHORT).show()
                }
            }
            renderInfo()
        }.show()
    }

    private fun showCalibrationOverlay() {
        val frame = calibrationFrame ?: return
        val density = resources.displayMetrics.density
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xF0101418.toInt())
        }
        val image = android.widget.ImageView(this).apply {
            setImageBitmap(frame)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        root.addView(image, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val tip = TextView(this).apply {
            text = "点击棋盘左上角交叉点"
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt())
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        root.addView(tip, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.TOP })
        val cancel = TextView(this).apply {
            text = "取消"
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt())
            setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
            setOnClickListener {
                calibrationPoints.clear()
                closeCalibrationOverlay()
            }
        }
        root.addView(cancel, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.END; setMargins(0, 0, (16 * density).toInt(), 0) })
        root.setOnTouchListener { _, event ->
            if (event.action != android.view.MotionEvent.ACTION_UP) return@setOnTouchListener true
            val drawable = image.drawable ?: return@setOnTouchListener true
            val iw = drawable.intrinsicWidth.toFloat()
            val ih = drawable.intrinsicHeight.toFloat()
            if (iw <= 0f || ih <= 0f || image.width <= 0 || image.height <= 0) return@setOnTouchListener true
            val scale = minOf(image.width / iw, image.height / ih)
            val dx = (image.width - iw * scale) / 2f
            val dy = (image.height - ih * scale) / 2f
            val px = ((event.x - dx) / scale).toInt()
            val py = ((event.y - dy) / scale).toInt()
            if (px >= 0 && py >= 0 && px < frame.width && py < frame.height) {
                handleScreenCalibration(px, py, tip)
            }
            true
        }
        calibrationRoot = root
        setContentView(root)
    }

    override fun onBackPressed() {
        if (calibrationRoot != null) {
            calibrationPoints.clear()
            closeCalibrationOverlay()
            return
        }
        super.onBackPressed()
    }

    private fun handleScreenCalibration(px: Int, py: Int, tip: TextView) {
        calibrationPoints.add(px to py)
        when (calibrationPoints.size) {
            1 -> tip.text = "已记录左上角，请点击右下角交叉点"
            2 -> {
                val p0 = calibrationPoints[0]
                val p1 = calibrationPoints[1]
                capturedBoardRect = BoardRect(
                    left = minOf(p0.first, p1.first),
                    top = minOf(p0.second, p1.second),
                    right = maxOf(p0.first, p1.first),
                    bottom = maxOf(p0.second, p1.second),
                )
                capturedBoardFlipped = flipped
                ConnectSession.setBoardRect(capturedBoardRect)
                calibrationPoints.clear()
                closeCalibrationOverlay()
                lifecycleScope.launch { recognizeOnce() }
            }
        }
    }

    private fun closeCalibrationOverlay() {
        calibrationRoot = null
        buildUi()
        refreshUi()
        renderInfo()
    }

    private suspend fun recognizeOnce() {
        val startedAt = System.currentTimeMillis()
        if (CaptureService.isRunning) {
            CaptureService.copyLatestBitmap()?.let { calibrationFrame = it }
        }
        val rect = capturedBoardRect
        if (rect == null) {
            setStatusMessage("屏幕识别：棋盘范围未标定")
            return
        }
        val frame = withContext(Dispatchers.IO) { calibrationFrame }
        if (frame == null) {
            setStatusMessage("屏幕识别：没有取到画面")
            renderInfo()
            return
        }
        val reader = if (visionMode == TemplatePieceReader.MODE_WIDE) wideReader else basicReader
        ConnectSession.provideReader(reader)
        val recognized = withContext(Dispatchers.IO) { reader.readBoard(frame, rect) }
        android.util.Log.i("Vision", "recognize: ${System.currentTimeMillis() - startedAt}ms fen=${recognized.toFen()}")
        val elapsed = System.currentTimeMillis() - startedAt
        lastRecognizedFen = recognized.toFen()
        lastRecognizedMs = elapsed
        ConnectSession.provideEngine(engine)
        ConnectSession.provideReader(reader)
        showRecognitionResult(lastRecognizedFen, elapsed)
    }

    private fun showRecognitionResult(fen: String, elapsed: Long) {
        AlertDialog.Builder(this)
            .setTitle("识别测试结果 · 方案${if (visionMode == TemplatePieceReader.MODE_WIDE) "B" else "A"} · ${elapsed}ms")
            .setMessage("FEN：\n$fen\n\n请先核对棋盘和行棋方向；确认无误后再应用。")
            .setPositiveButton("应用") { _, _ ->
                if (controller.editMode) {
                    Toast.makeText(this, "编辑模式不可应用", Toast.LENGTH_SHORT).show()
                } else if (controller.importFen(fen)) {
                    afterMoveChanged("识别结果已应用 · ${elapsed}ms")
                } else {
                    Toast.makeText(this, "FEN 应用失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("复制") { _, _ ->
                copyText(fen)
                Toast.makeText(this, "识别 FEN 已复制", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("关闭", null)
            .show()
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
        )
        AlertDialog.Builder(this).setTitle("设置").setItems(options) { _, which ->
            when (which) {
                0 -> thinkTimeDialog()
                1 -> AlertDialog.Builder(this).setTitle("搜索深度").setItems(depthLabels) { _, di -> searchDepth = depthValues[di] }.show()
                2 -> AlertDialog.Builder(this).setTitle("候选着法数").setItems(arrayOf("1", "2", "3", "4", "5")) { _, mi -> multiPv = mi + 1 }.show()
                3 -> controller.autoReply = !controller.autoReply
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

    /** 从悬浮窗等后台入口触发时，先把主界面拉到前台，避免授权/校准对话框“看不见” */
    private fun bringToFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                )
            }
            startActivity(intent)
        } catch (t: Throwable) {
            android.util.Log.w("Main", "bringToFront failed", t)
        }
    }

    private fun toastOverlay(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    /** 有截屏+校准时走连线闭环；否则退回本地持续分析 */
    private fun toggleConnectOrAnalysis() {
        val canConnect = engineReady && CaptureService.isRunning
        if (canConnect || ConnectSession.isRunning) {
            ConnectSession.useEngineLimits = false
            ConnectSession.provideEngine(engine)
            ConnectSession.provideReader(if (visionMode == TemplatePieceReader.MODE_WIDE) wideReader else basicReader)
            if (ConnectSession.isRunning) {
                ConnectSession.stop()
                analysisMode = false
                statusMessage = "连线分析已停止"
            } else {
                analysisMode = false
                analysisJob?.cancel()
                analysisJob = null
                if (!CaptureService.isRunning) {
                    bringToFront()
                    Toast.makeText(this, "屏幕识别已断开，请重新授权截屏", Toast.LENGTH_LONG).show()
                    ensurePermissionsThenOverlay()
                    return
                }
                ConnectSession.start()
                statusMessage = if (ConnectSession.boardRect == null) {
                    "连线分析已启动，自动找棋盘…"
                } else {
                    "连线分析已启动"
                }
                if (!LiveLinkService.isConnected) {
                    Toast.makeText(this, "无障碍未开：无法自动走子", Toast.LENGTH_SHORT).show()
                }
            }
            renderInfo()
            return
        }
        toggleAnalysisMode()
    }

    private fun hasOverlayPermission(): Boolean =
        android.provider.Settings.canDrawOverlays(this)

    private fun hasAccessibilityPermission(): Boolean =
        LiveLinkService.isConnected

    private fun hasCapturePermission(): Boolean {
        if (CaptureService.isRunning) return true
        // 刚授权、服务仍在启动中
        return captureGrantedAt > 0L && System.currentTimeMillis() - captureGrantedAt < 8000L
    }

    private fun permissionChecklist(): String = buildString {
        append(if (hasOverlayPermission()) "✔" else "✘")
        append(" 悬浮窗权限\n")
        append(if (hasAccessibilityPermission()) "✔" else "✘")
        append(" 无障碍权限\n")
        append(if (hasCapturePermission()) "✔" else "✘")
        append(" 屏幕识别（截屏授权）")
    }

    /** 三项全部通过才开悬浮窗；缺哪项就引导去开哪项 */
    private fun toggleOverlay() {
        if (overlayOn) {
            if (ConnectSession.isRunning) ConnectSession.stop()
            OverlayService.stop(this)
            overlayOn = false
            Toast.makeText(this, "悬浮窗已关闭", Toast.LENGTH_SHORT).show()
            return
        }
        ensurePermissionsThenOverlay()
    }

    /** 所有入口共用：先弹三项校验清单，全✔才真正开悬浮窗 */
    private fun ensurePermissionsThenOverlay() {
        val overlayOk = hasOverlayPermission()
        val a11yOk = hasAccessibilityPermission()
        val captureOk = hasCapturePermission()

        if (overlayOk && a11yOk && captureOk) {
            OverlayService.start(this)
            overlayOn = true
            Toast.makeText(this, "悬浮窗已开启", Toast.LENGTH_SHORT).show()
            updateOverlayState()
            return
        }

        val missing = buildString {
            if (!overlayOk) append("· 悬浮窗权限：去系统允许显示在其他应用上层\n")
            if (!a11yOk) append("· 无障碍：设置 → 无障碍 → 已安装的服务 → 象棋助手\n")
            if (!captureOk) append("· 屏幕识别：允许本应用截屏/录制\n")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("开启悬浮窗前需要 3 项权限")
            .setMessage("${permissionChecklist()}\n\n未完成：\n$missing")
            .setPositiveButton("去补全") { _, _ ->
                when {
                    !overlayOk -> startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName"),
                        ),
                    )
                    !a11yOk -> startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                    else -> {
                        pendingOpenOverlayAfterCapture = true
                        ensureNotificationThenCapture()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateOverlayState() {
        val svc = OverlayService.overlayDisplay ?: return
        ConnectSession.searchDepth = searchDepth
        ConnectSession.thinkMs = thinkMs
        val reader = if (visionMode == TemplatePieceReader.MODE_WIDE) wideReader else basicReader
        ConnectSession.provideReader(reader)
        ConnectSession.provideEngine(engine)
        svc.updateControls(searchDepth, thinkMs / 1000)
        svc.updateActions(LiveLinkService.isConnected, analysisMode || ConnectSession.isRunning, controller.thinking || ConnectSession.isRunning)
        svc.updateConnect(
            autoOn = ConnectSession.autoMoveOn,
            delayMs = ConnectSession.tapGapMs,
            sideLabel = if (ConnectSession.sideToMove == "w") "红方" else "黑方",
            running = ConnectSession.isRunning,
            message = ConnectSession.lastMessage,
        )
        val cloudText = if (displayCloud) {
            if (cloudLoading) "查询中" else cloudMoves.firstOrNull()?.let { Notation.moveToChinese(controller.displayPos, it.move) + " " + it.winrate } ?: "无"
        } else "关"
        val engineSummary = when {
            !engineReady -> "未就绪"
            controller.thinking -> "思考中"
            ConnectSession.lastRecognizedOk && lastResult.bestmove.isBlank() -> "已识别，分析中…"
            lastResult.bestmove.isBlank() -> "-"
            else -> lastResult.chinese(controller.displayPos)
        }
        // 出子才展示深度/时间；纯分析只展示着法
        val engineDetail = if (lastResult.bestmove.isBlank()) {
            if (ConnectSession.lastRecognizedSummary.isNotBlank()) ConnectSession.lastRecognizedSummary else ""
        } else if (ConnectSession.useEngineLimits) {
            val score = scoreTextFor(lastResult)
            val time = String.format("%.1f秒", lastResult.timeMs / 1000.0)
            val path = pvChinese(lastResult.pv).ifBlank { lastResult.chinese(controller.displayPos) }
            "深度${lastResult.depth} · $score · $time\n$path"
        } else {
            val score = scoreTextFor(lastResult)
            val path = pvChinese(lastResult.pv).ifBlank { lastResult.chinese(controller.displayPos) }
            "皮卡鱼 $score\n$path"
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
        // 显示为兵值 * 100（即原 cp 分）
        val red = value * sign
        return if (red >= 0) "红优 ${red}" else "黑优 ${-red}"
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
        // 悬浮窗/连线分析仍要工作：清掉本 Activity 的 onSnapshot，但不停 ConnectSession
        // OverlayService 自持 actions 与引擎，Activity 销毁后继续跑
        ConnectSession.onSnapshot = null
        analysisMode = false
        super.onDestroy()
    }

    companion object {
        private const val TAB_ENGINE = 0
        private const val TAB_OPENING = 1
        private const val TAB_GAME = 2
        private const val TAB_SETTINGS = 3
    }
}
