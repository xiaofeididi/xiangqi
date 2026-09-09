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

/** 悬浮窗：连线 / 分析 / 出子 + 深度时间 + 云库与引擎推荐 */
class OverlayService : Service(), OverlayService.Display {

    interface Actions {
        fun onLink()
        fun onAnalyze()
        fun onPlayMove()
        fun onDepthChange(delta: Int)
        fun onTimeChange(delta: Int)
    }

    interface Display {
        fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean = false)
        fun updateControls(depth: Int, seconds: Int)
        fun updateInfo(cloud: String, engineSummary: String, engineDetail: String)
    }

    companion object {
        var actions: Actions? = null
        var display: Display? = null

        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var infoText: TextView? = null
    private var depthText: TextView? = null
    private var timeText: TextView? = null
    private var linkButton: Button? = null
    private var analyzeButton: Button? = null
    private var playButton: Button? = null
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0

    override fun onCreate() {
        super.onCreate()
        display = this
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = dp(290)
            setPadding(dp(10), dp(6), dp(10), dp(9))
            background = GradientDrawable().apply {
                setColor(0xF218202A.toInt())
                cornerRadius = dp(14).toFloat()
            }
        }

        val handle = TextView(this).apply {
            text = "≡ 象棋助手"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(5))
        }
        panel.addView(handle)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkButton = overlayButton("连线")
        analyzeButton = overlayButton("分析")
        playButton = overlayButton("出子")
        buttons.addView(linkButton)
        buttons.addView(analyzeButton)
        buttons.addView(playButton)
        panel.addView(buttons)
        panel.addView(spacer(dp(6)))

        val controlRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        controlRows.addView(controlRow("深度", "不限") { delta -> actions?.onDepthChange(delta) })
        controlRows.addView(spacer(dp(4)))
        controlRows.addView(controlRow("时间", "3秒") { delta -> actions?.onTimeChange(delta) })
        panel.addView(controlRows)
        panel.addView(spacer(dp(7)))

        infoText = TextView(this).apply {
            text = "云库：-\n引擎：-\n-"
            textSize = 11.5f
            setTextColor(Color.WHITE)
            setLineSpacing(dp(1).toFloat(), 1f)
        }
        panel.addView(infoText)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = dp(14)
        p.y = dp(96)

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    initialX = p.x
                    initialY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initialX + (event.rawX.toInt() - startX)
                    p.y = initialY + (event.rawY.toInt() - startY)
                    wm.updateViewLayout(panel, p)
                    true
                }
                else -> false
            }
        }

        linkButton?.setOnClickListener { actions?.onLink() }
        analyzeButton?.setOnClickListener { actions?.onAnalyze() }
        playButton?.setOnClickListener { actions?.onPlayMove() }

        wm.addView(panel, p)
        root = panel
        params = p
        updateActions(false, false, false)
        updateControls(0, 3)
        updateInfo("", "", "")
    }

    private fun controlRow(label: String, initial: String, onDelta: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 11.5f
            setTextColor(0xB3FFFFFF.toInt())
        })
        val value = TextView(this).apply {
            text = initial
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(value)
        if (label == "深度") depthText = value else timeText = value
        row.addView(miniButton("-") { onDelta(-1) })
        row.addView(miniButton("+") { onDelta(1) })
        return row
    }

    private fun overlayButton(label: String): Button = Button(this).apply {
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
        textSize = 13f
        isAllCaps = false
        includeFontPadding = false
        minHeight = 0
        minWidth = 0
        setPadding(dp8(), dp2(), dp8(), dp2())
        setTextColor(Color.WHITE)
        stateListAnimator = null
        background = roundBackground(0xFF334154.toInt(), dp7().toFloat())
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp3()
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

    override fun updateActions(linkOn: Boolean, analysisOn: Boolean, thinking: Boolean) {
        linkButton?.apply {
            text = if (linkOn) "连线·开" else "连线"
            background = roundBackground(if (linkOn) 0xFF2E7D32.toInt() else 0xFF39465A.toInt(), dp8().toFloat())
        }
        analyzeButton?.apply {
            text = when {
                analysisOn && thinking -> "分析中"
                analysisOn -> "分析·开"
                else -> "分析"
            }
            background = roundBackground(if (analysisOn) 0xFF1565C0.toInt() else 0xFF39465A.toInt(), dp8().toFloat())
        }
        playButton?.alpha = if (thinking) 0.55f else 1f
    }

    override fun updateControls(depth: Int, seconds: Int) {
        depthText?.text = if (depth <= 0) "不限" else depth.toString() + "层"
        timeText?.text = seconds.toString() + "秒"
    }

    override fun updateInfo(cloud: String, engineSummary: String, engineDetail: String) {
        infoText?.text = "云库：" + cloud.ifBlank { "-" } + "\n引擎：" + engineSummary.ifBlank { "-" } +
            (if (engineDetail.isBlank()) "" else "\n" + engineDetail)
    }

    override fun onDestroy() {
        display = null
        root?.let { wm.removeView(it) }
        root = null
        params = null
        infoText = null
        depthText = null
        timeText = null
        linkButton = null
        analyzeButton = null
        playButton = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
