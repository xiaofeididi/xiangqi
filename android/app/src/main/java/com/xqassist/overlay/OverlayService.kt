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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
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
import com.xqassist.engine.EngineHolder
import com.xqassist.engine.EngineResult
import com.xqassist.engine.UcciEngine
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
}

/**
 * 悬浮窗 v0.5：
 * - 无迷你棋盘
 * - 标题仅 — / ×
 * - 分析=开/关；出子=独立；分析循环不用深度/时间
 * - 开局库行 +「启用库」
 * - 深度/时间/透明为下拉
 */
class OverlayService : Service(), OverlayDisplay {

    interface Actions {
        fun onLink()
        fun onAnalyze()
        fun onPlayMove()
        fun onDepthSelect(index: Int)
        fun onTimeSelect(index: Int)
        fun onOpacitySelect(index: Int)
        fun onCloseOverlay()
        fun onBookEnableToggle()
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
    private var linkButton: Button? = null
    private var analyzeButton: Button? = null
    private var playButton: Button? = null
    private var bookToggle: Button? = null
    private var depthSpin: Spinner? = null
    private var timeSpin: Spinner? = null
    private var opacitySpin: Spinner? = null

    private var expanded = true
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0

    private val depthLabels = listOf("深·不限", "深·8", "深·12", "深·16")
    private val depthValues = listOf(0, 8, 12, 16)
    private val timeLabels = listOf("时·1s", "时·3s", "时·5s", "时·10s")
    private val timeValues = listOf(1, 3, 5, 10)
    private val opacityLabels = listOf("透·100%", "透·85%", "透·70%")
    private val opacityValues = listOf(1f, 0.85f, 0.7f)

