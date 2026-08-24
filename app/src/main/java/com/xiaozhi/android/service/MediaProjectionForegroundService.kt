package com.xiaozhi.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import com.xiaozhi.android.media.ScreenCaptureController

class MediaProjectionForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        ScreenCaptureController.createProjection(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            ScreenCaptureController.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        ScreenCaptureController.createProjection(this)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        ScreenCaptureController.stop()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "屏幕识别服务",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("小智屏幕识别")
            .setContentText("保持屏幕分析可用")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xiaozhi_media_projection"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.xiaozhi.android.action.STOP_MEDIA_PROJECTION"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, MediaProjectionForegroundService::class.java)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MediaProjectionForegroundService::class.java)
                    .setAction(ACTION_STOP)
            )
        }
    }
}
