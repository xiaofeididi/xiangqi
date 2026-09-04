package com.xqassist.core

import android.graphics.Bitmap
import java.io.Closeable

/** A captured screen frame. MVP: wraps a Bitmap; vision pipeline feeds it to the detector. */
class Frame(val bitmap: Bitmap, val timestampMs: Long = System.currentTimeMillis()) : Closeable {
    override fun close() {
        if (!bitmap.isRecycled) bitmap.recycle()
    }
}