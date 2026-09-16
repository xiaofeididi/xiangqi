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
    private external fun nativeOutW(): Int
    private external fun nativeOutH(): Int
    private external fun nativeRelease()

    @Volatile
    var ready = false
        private set

    @Volatile
    var lastError = ""
        private set

    /** 最近一次 detect 的输出形状 */
    @Volatile
    var outC = 22
    @Volatile
    var outW = 8400
    @Volatile
    var outH = 1

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

    /** returns flattened output or null; updates outC/outW/outH */
    fun detect(bitmap: Bitmap): FloatArray? {
        if (!ready) return null
        return try {
            val arr = nativeDetect(bitmap)
            if (arr != null) {
                try {
                    outC = nativeOutC()
                    outW = nativeOutW()
                    outH = nativeOutH()
                } catch (_: Throwable) {}
            }
            arr
        } catch (t: Throwable) {
            lastError = t.message ?: "detect"
            null
        }
    }

    fun release() {
        try { nativeRelease() } catch (_: Throwable) {}
        ready = false
    }
}
