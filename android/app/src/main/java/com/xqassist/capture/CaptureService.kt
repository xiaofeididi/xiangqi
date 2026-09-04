package com.xqassist.capture

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
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log

/** Foreground service that owns the MediaProjection and turns frames into bitmaps. */
class CaptureService : Service() {

    private lateinit var projection: MediaProjection

    companion object {
        private const val CHANNEL = "xq_capture"
        private const val ID = 1
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

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
        if (data != null && resultCode > 0) {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(resultCode, data)
            Handler(Looper.getMainLooper()).post { Log.i("Capture", "projection ready") }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (::projection.isInitialized) projection.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}