    private val selfActions = object : Actions {
        override fun onLink() {
            if (LiveLinkService.isConnected) {
                toast("已连接")
            } else {
                toast("请开启无障碍「象棋助手」")
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

        override fun onAnalyze() {
            ConnectSession.attach(applicationContext)
            ConnectSession.provideEngine(engine)
            ConnectSession.provideReader(reader)
            BookManager.loadPrefs(applicationContext)
            if (!YoloDetector.isReady) ensureYolo()
            if (ConnectSession.isRunning) {
                ConnectSession.stop()
                refreshButtons()
                return
            }
            if (!CaptureService.isRunning) {
                setStatus("屏幕识别未开，请回助手授权截屏")
                toast("屏幕识别未开")
                return
            }
            ConnectSession.useEngineLimits = false
            if (engine?.isReady != true) {
                setStatus("引擎启动中…")
                ensureEngine()
                toast("引擎启动中")
                return
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
                val d = depthValues[depthSpin?.selectedItemPosition ?: 0]
                val t = timeValues[timeSpin?.selectedItemPosition ?: 1]
                ConnectSession.searchDepth = d
                ConnectSession.thinkMs = t * 1000
                ConnectSession.playBestNow { ok ->
                    mainHandler.post {
                        toast(if (ok) "已出子" else "出子失败")
                        refreshButtons()
                    }
                }
            } else {
                setStatus("先点「分析」")
                toast("还没有识别局面")
            }
        }

        override fun onDepthSelect(index: Int) {
            val d = depthValues.getOrElse(index) { 0 }
            ConnectSession.searchDepth = d
            toast(if (d == 0) "深度不限" else "深度 $d")
        }

        override fun onTimeSelect(index: Int) {
            val t = timeValues.getOrElse(index) { 3 }
            ConnectSession.thinkMs = t * 1000
            toast("时间 ${t}s")
        }

        override fun onOpacitySelect(index: Int) {
            val a = opacityValues.getOrElse(index) { 1f }
            updateOpacity(a)
        }

        override fun onCloseOverlay() {
            if (ConnectSession.isRunning) ConnectSession.stop()
            stopSelf()
        }

        override fun onBookEnableToggle() {
            BookManager.enabled = !BookManager.enabled
            BookManager.savePrefs(applicationContext)
            BookManager.clearHit()
            refreshBookLine()
            toast(if (BookManager.enabled) "启用开局库：有库着优先走库" else "已关开局库")
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            startAsForeground()
            overlayDisplay = this
            actions = selfActions
            ConnectSession.attach(applicationContext)
            BookManager.loadPrefs(applicationContext)

            LiveLinkService.onConnectedChanged = { mainHandler.post { refreshButtons() } }

            buildUi()
            ensureEngine()
            refreshButtons()
            analysisText?.text = "皮卡鱼 —"
            bookText?.text = BookManager.summary()
            setStatus(if (ConnectSession.boardRect == null) "未校准，分析时自动找盘" else "棋盘范围已就绪")
        } catch (t: Throwable) {
            android.util.Log.e("Overlay", "onCreate failed", t)
            Toast.makeText(this, "悬浮窗启动失败：${t.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun ensureYolo() {
        Thread {
            val ok = YoloDetector.init(applicationContext)
            mainHandler.post {
                setStatus(if (ok) "YOLO 就绪" else "YOLO 失败:${YoloDetector.lastError.ifBlank { "?" }}")
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
            setPadding(dp(6), dp(3), dp(6), dp(4))
            background = GradientDrawable().apply {
                setColor(0xE618202A.toInt())
                cornerRadius = dp(8).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        panel.addView(card)

        // 第1行：标题 + 动作 + 收起/关
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "助手"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row1.addView(title)
        linkButton = tinyButton("连")
        analyzeButton = tinyButton("析")
        playButton = tinyButton("出")
        row1.addView(linkButton)
        row1.addView(analyzeButton)
        row1.addView(playButton)
        row1.addView(miniButton("—") { setExpanded(false) })
        row1.addView(miniButton("×") { selfActions.onCloseOverlay() })
        card.addView(row1)

        // 第2行：下拉
        val ctrlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, 0)
        }
        depthSpin = spinner(depthLabels, 0).also { ctrlRow.addView(weighted(it, 1f)) }
        timeSpin = spinner(timeLabels, 1).also { ctrlRow.addView(weighted(it, 1f)) }
        opacitySpin = spinner(opacityLabels, 1).also { ctrlRow.addView(weighted(it, 1f)) }
        card.addView(ctrlRow)

        // 第3行：招法
        analysisText = TextView(this).apply {
            text = "皮卡鱼 —"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(3), 0, dp(1))
        }
        card.addView(analysisText!!)

        val barWrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            background = GradientDrawable().apply {
                setColor(0xFF3B3A3C.toInt())
                cornerRadius = 1.5f * resources.displayMetrics.density
            }
        }
        scoreBarFill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
            background = GradientDrawable().apply {
                setColor(0xFFE752C8.toInt())
                cornerRadius = 1.5f * resources.displayMetrics.density
            }
        }
        barWrap.addView(scoreBarFill!!)
        card.addView(barWrap)

        // 第4行：开局库
        val bookRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, 0)
        }
        bookText = TextView(this).apply {
            text = BookManager.summary()
            textSize = 11f
            setTextColor(0xFF6AFFCD.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        bookToggle = tinyButton("库")
        bookRow.addView(bookText!!)
        bookRow.addView(bookToggle!!)
        card.addView(bookRow)

        statusText = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(0x80FFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        card.addView(statusText!!)

        val resize = TextView(this).apply {
            text = "↘"
            textSize = 11f
            setTextColor(0x66FFFFFF.toInt())
            gravity = Gravity.END
            setPadding(0, 0, 0, 0)
        }
        card.addView(resize)

        // 窄条：约 46% 屏宽，贴顶，尽量不盖棋盘
        val baseW = (metrics.widthPixels * 0.46f).toInt().coerceIn(dp(170), dp(240))
        val p = WindowManager.LayoutParams(
            baseW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(4)
        p.y = dp(4)

        title.setOnTouchListener { _, event -> moveHandler(event, p, panel) }
        resize.setOnTouchListener { _, event -> resizeHandler(event, p, panel, metrics.widthPixels - dp(8)) }

        linkButton?.setOnClickListener { selfActions.onLink() }
        analyzeButton?.setOnClickListener { selfActions.onAnalyze() }
        playButton?.setOnClickListener { selfActions.onPlayMove() }

        val miniView = TextView(this).apply {
            text = "≡"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
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
                    mp.x = initialX + (event.rawX.toInt() - startX)
                    mp.y = initialY + (event.rawY.toInt() - startY)
                    wm.updateViewLayout(miniView, mp)
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
        android.util.Log.i("Overlay", "ui added w=${p.width} y=${p.y}")

        depthSpin?.onItemSelectedListener = spinnerListener { selfActions.onDepthSelect(it) }
        timeSpin?.onItemSelectedListener = spinnerListener { selfActions.onTimeSelect(it) }
        opacitySpin?.onItemSelectedListener = spinnerListener { selfActions.onOpacitySelect(it) }

        refreshBookLine()
        applyOpacity(0.85f)
    }

    private fun spinner(labels: List<String>, selected: Int): Spinner {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
        return Spinner(themed).apply {
            adapter = ArrayAdapter(
                themed,
                android.R.layout.simple_spinner_dropdown_item,
                labels,
            )
            setSelection(selected.coerceIn(0, labels.lastIndex))
        }
    }

    private fun weighted(v: View, w: Float) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w).let {
        v.layoutParams = it
        v
    }

    private fun spinnerListener(cb: (Int) -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            cb(position)
        }
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
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
                p.width = width.coerceIn((maxWidth / 3).coerceAtLeast(200), maxWidth)
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

    private fun tinyButton(label: String): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(6), dp(2), dp(6), dp(2))
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = roundBackground(0xFF39465A.toInt(), dp(5).toFloat())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = dp(3) }
    }

    private fun miniButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(8), 0, dp(8), 0)
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = roundBackground(0xFF334154.toInt(), dp7().toFloat())
        layoutParams = LinearLayout.LayoutParams(dp(24), dp(22)).apply { marginStart = dp(3) }
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
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(message: String) {
        statusText?.text = message
    }

