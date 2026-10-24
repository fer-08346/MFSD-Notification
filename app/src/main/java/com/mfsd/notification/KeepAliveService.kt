package com.mfsd.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Servicio en primer plano para evitar que Android suspenda la aplicación
 * o detenga la captura y los resúmenes de notificaciones.
 */
class KeepAliveService : Service {
    constructor() : super()

    companion object {
        private const val CHANNEL_ID = "mfsd_foreground_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) { }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, KeepAliveService::class.java))
            } catch (_: Exception) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val masterEnabled = NotificationStore.prefs(this)
            .getBoolean(NotificationStore.KEY_MASTER_ENABLED, true)
        val keepAliveEnabled = NotificationStore.prefs(this)
            .getBoolean(NotificationStore.KEY_KEEP_ALIVE, true)

        if (!masterEnabled || !keepAliveEnabled) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildForegroundNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (_: Exception) { }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicio en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la lectura y los resúmenes de MFSD Notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("MFSD Notification")
            .setContentText("Activa en segundo plano para lectura y resúmenes")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
