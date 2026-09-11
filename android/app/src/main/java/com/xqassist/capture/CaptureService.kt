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
 * 注意：MediaProjection 授权 Intent 只能消费一次；系统若无 extras 重启本服务，必须静默退出，
 * 不能反复弹授权（那会导致“无限授权”）。
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

        /** start() 时暂存，便于同进程二次确认状态 */
        @Volatile
        private var lastCode: Int = -1
        @Volatile
        private var lastData: Intent? = null
        @Volatile
        private var pendingProjection: MediaProjection? = null

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
            lastCode = -1
            lastData = null
            pendingProjection = null
            context.stopService(Intent(context, CaptureService::class.java))
        }

        /**
         * 在 Activity 授权回调里立刻 getMediaProjection，再交给本服务挂 VirtualDisplay。
         * 避免把 token Intent 再经 startForegroundService 丢一次导致授权丢失。
         */
        fun startWithProjection(context: Context, projection: MediaProjection) {
            pendingProjection = projection
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }

        fun start(context: Context, resultCode: Int, data: Intent) {
            lastCode = resultCode
            lastData = data
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
            .setContentText("请保持截屏授权")
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
            return START_NOT_STICKY
        }

        // 优先使用 Activity 刚建好的 projection
        var mp = pendingProjection
        pendingProjection = null

        if (mp == null) {
            var resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
            var data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_RESULT_DATA)
            }
            if (data == null) {
                resultCode = lastCode
                data = lastData
            }
            if (data == null || resultCode < 0) {
                Log.w(TAG, "capture: no projection token, stop quietly")
                isRunning = false
                stopSelf()
                return START_NOT_STICKY
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mp = try {
                // 必须在 startForeground 之后调用（onCreate 已完成 FGS）
                mpm.getMediaProjection(resultCode, data)
            } catch (t: Throwable) {
                Log.e(TAG, "capture: getMediaProjection failed", t)
                null
            }
        }

        if (mp == null) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        return try {
            projection = mp
            val callbackHandler = Handler(mainLooper)
            projectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "capture: projection onStop")
                    isRunning = false
                    stopSelf()
                }
            }
            projection!!.registerCallback(projectionCallback!!, callbackHandler)

            if (!startCapture(projection!!)) {
                throw IllegalStateException("createVirtualDisplay failed")
            }
            isRunning = true
            Log.i(TAG, "capture: ready")
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "capture: failed", t)
            isRunning = false
            stopSelf()
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

        display = mp.createVirtualDisplay(
            "XiangqiCapture",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            null,
        )
        if (display == null) {
            Log.e(TAG, "capture: VirtualDisplay null ${width}x${height}@$dpi")
            return false
        }
        return true
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
