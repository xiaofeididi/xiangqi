package com.xqassist.overlay

import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.xqassist.book.BookManager
import com.xqassist.book.CloudFileInfo
import com.xqassist.book.ProCloud
import com.xqassist.capture.CaptureService
import com.xqassist.connection.ConnectSession
import com.xqassist.connection.LiveLinkService
import com.xqassist.core.Notation
import com.xqassist.core.Position
import com.xqassist.core.Quad
import com.xqassist.engine.EngineHolder
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
import com.xqassist.ui.MiniBoardView
import com.xqassist.vision.TemplatePieceReader
import com.xqassist.vision.YoloDetector
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

interface OverlayDisplay {
    fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean = false)
    fun updateControls(depth: Int, seconds: Int)
    fun updateInfo(cloud: String, engineSummary: String, engineDetail: String)
    fun updateOpacity(alpha: Float)
    fun updateConnect(autoOn: Boolean, delayMs: Int, sideLabel: String, running: Boolean, message: String)
    fun updateMiniBoard(position: Position?, hint: Quad?, status: String)
}

/**
 * 悬浮窗：对齐 Pro 双窗口
 * 1) 控制条 MATCH_PARENT×WRAP：分析行（完整 PV 中文）+ 开局库行 + 分数条
 * 2) 迷你棋盘独立窗：默认 100dp×1.2，前 2 步箭头
 */
class OverlayService : Service(), OverlayDisplay {

    interface Actions {
        fun onLink()
        fun onLinkLongPress()
        fun onAnalyze()
        fun onPlayMove()
        fun onDepthChange(delta: Int)
        fun onTimeChange(delta: Int)
        fun onOpacityChange(delta: Int)
        fun onCloseOverlay()
        fun onAutoMoveToggle()
        fun onSideToggle()
        fun onCalibrate()
    }