    private fun applyOpacity(a: Float) {
        val alpha = a.coerceIn(0.35f, 1f)
        root?.alpha = alpha
        mini?.alpha = alpha
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
        linkButton?.apply {
            text = if (connected) "连✓" else "连"
            background = roundBackground(
                if (connected) 0xFF2E7D32.toInt() else 0xFF39465A.toInt(),
                dp(5).toFloat(),
            )
        }
        analyzeButton?.apply {
            text = if (running) "析·" else "析"
            background = roundBackground(
                if (running) 0xFF1565C0.toInt() else 0xFF39465A.toInt(),
                dp(5).toFloat(),
            )
        }
        playButton?.apply {
            text = if (ConnectSession.lastRecognizedOk) "出✓" else "出"
        }
        depthSpin?.alpha = if (running) 0.35f else 1f
        timeSpin?.alpha = if (running) 0.35f else 1f
    }

    private fun refreshBookLine() {
        val hit = BookManager.lastHit
        val board = ConnectSession.lastBoard
        val name = BookManager.summary()
        bookText?.text = if (hit != null && board != null && BookManager.enabled) {
            "${hit.chinese(board)} · $name"
        } else {
            name + if (BookManager.enabled) "" else "·关"
        }
        bookToggle?.apply {
            text = if (BookManager.enabled) "库开" else "库"
            background = roundBackground(
                if (BookManager.enabled) 0xFF2E7D32.toInt() else 0xFF334154.toInt(),
                dp(5).toFloat(),
            )
        }
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

    override fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean) {
        refreshButtons()
    }

    override fun updateControls(depth: Int, seconds: Int) {
        val di = depthValues.indexOf(depth).takeIf { it >= 0 } ?: 0
        val ti = timeValues.indexOf(seconds).takeIf { it >= 0 } ?: 1
        depthSpin?.setSelection(di)
        timeSpin?.setSelection(ti)
    }

    override fun updateInfo(cloud: String, engineSummary: String, engineDetail: String) {
        analysisText?.text = engineDetail.ifBlank { engineSummary }.ifBlank { "皮卡鱼 —" }
        if (cloud.isNotBlank()) bookText?.text = cloud
    }

    override fun updateOpacity(alpha: Float) {
        applyOpacity(alpha)
        val oi = opacityValues.indexOfFirst { kotlin.math.abs(it - alpha) < 0.02f }
        if (oi >= 0) opacitySpin?.setSelection(oi)
    }

    override fun updateConnect(
        autoOnValue: Boolean,
        delayMsValue: Int,
        sideLabel: String,
        running: Boolean,
        message: String,
    ) {
        mainHandler.post {
            setStatus(message.ifBlank { sideLabel })
            val engReady = engine?.isReady == true
            val result = ConnectSession.lastResult
            val board = ConnectSession.lastBoard
            val bookHit = BookManager.lastHit
            val resultFresh = result.bestmove.isNotBlank() &&
                result.fen.isNotBlank() &&
                result.fen == ConnectSession.lastFen
            analysisText?.text = when {
                BookManager.enabled && bookHit != null && board != null && resultFresh &&
                    result.bestmove == bookHit.move ->
                    "开局库 ${bookHit.chinese(board)}  ${bookHit.score}分  [${bookHit.source}]"
                !engReady -> "皮卡鱼启动中…"
                !resultFresh -> if (message.contains("分析中")) "皮卡鱼思考中…" else "皮卡鱼 —"
                else -> buildProAnalysisLine(result, board)
            }
            setScoreBar(if (resultFresh) result.scoreCp else null)
            refreshBookLine()
            refreshButtons()
        }
    }

    override fun onDestroy() {
        if (ConnectSession.isRunning) ConnectSession.stop()
        if (actions === selfActions) actions = null
        overlayDisplay = null
        root?.let { runCatching { wm.removeView(it) } }
        mini?.let { runCatching { wm.removeView(it) } }
        root = null
        mini = null
        params = null
        miniParams = null
        analysisText = null
        bookText = null
        statusText = null
        linkButton = null
        analyzeButton = null
        playButton = null
        bookToggle = null
        depthSpin = null
        timeSpin = null
        opacitySpin = null
        scoreBarFill = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
