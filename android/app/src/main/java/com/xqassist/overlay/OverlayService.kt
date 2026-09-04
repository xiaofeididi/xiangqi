package com.xqassist.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** Floating analysis widget: shows current position / best move on top of other apps. */
class OverlayService : Service() {

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, OverlayService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private val wm by lazy { getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var view: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var startX = 0
    private var startY = 0
    private var initialX = 0
    private var initialY = 0

    override fun onCreate() {
        super.onCreate()
        val v = TextView(this).apply {
            text = "象棋助手\n等待局面…"
            setTextColor(0xFFE8F48B.toInt())
            textSize = 14f
            setBackgroundColor(0xAA202020.toInt())
            setPadding(16, 10, 16, 10)
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = 20
        p.y = 120
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX.toInt(); startY = ev.rawY.toInt()
                    initialX = p.x; initialY = p.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    p.x = initialX + (ev.rawX.toInt() - startX)
                    p.y = initialY + (ev.rawY.toInt() - startY)
                    wm.updateViewLayout(v, p)
                    true
                }
                else -> false
            }
        }
        wm.addView(v, p)
        view = v; params = p
    }

    fun update(text: String) {
        view?.text = text
    }

    override fun onDestroy() {
        view?.let { wm.removeView(it) }
        view = null; params = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}