    companion object {
        var actions: Actions? = null
        var overlayDisplay: OverlayDisplay? = null

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val engineStarting = AtomicBoolean(false)

    private var engine: UcciEngine? = null
    private val reader by lazy { TemplatePieceReader(this, TemplatePieceReader.MODE_BASIC) }

    private var root: LinearLayout? = null
    private var mini: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var miniParams: WindowManager.LayoutParams? = null

    private var analysisText: TextView? = null
    private var bookText: TextView? = null
    private var statusText: TextView? = null
    private var scoreBarFill: View? = null
    private var depthText: TextView? = null
    private var timeText: TextView? = null
    private var opacityText: TextView? = null
    private var linkButton: Button? = null
    private var analyzeButton: Button? = null
    private var playButton: Button? = null

    private var boardView: MiniBoardView? = null
    private var boardRoot: LinearLayout? = null
    private var boardParams: WindowManager.LayoutParams? = null
    private var boardVisible = false

    private var expanded = true
    private var opacity = 1f
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var autoOn = false
    private var searchDepth = 0
    private var thinkSec = 3

    private val selfActions = object : Actions {
        override fun onLink() {
            if (LiveLinkService.isConnected) {
                toast("已连接")
            } else {
                toast("请在系统设置开启无障碍「象棋助手」")
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (t: Throwable) {
                    toast("无法打开无障碍设置")
                }
            }
            refreshButtons()
        }

        override fun onLinkLongPress() {
            if (LiveLinkService.isConnected) {
                LiveLinkService.disconnect()
                toast("连线已断开（长按）")
            } else {
                toast("未连接，无需断开")
            }
            refreshButtons()
        }

        override fun onAnalyze() {
            ConnectSession.attach(applicationContext)
            ConnectSession.provideEngine(engine)
            ConnectSession.provideReader(reader)
            BookManager.loadPrefs(applicationContext)
            if (!YoloDetector.isReady) {
                ensureYolo()
            }
            if (ConnectSession.isRunning) {
                ConnectSession.stop()
                refreshButtons()
                return
            }
            if (!CaptureService.isRunning) {
                setStatus("屏幕识别未开，请回助手重新授权截屏")
                toast("屏幕识别未开，请回助手重新授权")
                return
            }
            ConnectSession.useEngineLimits = false
            if (engine?.isReady != true) {
                setStatus("引擎启动中…")
                ensureEngine()
                toast("引擎启动中，请稍后再试")
                return
            }
            if (!LiveLinkService.isConnected) {
                toast("无障碍未开：仅分析，无法出子")
            }
            ConnectSession.start()
            refreshButtons()
        }

        override fun onPlayMove() {
            ConnectSession.attach(applicationContext)
            ConnectSession.provideEngine(engine)
            ConnectSession.provideReader(reader)
            if (ConnectSession.isRunning || ConnectSession.lastFen.isNotBlank()) {
                ConnectSession.useEngineLimits = true
                ConnectSession.searchDepth = searchDepth
                ConnectSession.thinkMs = thinkSec * 1000
                ConnectSession.playBestNow { ok ->
                    mainHandler.post {
                        toast(if (ok) "已按分析结果出子" else "出子失败：请检查无障碍/着法")
                        refreshButtons()
                    }
                }
            } else {
                setStatus("还没有识别局面，先点「分析」")
                toast("还没有识别局面，先点「分析」")
            }
        }

        override fun onDepthChange(delta: Int) {
            searchDepth = when {
                delta < 0 -> 0
                searchDepth <= 0 -> 6
                else -> (searchDepth + delta).coerceIn(6, 18)
            }
            ConnectSession.searchDepth = searchDepth
            updateControls(searchDepth, thinkSec)
            setStatus(if (searchDepth == 0) "深度不限" else "深度 $searchDepth 层")
        }

        override fun onTimeChange(delta: Int) {
            if (delta < 0) thinkSec = (thinkSec - 1).coerceAtLeast(1)
            else thinkSec = (thinkSec + 1).coerceAtMost(60)
            ConnectSession.thinkMs = thinkSec * 1000
            updateControls(searchDepth, thinkSec)
            setStatus("思考时间 ${thinkSec} 秒")
        }

        override fun onOpacityChange(delta: Int) {
            val next = if (delta < 0) {
                (opacity - 0.1f).coerceAtLeast(0.35f)
            } else {
                (opacity + 0.1f).coerceAtMost(1f)
            }
            updateOpacity(next)
        }

        override fun onCloseOverlay() {
            if (ConnectSession.isRunning) ConnectSession.stop()
            stopSelf()
        }

        override fun onAutoMoveToggle() {
            ConnectSession.attach(applicationContext)
            ConnectSession.setAutoMove(!ConnectSession.autoMoveOn)
            autoOn = ConnectSession.autoMoveOn
            toast(if (autoOn) "自动走已开启" else "自动走已关闭")
            refreshButtons()
        }

        override fun onSideToggle() {
            ConnectSession.attach(applicationContext)
            ConnectSession.toggleSide()
            toast("行棋方：${if (ConnectSession.sideToMove == "w") "红方" else "黑方"}")
        }

        override fun onCalibrate() {
            setStatus("请回助手「识别测试」完成校准；无校准时会自动找盘")
            toast("请回助手校准；无校准时会自动找盘")
        }
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        overlayDisplay = this
        actions = selfActions
        ConnectSession.attach(applicationContext)
        BookManager.loadPrefs(applicationContext)
        autoOn = ConnectSession.autoMoveOn
        searchDepth = ConnectSession.searchDepth
        thinkSec = (ConnectSession.thinkMs / 1000).coerceAtLeast(1)

        LiveLinkService.onConnectedChanged = {
            mainHandler.post { refreshButtons() }
        }

        buildUi()
        ensureEngine()
        refreshButtons()
        updateControls(searchDepth, thinkSec)
        analysisText?.text = "引擎启动中…"
        bookText?.text = "开局库：" + BookManager.summary()
        setStatus(if (ConnectSession.boardRect == null) "未校准，分析时自动找盘" else "棋盘范围已就绪")
    }

    private fun ensureYolo() {
        Thread {
            val ok = YoloDetector.init(applicationContext)
            mainHandler.post {
                setStatus(
                    if (ok) "YOLO 就绪"
                    else "YOLO 失败:${YoloDetector.lastError.ifBlank { "?" }}"
                )
            }
        }.start()
    }

    private fun startAsForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel("xq_overlay", "悬浮窗", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
            val notif = Notification.Builder(this, "xq_overlay")
                .setContentTitle("象棋助手")
                .setContentText("悬浮窗连线分析运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(3, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(3, notif)
            }
        } catch (t: Throwable) {
            android.util.Log.w("Overlay", "startForeground failed", t)
        }
    }

