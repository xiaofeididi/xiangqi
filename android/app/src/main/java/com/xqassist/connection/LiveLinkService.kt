package com.xqassist.connection

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 连线服务：读取对局界面元素 + 模拟点击坐标 */
class LiveLinkService : AccessibilityService() {

    companion object {
        var instance: LiveLinkService? = null
            private set
        val isConnected: Boolean get() = instance != null
        private const val TAG = "LiveLink"

        /** 在当前前台窗口的 (x,y) 处模拟点击 */
        fun tapAt(x: Int, y: Int, done: (Boolean) -> Unit = {}): Boolean {
            val svc = instance ?: run { done(false); return false }
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
                .build()
            return svc.dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
            }, null)
        }

        /** 同步点击，供连线分析循环在后台线程调用；默认最多等 1.5s */
        fun tapAtSync(x: Int, y: Int, timeoutMs: Long = 1500L): Boolean {
            if (instance == null) return false
            val latch = java.util.concurrent.CountDownLatch(1)
            val ok = java.util.concurrent.atomic.AtomicBoolean(false)
            val dispatched = tapAt(x, y) { success ->
                ok.set(success)
                latch.countDown()
            }
            if (!dispatched) return false
            return try {
                latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) && ok.get()
            } catch (_: InterruptedException) {
                false
            }
        }

        fun disconnect() {
            instance?.disableSelf()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        }
        Log.i(TAG, "llink: connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        Log.i(TAG, "llink: window " + event.packageName + " / " + event.className)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 打印当前窗口节点树摘要 */
    fun dumpNodes(): String {
        val root = rootInActiveWindow ?: return "no window"
        val out = StringBuilder()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            out.append(" ".repeat(depth + 1))
                .append(node.className?.toString()?.substringAfterLast('.'))
                .append(" text=").append(node.text)
                .append(" desc=").append(node.contentDescription)
                .append(" bounds=").append(bounds.toShortString()).append("\n")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        root.recycle()
        return out.toString()
    }
}
