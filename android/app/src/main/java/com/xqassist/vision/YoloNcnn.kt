package com.xqassist.vision

import android.graphics.Bitmap

/** JNI bridge to vendored NCNN + extracted Pro YOLO weights. */
object YoloNcnn {
    init {
        System.loadLibrary("xqyolo")
    }

    private external fun nativeInit(param: String, bin: String): Boolean
    private external fun nativeDetect(bitmap: Bitmap): FloatArray?
    private external fun nativeOutC(): Int
    private external fun nativeRelease()

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError = ""
        private set

    fun init(paramPath: String, binPath: String): Boolean {
        return try {
            val ok = nativeInit(paramPath, binPath)
            ready = ok
            lastError = if (ok) "" else "nativeInit=false"
            ok
        } catch (t: Throwable) {
            ready = false
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    /** returns flattened [C, N] or null */
    fun detect(bitmap: Bitmap): FloatArray? {
        if (!ready) return null
        return try {
            nativeDetect(bitmap)
        } catch (t: Throwable) {
            lastError = t.message ?: "detect"
            null
        }
    }

    fun outC(): Int = if (ready) {
        try { nativeOutC().coerceIn(8, 32) } catch (_: Throwable) { 22 }
    } else 22

    fun release() {
        try { nativeRelease() } catch (_: Throwable) {}
        ready = false
    }
}