    private fun ensureEngine() {
        val ready = EngineHolder.engine
        if (ready?.isReady == true) {
            engine = ready
            ConnectSession.provideEngine(ready)
            ConnectSession.provideReader(reader)
            setStatus("皮卡鱼就绪")
            return
        }
        if (!engineStarting.compareAndSet(false, true)) return
        EngineHolder.ensure(applicationContext) { eng ->
            mainHandler.post {
                engineStarting.set(false)
                if (eng != null && eng.isReady) {
                    engine = eng
                    ConnectSession.provideEngine(eng)
                    ConnectSession.provideReader(reader)
                    setStatus("皮卡鱼就绪")
                } else {
                    setStatus("皮卡鱼启动失败")
                }
                refreshButtons()
            }
        }
    }

    private fun buildUi() {
        val metrics = resources.displayMetrics
        fun dp(value: Int) = (value * metrics.density).toInt()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF218202A.toInt())
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        panel.addView(card)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply {
            text = "≡ 象棋助手"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        header.addView(title)
        header.addView(miniButton("棋") { toggleBoard() })
        header.addView(miniButton("库") { showBookDialog() })
        header.addView(miniButton("下") { showDownloadDialog() })
        header.addView(miniButton("—") { setExpanded(false) })
        header.addView(miniButton("×") { selfActions.onCloseOverlay() })
        card.addView(header)
        card.addView(spacer(dp(4)))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkButton = actionButton("连线")
        analyzeButton = actionButton("分析")
        playButton = actionButton("出子")
        buttons.addView(linkButton)
        buttons.addView(analyzeButton)
        buttons.addView(playButton)
        card.addView(buttons)
        card.addView(spacer(dp(4)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        controls.addView(controlTile("深", "不限", 1.0f) { delta -> selfActions.onDepthChange(delta) })
        controls.addView(controlTile("时", "3秒", 1.0f) { delta -> selfActions.onTimeChange(delta) })
        controls.addView(controlTile("透明", "100%", 1.2f) { delta -> selfActions.onOpacityChange(delta) })
        card.addView(controls)
        card.addView(spacer(dp(4)))

        analysisText = TextView(this).apply {
            text = "皮卡鱼 —"
            textSize = 12.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setLineSpacing(dp(2).toFloat(), 1.05f)
            maxLines = 4
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        card.addView(analysisText!!)

        val barWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply {
                topMargin = dp(4)
            }
            background = GradientDrawable().apply {
                setColor(0xFF3B3A3C.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        scoreBarFill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
            background = GradientDrawable().apply {
                setColor(0xFFE752C8.toInt())
                cornerRadius = dp(3).toFloat()
            }
        }
        barWrap.addView(scoreBarFill!!)
        card.addView(barWrap)
        card.addView(spacer(dp(4)))

        bookText = TextView(this).apply {
            text = "开局库：" + BookManager.summary()
            textSize = 11.5f
            setTextColor(0xFF6AFFCD.toInt())
            maxLines = 2
        }
        card.addView(bookText!!)

        statusText = TextView(this).apply {
            text = "待命"
            textSize = 11f
            setTextColor(0xB3FFFFFF.toInt())
            maxLines = 2
        }
        card.addView(statusText!!)

        val resize = TextView(this).apply {
            text = "↘"
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(resize)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(4)
        p.y = dp(72)

        title.setOnTouchListener { _, event -> moveHandler(event, p, panel) }
        resize.setOnTouchListener { _, event -> resizeHandler(event, p, panel, metrics.widthPixels - dp(8)) }

        linkButton?.setOnClickListener { selfActions.onLink() }
        linkButton?.setOnLongClickListener { selfActions.onLinkLongPress(); true }
        analyzeButton?.setOnClickListener { selfActions.onAnalyze() }
        analyzeButton?.setOnLongClickListener { selfActions.onSideToggle(); true }
        playButton?.setOnClickListener { selfActions.onPlayMove() }
        playButton?.setOnLongClickListener { selfActions.onAutoMoveToggle(); true }

        val miniView = TextView(this).apply {
            text = "≡ 象棋"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(9), dp(7), dp(9), dp(7))
            background = GradientDrawable().apply {
                setColor(0xE618202A.toInt())
                cornerRadius = dp(12).toFloat()
            }
            visibility = View.GONE
        }
        val mp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        mp.gravity = Gravity.TOP or Gravity.START
        mp.x = p.x
        mp.y = p.y
        miniView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initialX = mp.x; initialY = mp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val movedX = event.rawX.toInt() - startX
                    val movedY = event.rawY.toInt() - startY
                    mp.x = initialX + movedX
                    mp.y = initialY + movedY
                    if (movedX != 0 || movedY != 0) wm.updateViewLayout(miniView, mp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX.toInt() - startX) < dp(8) &&
                        kotlin.math.abs(event.rawY.toInt() - startY) < dp(8)
                    if (moved) setExpanded(true)
                    true
                }
                else -> false
            }
        }

        wm.addView(panel, p)
        wm.addView(miniView, mp)
        root = panel
        params = p
        mini = miniView
        miniParams = mp

        buildBoardWindow()
        applyOpacity()
    }

    private fun buildBoardWindow() {
        val density = resources.displayMetrics.density
        val w = (100 * density).toInt()
        val h = (w * 1.2f).toInt()
        fun dp(value: Int) = (value * density).toInt()
        val board = MiniBoardView(this)
        boardView = board
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = GradientDrawable().apply {
                setColor(0xE618202A.toInt())
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        card.addView(board, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val grip = TextView(this).apply {
            text = "↘"
            textSize = 11f
            setTextColor(0x80FFFFFF.toInt())
            gravity = Gravity.END
        }
        card.addView(grip)
        wrap.addView(card)

        val bp = WindowManager.LayoutParams(
            w,
            h,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        bp.gravity = Gravity.TOP or Gravity.END
        bp.x = dp(8)
        bp.y = dp(160)

        board.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initialX = bp.x; initialY = bp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    bp.x = initialX - (event.rawX.toInt() - startX)
                    bp.y = initialY + (event.rawY.toInt() - startY)
                    wm.updateViewLayout(wrap, bp)
                    true
                }
                else -> false
            }
        }
        grip.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt(); startY = event.rawY.toInt()
                    initialWidth = bp.width
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nw = (initialWidth - (event.rawX.toInt() - startX)).coerceIn(dp(60), dp(240))
                    bp.width = nw
                    bp.height = (nw * 1.2f).toInt()
                    wm.updateViewLayout(wrap, bp)
                    true
                }
                else -> false
            }
        }
        boardRoot = wrap
        boardParams = bp
        boardVisible = false
    }

    private fun toggleBoard() {
        val wrap = boardRoot ?: return
        val bp = boardParams ?: return
        if (boardVisible) {
            runCatching { wm.removeView(wrap) }
            boardVisible = false
            toast("迷你棋盘已隐藏")
        } else {
            runCatching { wm.addView(wrap, bp) }
            boardVisible = true
            toast("迷你棋盘已显示（拖↘缩放）")
            refreshMiniBoard()
        }
    }

    private fun overlayDialog(): AlertDialog.Builder {
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        return AlertDialog.Builder(ctx)
    }

    private fun showBookDialog() {
        BookManager.loadPrefs(applicationContext)
        val localNames = BookManager.listLocal(this).map { it.name }
        val items = mutableListOf("云库 chessdb（在线）")
        items += localNames.map { name ->
            if (BookManager.mode == 1 && BookManager.bookName == name) "★ $name（当前）" else name
        }
        items += "下载开局库…"
        overlayDialog()
            .setTitle("切换开局库 · " + BookManager.summary())
            .setItems(items.toTypedArray()) { _, which ->
                when {
                    which == 0 -> {
                        BookManager.useCloud(applicationContext)
                        BookManager.clearHit()
                        bookText?.text = "开局库：" + BookManager.summary()
                        toast("已切换云库")
                    }
                    which <= localNames.size -> {
                        val name = localNames[which - 1]
                        val ok = BookManager.openLocal(applicationContext, name)
                        BookManager.clearHit()
                        bookText?.text = "开局库：" + BookManager.summary()
                        toast(if (ok) "已切换到 $name" else "打开失败：$name")
                    }
                    else -> showDownloadDialog(bookOnly = true)
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showDownloadDialog(bookOnly: Boolean = false) {
        Thread {
            try {
                val engines = if (bookOnly) emptyList() else ProCloud.fetchList("engine")
                val books = ProCloud.fetchList("openBook")
                mainHandler.post { pickAndDownload(engines, books, bookOnly) }
            } catch (t: Throwable) {
                mainHandler.post { toast("列表获取失败：${t.message}") }
            }
        }.start()
    }

    private fun pickAndDownload(
        engines: List<CloudFileInfo>,
        books: List<CloudFileInfo>,
        bookOnly: Boolean,
    ) {
        val labels = mutableListOf<String>()
        val targets = mutableListOf<Pair<String, CloudFileInfo>>()
        if (!bookOnly) {
            engines.forEach {
                labels += "引擎 ${it.name} / ${it.fileName} (${it.size / 1024 / 1024}MB)"
                targets += "engine" to it
            }
        }
        books.forEach {
            labels += "开局库 ${it.name} (${it.size / 1024}KB)"
            targets += "book" to it
        }
        if (labels.isEmpty()) {
            toast("无可下载项")
            return
        }
        overlayDialog()
            .setTitle("下载引擎 / 开局库")
            .setItems(labels.toTypedArray()) { _, which ->
                val (kind, info) = targets[which]
                startDownload(kind, info)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startDownload(kind: String, info: CloudFileInfo) {
        val dir = if (kind == "engine") File(filesDir, "engine") else BookManager.booksDir(this)
        dir.mkdirs()
        val dest = File(dir, info.fileName)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val pct = TextView(this).apply { text = "0%" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
            addView(TextView(this@OverlayService).apply { text = info.name })
            addView(bar)
            addView(pct)
        }
        val dlg = overlayDialog()
            .setTitle("下载 ${info.fileName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .create()
        dlg.show()
        Thread {
            val ok = ProCloud.download(info.url, dest) { p ->
                mainHandler.post {
                    bar.progress = p
                    pct.text = "$p%"
                }
            }
            mainHandler.post {
                dlg.dismiss()
                if (ok) {
                    if (kind == "book") {
                        BookManager.openLocal(applicationContext, dest.name)
                        bookText?.text = "开局库：" + BookManager.summary()
                    }
                    toast("下载完成 ${dest.name}")
                } else {
                    toast("下载失败")
                }
            }
        }.start()
    }

    private fun moveHandler(event: MotionEvent, p: WindowManager.LayoutParams, panel: LinearLayout): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt(); startY = event.rawY.toInt()
                initialX = p.x; initialY = p.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = initialX + (event.rawX.toInt() - startX)
                p.y = initialY + (event.rawY.toInt() - startY)
                wm.updateViewLayout(panel, p)
                return true
            }
        }
        return false
    }

    private fun resizeHandler(
        event: MotionEvent,
        p: WindowManager.LayoutParams,
        panel: LinearLayout,
        maxWidth: Int,
    ): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt(); startY = event.rawY.toInt()
                initialWidth = p.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val width = initialWidth + (event.rawX.toInt() - startX)
                val clampedWidth = width.coerceIn((maxWidth / 3).coerceAtLeast(200), maxWidth)
                p.width = clampedWidth
                p.height = WindowManager.LayoutParams.WRAP_CONTENT
                wm.updateViewLayout(panel, p)
                return true
            }
        }
        return false
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        root?.visibility = if (value) View.VISIBLE else View.GONE
        mini?.visibility = if (value) View.GONE else View.VISIBLE
    }

    private fun controlTile(label: String, initial: String, weight: Float, onDelta: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
                marginEnd = dp3()
            }
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF.toInt())
                cornerRadius = dp7().toFloat()
            }
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 10.5f
            setTextColor(0xB3FFFFFF.toInt())
        })
        val value = TextView(this).apply {
            text = initial
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp2()
                marginEnd = dp2()
            }
        }
        row.addView(value)
        when (label) {
            "深" -> depthText = value
            "时" -> timeText = value
            "透明" -> opacityText = value
        }
        row.addView(miniButton("-") { onDelta(-1) })
        row.addView(miniButton("+") { onDelta(1) })
        return row
    }

    private fun actionButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 12f
        isAllCaps = false
        includeFontPadding = false
        setPadding(0, dp8(), 0, dp8())
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = roundBackground(0xFF39465A.toInt(), dp8().toFloat())
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = dp8()
        }
    }

    private fun miniButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 9f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(5), 0, dp(5), 0)
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = roundBackground(0xFF334154.toInt(), dp7().toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
            marginStart = dp(2)
        }
        setOnClickListener { action() }
    }

    private fun roundBackground(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    private fun spacer(height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    }

    private fun dp8(): Int = (8 * resources.displayMetrics.density).toInt()
    private fun dp7(): Int = (7 * resources.displayMetrics.density).toInt()
    private fun dp3(): Int = (3 * resources.displayMetrics.density).toInt()
    private fun dp2(): Int = (2 * resources.displayMetrics.density).toInt()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(message: String) {
        statusText?.text = message
    }

    private fun applyOpacity() {
        root?.alpha = opacity
        mini?.alpha = opacity
        boardRoot?.alpha = opacity
        opacityText?.text = (opacity * 100).toInt().toString() + "%"
    }

    private fun setScoreBar(scoreCp: Int?) {
        val fill = scoreBarFill ?: return
        val lp = fill.layoutParams as LinearLayout.LayoutParams
        val ratio = when {
            scoreCp == null -> 0.5f
            else -> {
                val c = scoreCp.coerceIn(-1000, 1000)
                0.05f + (c + 1000) / 2000f * 0.9f
            }
        }
        lp.weight = ratio
        fill.layoutParams = lp
    }

    private fun refreshButtons() {
        val connected = LiveLinkService.isConnected
        val running = ConnectSession.isRunning
        autoOn = ConnectSession.autoMoveOn
        linkButton?.apply {
            text = if (connected) "已连接" else "连线"
            background = roundBackground(
                if (connected) 0xFF2E7D32.toInt() else 0xFF39465A.toInt(),
                dp8().toFloat(),
            )
        }
        analyzeButton?.apply {
            text = when {
                running && autoOn -> "分析·自动"
                running -> "分析·开"
                autoOn -> "分析(自动)"
                else -> "分析"
            }
            background = roundBackground(
                if (running) 0xFF1565C0.toInt()
                else if (autoOn) 0xFF6A1B9A.toInt()
                else 0xFF39465A.toInt(),
                dp8().toFloat(),
            )
        }
        playButton?.apply {
            val ok = ConnectSession.lastRecognizedOk
            text = if (ok) "✓出子" else "出子"
        }
    }

    private fun refreshMiniBoard() {
        val board = ConnectSession.lastBoard
        val result = ConnectSession.lastResult
        val hints = if (result.fen == ConnectSession.lastFen && result.bestmove.isNotBlank()) {
            ConnectSession.lastHintMoves
        } else {
            emptyList()
        }
        boardView?.position = board
        boardView?.multiHints = hints
        boardView?.statusLine = if (hints.isEmpty()) ConnectSession.lastMessage
        else buildProAnalysisLine(result, board)
    }

    private fun buildProAnalysisLine(result: EngineResult, board: Position?): String {
        val score = when {
            result.mateIn != null -> "绝杀(${result.mateIn})"
            result.scoreCp != null -> "${result.scoreCp}"
            else -> "-"
        }
        val npsK = if (result.nps > 0) "[${result.nps / 1000}k]" else ""
        val pvText = if (board != null && result.pv.isNotEmpty()) {
            val sim = board.copy()
            result.pv.take(8).joinToString("  ") { m ->
                val cn = try { Notation.moveToChinese(sim, m) } catch (_: Throwable) { m }
                try { sim.applyIccs(m) } catch (_: Throwable) {}
                cn
            }
        } else {
            result.pv.joinToString(" ")
        }
        return "$score (${result.depth}) $npsK $pvText".trim()
    }

    private fun refreshBookLine() {
        val hit = BookManager.lastHit
        val board = ConnectSession.lastBoard
        bookText?.text = if (hit != null && board != null) {
            "开局库：${hit.chinese(board)}  ${hit.score}分  胜${"%.0f".format(hit.winRate)}%  [${hit.source}]"
        } else {
            "开局库：" + BookManager.summary()
        }
    }

    override fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean) {
        refreshButtons()
    }

    override fun updateControls(depth: Int, seconds: Int) {
        depthText?.text = if (depth <= 0) "不限" else depth.toString() + "层"
        timeText?.text = seconds.toString() + "秒"
    }

    override fun updateInfo(cloud: String, engineSummary: String, engineDetail: String) {
        analysisText?.text = engineDetail.ifBlank { engineSummary }.ifBlank { "皮卡鱼 —" }
        if (cloud.isNotBlank()) bookText?.text = cloud
    }

    override fun updateOpacity(alpha: Float) {
        opacity = alpha.coerceIn(0.35f, 1f)
        applyOpacity()
    }

    override fun updateConnect(
        autoOnValue: Boolean,
        delayMsValue: Int,
        sideLabel: String,
        running: Boolean,
        message: String,
    ) {
        mainHandler.post {
            autoOn = autoOnValue
            setStatus(message.ifBlank { sideLabel })
            val engReady = engine?.isReady == true
            val result = ConnectSession.lastResult
            val board = ConnectSession.lastBoard
            val bookHit = BookManager.lastHit
            // 只显示与当前局面匹配的结果，避免旧着法串台
            val resultFresh = result.bestmove.isNotBlank() && result.fen.isNotBlank() && result.fen == ConnectSession.lastFen
            analysisText?.text = when {
                bookHit != null && board != null && resultFresh && result.bestmove == bookHit.move ->
                    "开局库 ${bookHit.chinese(board)}  ${bookHit.score}分  胜${"%.0f".format(bookHit.winRate)}%  [${bookHit.source}]"
                !engReady -> "皮卡鱼启动中…"
                !resultFresh -> {
                    if (message.contains("分析中")) "皮卡鱼思考中…" else "皮卡鱼 —"
                }
                else -> buildProAnalysisLine(result, board)
            }
            setScoreBar(if (resultFresh) result.scoreCp else null)
            refreshBookLine()
            refreshMiniBoard()
            refreshButtons()
        }
    }

    override fun updateMiniBoard(position: Position?, hint: Quad?, status: String) {
        mainHandler.post {
            boardView?.position = position
            if (hint != null) boardView?.multiHints = listOf(hint)
            boardView?.statusLine = status
        }
    }

    override fun onDestroy() {
        if (ConnectSession.isRunning) ConnectSession.stop()
        if (actions === selfActions) actions = null
        overlayDisplay = null
        root?.let { runCatching { wm.removeView(it) } }
        mini?.let { runCatching { wm.removeView(it) } }
        boardRoot?.let { runCatching { wm.removeView(it) } }
        root = null
        mini = null
        boardRoot = null
        params = null
        miniParams = null
        boardParams = null
        analysisText = null
        bookText = null
        statusText = null
        depthText = null
        timeText = null
        opacityText = null
        linkButton = null
        analyzeButton = null
        playButton = null
        boardView = null
        scoreBarFill = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
