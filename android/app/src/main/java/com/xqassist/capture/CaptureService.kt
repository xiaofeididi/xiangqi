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
import android.graphics.Point
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
 * 对齐 Pro 象棋 w2.x（ScreenHelper）的抓屏参数：
 * - ImageReader format=1 (RGBA_8888), maxImages=2
 * - createVirtualDisplay flags=16 (VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
 * - 用 getRealSize 取真实分辨率
 *
 * Android 14 顺序：必须先 startForeground(type=mediaProjection)，再 getMediaProjection。
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var width = 0
    private var height = 0
    private var dpi = 0

    companion object {
        private const val CHANNEL = "xq_capture"
        private const val ID = 10086
        private const val TAG = "Capture"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_START_WAITING = "start_waiting"
        const val ACTION_APPLY_TOKEN = "apply_token"

        private val frameLock = Any()
        private var latestFrame: Bitmap? = null

        @Volatile
        private var lastCode: Int = -1

        @Volatile
        private var lastData: Intent? = null

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

        /** 授权前先起 FGS，满足 Android 14 getMediaProjection 前置条件 */
        fun startWaiting(context: Context) {
            val i = Intent(context, CaptureService::class.java).setAction(ACTION_START_WAITING)
            context.startForegroundService(i)
        }

        fun applyToken(context: Context, resultCode: Int, data: Intent) {
            lastCode = resultCode
            lastData = data
            val i = Intent(context, CaptureService::class.java)
                .setAction(ACTION_APPLY_TOKEN)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            lastCode = -1
            lastData = null
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "屏幕识别", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(ch)
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("屏幕识别进行中")
            .setContentText("正在捕获屏幕画面")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notif)
        }
        Log.i(TAG, "capture: FGS started type=mediaProjection")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ""
        if (action == ACTION_START_WAITING) {
            // 只起 FGS，等 token
            Log.i(TAG, "capture: waiting for projection token")
            return START_NOT_STICKY
        }

        if (projection != null && display != null) {
            isRunning = true
            return START_NOT_STICKY
        }

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
            Log.w(TAG, "capture: no token, keep FGS waiting")
            return START_NOT_STICKY
        }

        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data)
                ?: throw IllegalStateException("getMediaProjection null")
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

            if (!startCaptureLikePro(projection!!)) {
                throw IllegalStateException("createVirtualDisplay failed")
            }
            isRunning = true
            Log.i(TAG, "capture: ready ${width}x${height}@$dpi flags=16")
            START_NOT_STICKY
        } catch (t: Throwable) {
            Log.e(TAG, "capture: failed", t)
            isRunning = false
            stopSelf()
            START_NOT_STICKY
        }
    }

    /** 与 Pro w2.x.b 完全一致的参数 */
    private fun startCaptureLikePro(mp: MediaProjection): Boolean {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val point = Point()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(point)
        width = point.x.coerceAtLeast(1)
        height = point.y.coerceAtLeast(1)
        val metrics = resources.displayMetrics
        dpi = metrics.densityDpi.takeIf { it > 0 } ?: 320

        imageThread?.quitSafely()
        imageThread = HandlerThread("xq-capture").apply { start() }
        imageHandler = Handler(imageThread!!.looper)

        reader?.close()
        // Pro: ImageReader.newInstance(w, h, 1, 2)
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ activeReader ->
            var image: Image? = null
            try {
                image = activeReader.acquireLatestImage()
                if (image != null) {
                    val converted = bitmapFromImage(image, width, height)
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

        // Pro: flags = 16 (VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR)
        display = mp.createVirtualDisplay(
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
            Log.e(TAG, "capture: VirtualDisplay null")
            return false
        }
        return true
    }

    /** 与 Pro w2.x.a 的拷贝方式一致，处理 rowStride 对齐 */
    private fun bitmapFromImage(image: Image, wantW: Int, wantH: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
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
