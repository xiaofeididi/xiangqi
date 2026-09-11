package com.xqassist.capture

import android.content.Context
import android.graphics.Bitmap

/**
 * 兼容层：抓屏实现已迁到 ScreenHelper（对齐 Pro w2.x，targetSdk=28）。
 * 保留此类型以免改动全部调用点。
 */
object CaptureService {
    val isRunning: Boolean
        get() = ScreenHelper.isRunning

    fun latestBitmap(): Bitmap? = ScreenHelper.latestBitmap()

    fun copyLatestBitmap(): Bitmap? = ScreenHelper.copyLatestBitmap()

    fun stop(@Suppress("UNUSED_PARAMETER") context: Context) {
        ScreenHelper.stop()
    }
}
