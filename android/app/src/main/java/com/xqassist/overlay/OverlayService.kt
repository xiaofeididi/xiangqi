package com.xqassist.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.xqassist.core.Quad

interface OverlayDisplay {
    fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean = false)
    fun updateControls(depth: Int, seconds: Int)
    fun updateInfo(cloud: String, engineSummary: String, engineDetail: String)
    fun updateOpacity(alpha: Float)
    fun updateConnect(autoOn: Boolean, delayMs: Int, sideLabel: String, running: Boolean, message: String)
    fun updateMiniBoard(position: com.xqassist.core.Position?, hint: Quad?, status: String)
}

/** 悬浮窗：保持既有紧凑样式；连线能力仍通过 Actions 与主界面联动 */
class OverlayService : Service(), OverlayDisplay {

    interface Actions {
        fun onLink()
        fun onLinkLongPress()
        fun onRecognize()
        fun onAnalyze()
        fun onPlayMove()
        fun onDepthChange(delta: Int)
        fun onTimeChange(delta: Int)
        fun onOpacityChange(delta: Int)
        fun onCloseOverlay()
        fun onAutoMoveToggle()
        fun onDelayChange(delta: Int)
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
    private var root: LinearLayout? = null
    private var mini: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var miniParams: WindowManager.LayoutParams? = null
    private var infoText: TextView? = null
    private var depthText: TextView? = null
    private var timeText: TextView? = null
    private var opacityText: TextView? = null
    private var linkButton: Button? = null
    private var analyzeButton: Button? = null
    private var playButton: Button? = null
    private var cloudText: TextView? = null
    private var expanded = true
    private var opacity = 1f
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0
    private var initialWidth = 0
    private var initialHeight = 0
    private var autoOn = false
    private var delayMs = 150

