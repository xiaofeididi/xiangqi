package com.xqassist.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Foreground service that owns the MediaProjection and turns frames into bitmaps. */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private val frameLock = Any()
    private var latestFrame: Bitmap? = null

    companion object {
        private const val CHANNEL = "xq_capture"
        private const val ID = 1
        private const val TAG = "Capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        @Volatile
        var isRunning = false
            private set

        fun latestBitmap(): Bitmap? = synchronized(frameLock) { latestFrame }

        fun copyLatestBitmap(): Bitmap? {
            val source = synchronized(frameLock) { latestFrame } ?: return null
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "屏幕识别", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("屏幕识别")
            .setContentText("正在识别棋盘…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (data == null || resultCode < 0) {
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            startCapture()
            isRunning = true
            Log.i(TAG, "capture: projection ready")
            START_STICKY
        } catch (e: Throwable) {
            Log.e(TAG, "capture: projection failed", e)
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        imageThread = HandlerThread("xq-capture").apply { start() }
        imageHandler = Handler(imageThread!!.looper)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "capture: projection stopped")
            }
        }, imageHandler)
        reader!!.setOnImageAvailableListener({ activeReader ->
            var image: Image? = null
            try {
                image = activeReader.acquireLatestImage()
                if (image != null) {
                    val converted = bitmapFromImage(image)
                    synchronized(frameLock) {
                        latestFrame = converted
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "capture: frame skipped", e)
            } finally {
                image?.close()
            }
        }, imageHandler)

        display = projection!!.createVirtualDisplay(
            "XiangqiCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            imageHandler,
        )
    }

    private fun bitmapFromImage(image: Image): Bitmap {
        val plane = image.planes[0]
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        if (rowStride == image.width * pixelStride) {
            val buffer = java.nio.ByteBuffer.allocate(rowStride * image.height)
            buffer.rewind()
            plane.buffer.rewind()
            buffer.put(plane.buffer)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
        } else {
            val row = ByteArray(rowStride)
            val pixels = IntArray(image.width * image.height)
            plane.buffer.rewind()
            for (y in 0 until image.height) {
                plane.buffer.get(row, 0, minOf(rowStride, plane.buffer.remaining()))
                for (x in 0 until image.width) {
                    val offset = x * pixelStride
                    pixels[y * image.width + x] = (row[offset].toInt() and 0xff) or
                        ((row[offset + 1].toInt() and 0xff) shl 8) or
                        ((row[offset + 2].toInt() and 0xff) shl 16) or
                        ((row[offset + 3].toInt() and 0xff) shl 24)
                }
            }
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        }
        return bitmap
    }

    override fun onDestroy() {
        isRunning = false
        display?.release()
        display = null
        reader?.close()
        reader = null
        projection?.stop()
        projection = null
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
        synchronized(frameLock) { latestFrame = null }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
