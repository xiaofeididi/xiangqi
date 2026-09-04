package com.xqassist.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Hosts the UCCI engine in its own foreground service. */
class EngineService : Service() {

    companion object {
        private const val CHANNEL = "xq_engine"
        private const val ID = 2
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL, "引擎", NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val notif = Notification.Builder(this, CHANNEL)
            .setContentTitle("象棋引擎")
            .setContentText("皮卡鱼分析运行中")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}