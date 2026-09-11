package com.xqassist.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Log
import android.view.WindowManager
import java.nio.ByteBuffer

/**
 * 对齐 Pro 象棋 w2.x（ScreenHelper）：
 * - Activity 内 getMediaProjection + createVirtualDisplay
 * - ImageReader format=1 (RGBA_8888), maxImages=2
 * - flags=16 (VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
 * - getRealSize 真实分辨率
 *
 * targetSdk=28，不需要 Android 14 的 mediaProjection FGS 前置。
 * createVirtualDisplay 成功后系统状态栏会出现「屏幕共享中」。
 */
object ScreenHelper {
    private const val TAG = "Capture"

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: android.hardware.display.VirtualDisplay? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    @Volatile
    var isRunning = false
        private set

    fun latestBitmap(): Bitmap? = synchronized(frameLock) { latestFrame }

    fun copyLatestBitmap(): Bitmap? {
        val source = synchronized(frameLock) { latestFrame } ?: return null
        return try {
            source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (_: Throwable) {
            null
        }
    }

    /** Pro: w2.x.startService — 记录屏幕尺寸并发起授权 */
    fun prepare(activity: Activity): Boolean {
        return try {
            val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            width = point.x.coerceAtLeast(1)
            height = point.y.coerceAtLeast(1)
            dpi = activity.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: 320
            Log.i(TAG, "prepare ${width}x${height}@$dpi")
            activity.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "prepare failed", t)
            false
        }
    }

    const val REQUEST_CODE = 10086

    /** Pro: w2.x.b — 授权回调后立刻建投影和 VirtualDisplay */
    fun create(activity: Activity, resultCode: Int, data: Intent?): Boolean {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.w(TAG, "create: denied resultCode=$resultCode")
            return false
        }
        if (isRunning && projection != null && display != null) {
            return true
        }
        return try {
            val mpm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            releaseInternal()
            projection = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection null")

            if (width <= 0 || height <= 0) {
                val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val point = Point()
                @Suppress("DEPRECATION")
                wm.defaultDisplay.getRealSize(point)
                width = point.x.coerceAtLeast(1)
                height = point.y.coerceAtLeast(1)
                dpi = activity.resources.displayMetrics.densityDpi.takeIf { it > 0 } ?: 320
            }

            reader = ImageReader.newInstance(width, height, 1, 2)
            reader!!.setOnImageAvailableListener({ activeReader ->
                var image: Image? = null
                try {
                    image = activeReader.acquireLatestImage()
                    if (image != null) {
                        val bmp = bitmapFromImage(image, width, height)
                        synchronized(frameLock) {
                            latestFrame?.recycle()
                            latestFrame = bmp
                        }
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "frame skipped", e)
                } finally {
                    image?.close()
                }
            }, null)

            // Pro: flags = 16
            display = projection!!.createVirtualDisplay(
                "ProScreenCapture",
                width,
                height,
                dpi,
                16,
                reader!!.surface,
                null,
                null,
            )
            if (display == null) {
                throw IllegalStateException("createVirtualDisplay null")
            }
            isRunning = true
            Log.i(TAG, "create: ready ${width}x${height}@$dpi (屏幕共享中应已显示)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "create failed", t)
            releaseInternal()
            false
        }
    }

    fun stop() {
        releaseInternal()
    }

    private fun releaseInternal() {
        isRunning = false
        try {
            display?.release()
        } catch (_: Throwable) {
        }
        display = null
        try {
            reader?.close()
        } catch (_: Throwable) {
        }
        reader = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
    }

    /** 与 Pro w2.x.a 一致：处理 rowStride 对齐 */
    private fun bitmapFromImage(image: Image, wantW: Int, wantH: Int): Bitmap {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val tmpW = wantW + ((rowStride - pixelStride * wantW) / pixelStride)
        val raw = Bitmap.createBitmap(tmpW, wantH, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        raw.copyPixelsFromBuffer(buffer)
        val crop = Bitmap.createBitmap(raw, 0, 0, wantW, wantH)
        if (crop != raw) raw.recycle()
        return crop
    }
}