    override fun onCreate() {
        super.onCreate()
        overlayDisplay = this

        val metrics = resources.displayMetrics
        fun dp(value: Int) = (value * metrics.density).toInt()
        val baseWidth = (metrics.widthPixels * 0.72f).toInt().coerceIn(dp(240), dp(430))
        val baseHeight = baseWidth * 9 / 16 + dp(8)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(0xF218202A.toInt())
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        panel.addView(card)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(TextView(this).apply {
            text = "≡ 象棋助手"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        })
        header.addView(miniButton("—") { setExpanded(false) })
        header.addView(miniButton("×") {
            actions?.onCloseOverlay()
            stopSelf()
        })
        card.addView(header)
        card.addView(spacer(dp(5)))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkButton = actionButton("连线")
        analyzeButton = actionButton("分析")
        playButton = actionButton("出子")
        buttons.addView(linkButton)
        buttons.addView(analyzeButton)
        buttons.addView(playButton)
        card.addView(buttons)
        card.addView(spacer(dp(5)))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        controls.addView(controlTile("深", "不限", 1.0f) { delta -> actions?.onDepthChange(delta) })
        controls.addView(controlTile("时", "3秒", 1.0f) { delta -> actions?.onTimeChange(delta) })
        controls.addView(controlTile("透明", "100%", 1.2f) { delta -> actions?.onOpacityChange(delta) })
        card.addView(controls)
        card.addView(spacer(dp(5)))

        // 云库 / 引擎 各占一半
        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        cloudText = TextView(this).apply {
            text = "云库\n-"
            textSize = 11f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(0, 0, dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        infoText = TextView(this).apply {
            text = "引擎\n-"
            textSize = 11f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(1).toFloat(), 1f)
            setPadding(dp(4), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        infoRow.addView(cloudText)
        infoRow.addView(infoText)
        card.addView(infoRow)

        val resize = TextView(this).apply {
            text = "↘"
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(resize, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val p = WindowManager.LayoutParams(
            baseWidth,
            baseHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(14)
        p.y = dp(88)

        header.setOnTouchListener { _, event -> moveHandler(event, p, panel) }
        resize.setOnTouchListener { _, event -> resizeHandler(event, p, panel, metrics.widthPixels - dp(16)) }

        linkButton?.setOnClickListener { actions?.onLink() }
        linkButton?.setOnLongClickListener {
            actions?.onLinkLongPress()
            true
        }
        analyzeButton?.setOnClickListener { actions?.onAnalyze() }
        // 长按出子 = 开关自动走
        playButton?.setOnClickListener { actions?.onPlayMove() }
        playButton?.setOnLongClickListener {
            actions?.onAutoMoveToggle()
            true
        }
        // 长按分析 = 切换行棋方
        analyzeButton?.setOnLongClickListener {
            actions?.onSideToggle()
            true
        }

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
        updateActions(false, false, false)
        updateControls(0, 3)
        updateInfo("", "", "")
        updateOpacity(1f)
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

    private fun resizeHandler(event: MotionEvent, p: WindowManager.LayoutParams, panel: LinearLayout, maxWidth: Int): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt(); startY = event.rawY.toInt()
                initialWidth = p.width
                initialHeight = p.height
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val width = initialWidth + (event.rawX.toInt() - startX)
                val clampedWidth = width.coerceIn((maxWidth / 4).coerceAtLeast(180), maxWidth)
                p.width = clampedWidth
                p.height = clampedWidth * 9 / 16
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
        layoutParams = LinearLayout.LayoutParams(dp(16), dp(18)).apply {
            marginStart = dp(3)
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

    override fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean) {
        // 始终以无障碍实时状态为准，避免按钮文案过期
        val connected = com.xqassist.connection.LiveLinkService.isConnected
        linkButton?.apply {
            text = if (connected) "已连接" else "连线"
            background = roundBackground(if (connected) 0xFF2E7D32.toInt() else 0xFF39465A.toInt(), dp8().toFloat())
        }
        analyzeButton?.apply {
            text = when {
                analysisOn && thinking -> "分析中"
                analysisOn -> "分析·开"
                else -> if (autoOn) "分析·自动" else "分析"
            }
            background = roundBackground(
                if (analysisOn) 0xFF1565C0.toInt()
                else if (autoOn) 0xFF6A1B9A.toInt()
                else 0xFF39465A.toInt(),
                dp8().toFloat(),
            )
        }
        playButton?.alpha = if (thinking) 0.55f else 1f
    }

    override fun updateControls(depth: Int, seconds: Int) {
        depthText?.text = if (depth <= 0) "不限" else depth.toString() + "层"
        timeText?.text = seconds.toString() + "秒"
    }

    override fun updateInfo(cloud: String, engineSummary: String, engineDetail: String) {
        cloudText?.text = "云库\n" + cloud.ifBlank { "-" }
        infoText?.text = buildString {
            append("引擎\n")
            append(engineSummary.ifBlank { "-" })
            if (engineDetail.isNotBlank()) {
                append("\n").append(engineDetail)
            }
        }
    }

    override fun updateOpacity(alpha: Float) {
        opacity = alpha.coerceIn(0.35f, 1f)
        root?.alpha = opacity
        mini?.alpha = opacity
        opacityText?.text = (opacity * 100).toInt().toString() + "%"
    }

    override fun updateConnect(autoOnValue: Boolean, delayMsValue: Int, sideLabel: String, running: Boolean, message: String) {
        autoOn = autoOnValue
        delayMs = delayMsValue
        analyzeButton?.apply {
            text = when {
                running && autoOnValue -> "分析·自动"
                running -> "分析·开"
                autoOnValue -> "分析(自动)"
                else -> "分析"
            }
        }
        // 连线状态并入按钮文案
        linkButton?.apply {
            val connected = text == "已连接" || text == "断开"
            // 保持已连接/断开状态由 updateActions 控制；此处只显示识别进度到出子按钮
        }
        playButton?.apply {
            val prefix = if (message.contains("已正常识别")) "✓" else ""
            text = if (running) prefix + "出子" else "出子"
        }
    }

    override fun updateMiniBoard(position: com.xqassist.core.Position?, hint: Quad?, status: String) {
        // 上一版悬浮窗样式不内嵌迷你棋盘
    }

    override fun onDestroy() {
        overlayDisplay = null
        root?.let { wm.removeView(it) }
        mini?.let { wm.removeView(it) }
        root = null
        mini = null
        params = null
        miniParams = null
        infoText = null
        depthText = null
        timeText = null
        opacityText = null
        linkButton = null
        analyzeButton = null
        playButton = null
        cloudText = null
        infoText = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
