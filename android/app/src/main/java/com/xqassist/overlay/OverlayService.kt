package com.xqassist.overlay

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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.xqassist.book.BookManager
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
import java.util.concurrent.atomic.AtomicBoolean

interface OverlayDisplay {
    fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean = false)
    fun updateControls(depth: Int, seconds: Int)
    fun updateInfo(cloud: String, engineSummary: String, engineDetail: String)
    fun updateOpacity(alpha: Float)
    fun updateConnect(autoOn: Boolean, delayMs: Int, sideLabel: String, running: Boolean, message: String)
}

/**
 * 悬浮窗：Pro 风格信息条
 * - 无开局库控件（库在主界面/设置）
 * - 分析开/关；出子独立
 * - 深度/时间下拉；仅出子时生效
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
    private var statusText: TextView? = null
    private var scoreBarFill: View? = null
    private var linkButton: Button? = null
    private var analyzeButton: Button? = null
    private var playButton: Button? = null
    private var depthSpin: Spinner? = null
    private var timeSpin: Spinner? = null

    private var expanded = true
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0

    private val depthLabels = listOf("深 不限", "深 8", "深 12", "深 16")
    private val depthValues = listOf(0, 8, 12, 16)
    private val timeLabels = listOf("时 1s", "时 3s", "时 5s", "时 10s")
    private val timeValues = listOf(1, 3, 5, 10)

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
                setStatus("截屏未授权")
                toast("屏幕识别未开")
                return
            }
            ConnectSession.useEngineLimits = false
            if (engine?.isReady != true) {
                setStatus("引擎启动中")
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
                ConnectSession.searchDepth = depthValues[depthSpin?.selectedItemPosition ?: 0]
                ConnectSession.thinkMs = timeValues[timeSpin?.selectedItemPosition ?: 1] * 1000
                ConnectSession.playBestNow { ok ->
                    mainHandler.post {
                        toast(if (ok) "已出子" else "出子失败")
                        refreshButtons()
                    }
                }
            } else {
                toast("先点「析」开始分析")
            }
        }

        override fun onDepthSelect(index: Int) {
            ConnectSession.searchDepth = depthValues.getOrElse(index) { 0 }
        }

        override fun onTimeSelect(index: Int) {
            ConnectSession.thinkMs = timeValues.getOrElse(index) { 3 } * 1000
        }

        override fun onOpacitySelect(index: Int) {
            // 仅透明度，由主界面/设置也可调
        }

        override fun onCloseOverlay() {
            if (ConnectSession.isRunning) ConnectSession.stop()
            stopSelf()
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
            setStatus("待命")
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
                setStatus(if (ok) "YOLO 就绪" else "YOLO 失败")
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
                .setContentText("连线分析运行中")
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
                }
                refreshButtons()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF01B2430.toInt())
                cornerRadius = 12f * density
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        panel.addView(card)

        // 标题 + 操作
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "象棋助手"
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0x99FFFFFF.toInt())
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(title)
        linkButton = chip("连")
        analyzeButton = chip("析")
        playButton = chip("出")
        top.addView(linkButton)
        top.addView(analyzeButton)
        top.addView(playButton)
        top.addView(chip("—") { setExpanded(false) })
        top.addView(chip("×") { selfActions.onCloseOverlay() })
        card.addView(top)

        // 设置行
        val limits = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        depthSpin = makeSpinner(depthLabels, 0).also {
            limits.addView(it, LinearLayout.LayoutParams(0, dp(28), 1f).apply { marginEnd = dp(4) })
        }
        timeSpin = makeSpinner(timeLabels, 1).also {
            limits.addView(it, LinearLayout.LayoutParams(0, dp(28), 1f))
        }
        card.addView(limits)

        // 招法
        analysisText = TextView(this).apply {
            text = "皮卡鱼 —"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, dp(4))
        }
        card.addView(analysisText!!)

        // 分数条
        val barBg = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3))
            background = GradientDrawable().apply {
                setColor(0xFF2A3340.toInt())
                cornerRadius = 2f * density
            }
        }
        scoreBarFill = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.5f)
            background = GradientDrawable().apply {
                setColor(0xFF3DDC97.toInt())
                cornerRadius = 2f * density
            }
        }
        barBg.addView(scoreBarFill!!)
        card.addView(barBg)

        statusText = TextView(this).apply {
            text = ""
            textSize = 10f
            setTextColor(0x88FFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(4), 0, 0)
        }
        card.addView(statusText!!)

        val grip = TextView(this).apply {
            text = "···"
            textSize = 10f
            setTextColor(0x55FFFFFF.toInt())
            gravity = Gravity.END
            setPadding(0, dp(2), 0, 0)
        }
        card.addView(grip)

        // 窄条贴顶
        val w = (resources.displayMetrics.widthPixels * 0.52f).toInt().coerceIn(dp(200), dp(280))
        val p = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(6)
        p.y = dp(8)

        title.setOnTouchListener { _, e -> moveHandler(e, p, panel) }
        grip.setOnTouchListener { _, e -> resizeHandler(e, p, panel) }

        linkButton?.setOnClickListener { selfActions.onLink() }
        analyzeButton?.setOnClickListener { selfActions.onAnalyze() }
        playButton?.setOnClickListener { selfActions.onPlayMove() }

        depthSpin?.onItemSelectedListener = sel { selfActions.onDepthSelect(it) }
        timeSpin?.onItemSelectedListener = sel { selfActions.onTimeSelect(it) }

        val miniView = TextView(this).apply {
            text = "≡"
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF01B2430.toInt())
                cornerRadius = 12f * density
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
        miniView.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX.toInt(); startY = e.rawY.toInt()
                    initialX = mp.x; initialY = mp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    mp.x = initialX + (e.rawX.toInt() - startX)
                    mp.y = initialY + (e.rawY.toInt() - startY)
                    wm.updateViewLayout(miniView, mp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (kotlin.math.abs(e.rawX.toInt() - startX) < dp(8) &&
                        kotlin.math.abs(e.rawY.toInt() - startY) < dp(8)
                    ) setExpanded(true)
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
        android.util.Log.i("Overlay", "ui added w=$w")
    }

    private fun chip(label: String, extra: (() -> Unit)? = null): Button = Button(this).apply {
        text = label
        textSize = 11f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(dp(8), dp(3), dp(8), dp(3))
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = GradientDrawable().apply {
            setColor(0xFF2F3B4A.toInt())
            cornerRadius = 8f * resources.displayMetrics.density
        }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = dp(3) }
        extra?.let { setOnClickListener { it() } }
    }

    private fun makeSpinner(labels: List<String>, selected: Int): Spinner {
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Light)
        return Spinner(themed).apply {
            adapter = ArrayAdapter(themed, android.R.layout.simple_spinner_dropdown_item, labels)
            setSelection(selected.coerceIn(0, labels.lastIndex))
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = 6f * resources.displayMetrics.density
            }
            setPadding(dp(4), 0, dp(4), 0)
        }
    }

    private fun sel(cb: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = cb(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
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

    private fun resizeHandler(event: MotionEvent, p: WindowManager.LayoutParams, panel: LinearLayout): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt(); startY = event.rawY.toInt()
                initialWidth = p.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                p.width = (initialWidth + (event.rawX.toInt() - startX)).coerceIn(dp(180), dp(360))
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

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(msg: String) {
        statusText?.text = msg
    }

    private fun setScoreBar(scoreCp: Int?) {
        val fill = scoreBarFill ?: return
        val lp = fill.layoutParams as LinearLayout.LayoutParams
        lp.weight = when {
            scoreCp == null -> 0.5f
            else -> 0.08f + (scoreCp.coerceIn(-1000, 1000) + 1000) / 2000f * 0.84f
        }
        fill.layoutParams = lp
    }

    private fun refreshButtons() {
        val connected = LiveLinkService.isConnected
        val running = ConnectSession.isRunning
        linkButton?.apply {
            text = if (connected) "连✓" else "连"
            background = GradientDrawable().apply {
                setColor(if (connected) 0xFF2E7D32.toInt() else 0xFF2F3B4A.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
        }
        analyzeButton?.apply {
            text = if (running) "析·" else "析"
            background = GradientDrawable().apply {
                setColor(if (running) 0xFF1565C0.toInt() else 0xFF2F3B4A.toInt())
                cornerRadius = 8f * resources.displayMetrics.density
            }
        }
        playButton?.text = if (ConnectSession.lastRecognizedOk) "出✓" else "出"
        depthSpin?.alpha = if (running) 0.4f else 1f
        timeSpin?.alpha = if (running) 0.4f else 1f
    }

    private fun buildLine(result: EngineResult, board: Position?): String {
        val score = when {
            result.mateIn != null -> "绝杀${result.mateIn}"
            result.scoreCp != null -> "${result.scoreCp}"
            else -> "-"
        }
        val nps = if (result.nps > 0) " ${result.nps / 1000}k" else ""
        val pv = if (board != null && result.pv.isNotEmpty()) {
            val sim = board.copy()
            result.pv.take(6).joinToString(" ") { m ->
                val cn = try { Notation.moveToChinese(sim, m) } catch (_: Throwable) { m }
                try { sim.applyIccs(m) } catch (_: Throwable) {}
                cn
            }
        } else result.pv.joinToString(" ")
        return "$score (${result.depth})$nps\n$pv".trim()
    }

    override fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean) = refreshButtons()

    override fun updateControls(depth: Int, seconds: Int) {
        depthValues.indexOf(depth).takeIf { it >= 0 }?.let { depthSpin?.setSelection(it) }
        timeValues.indexOf(seconds).takeIf { it >= 0 }?.let { timeSpin?.setSelection(it) }
    }

    override fun updateInfo(cloud: String, engineSummary: String, engineDetail: String) {
        analysisText?.text = engineDetail.ifBlank { engineSummary }.ifBlank { "皮卡鱼 —" }
    }

    override fun updateOpacity(alpha: Float) {
        val a = alpha.coerceIn(0.35f, 1f)
        root?.alpha = a
        mini?.alpha = a
    }

    override fun updateConnect(
        autoOn: Boolean,
        delayMs: Int,
        sideLabel: String,
        running: Boolean,
        message: String,
    ) {
        mainHandler.post {
            setStatus(message.ifBlank { sideLabel })
            val result = ConnectSession.lastResult
            val board = ConnectSession.lastBoard
            val fresh = result.bestmove.isNotBlank() &&
                result.fen.isNotBlank() &&
                result.fen == ConnectSession.lastFen
            analysisText?.text = when {
                engine?.isReady != true -> "皮卡鱼启动中…"
                !fresh -> if (message.contains("分析中")) "思考中…" else "皮卡鱼 —"
                else -> buildLine(result, board)
            }
            setScoreBar(if (fresh) result.scoreCp else null)
            refreshButtons()
        }
    }

    override fun onDestroy() {
        if (ConnectSession.isRunning) ConnectSession.stop()
        if (actions === selfActions) actions = null
        overlayDisplay = null
        root?.let { runCatching { wm.removeView(it) } }
        mini?.let { runCatching { wm.removeView(it) } }
        root = null; mini = null; params = null; miniParams = null
        analysisText = null; statusText = null; scoreBarFill = null
        linkButton = null; analyzeButton = null; playButton = null
        depthSpin = null; timeSpin = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
