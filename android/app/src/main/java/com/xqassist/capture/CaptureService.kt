package com.xqassist.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import com.xqassist.MainActivity

/**
 * 屏幕识别前台服务。
 * Android 14 规范：FGS(type=mediaProjection) → getMediaProjection → registerCallback → createVirtualDisplay。
 * 只有 VirtualDisplay 真正挂上，系统状态栏才会出现「屏幕已共享/正在录制」。
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null

    companion object {
        private const val CHANNEL = "xq_capture"
        private const val ID = 1
        private const val TAG = "Capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private val frameLock = Any()
        private var latestFrame: Bitmap? = null

        /** 仅在 projection 真正创建成功后为 true */
        @Volatile
        var isRunning = false
            private set

        @JvmStatic
        fun latestBitmap(): Bitmap? = synchronized(frameLock) { latestFrame }

        @JvmStatic
        fun copyLatestBitmap(): Bitmap? {
            val source = synchronized(frameLock) { latestFrame } ?: return null
            return try {
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
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
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("屏幕识别进行中")
            .setContentText("请保持截屏授权，以便识别对方棋盘")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null && display != null) {
            isRunning = true
            return START_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (data == null || resultCode < 0) {
            Log.e(TAG, "capture: missing resultCode/data")
            fail()
            return START_NOT_STICKY
        }

        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            // Android 14：必须已有 mediaProjection 前台服务（已在 onCreate startForeground）
            projection = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection returned null")

            val callbackHandler = Handler(mainLooper)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "capture: projection onStop")
                    isRunning = false
                    stopSelf()
                }
            }
            // 注册回调必须在 createVirtualDisplay 之前（Android 14 强制）
            projection!!.registerCallback(projectionCallback!!, callbackHandler)

            if (!startCapture(projection!!)) {
                throw IllegalStateException("createVirtualDisplay failed")
            }

            isRunning = true
            Log.i(TAG, "capture: ready display=${display != null}")
            START_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "capture: failed", t)
            fail()
            START_NOT_STICKY
        }
    }

    private fun startCapture(mp: MediaProjection): Boolean {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        val dpi = metrics.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi

        imageThread?.quitSafely()
        imageThread = HandlerThread("xq-capture").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        reader?.close()
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ activeReader ->
            var image: Image? = null
            try {
                image = activeReader.acquireLatestImage()
                if (image != null) {
                    val converted = bitmapFromImage(image)
                    synchronized(frameLock) {
                        latestFrame?.recycle()
                        latestFrame = converted
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "capture: frame skipped", e)
            } finally {
                image?.close()
            }
        }, imageHandler)

        // AUTO_MIRROR 会让系统顶部出现「屏幕已共享」提示
        display = mp.createVirtualDisplay(
            "XiangqiCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader!!.surface,
            null,
            null,
        )
        if (display == null) {
            Log.e(TAG, "capture: createVirtualDisplay returned null (${width}x${height}@$dpi)")
            return false
        }
        return true
    }

    private fun fail() {
        isRunning = false
        stopSelf()
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
        try {
            projectionCallback?.let { projection?.unregisterCallback(it) }
        } catch (_: Throwable) {
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        projection = null
        imageThread?.quitSafely()
        imageThread = null
        imageHandler = null
        synchronized(frameLock) {
            latestFrame?.recycle()
            latestFrame = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
