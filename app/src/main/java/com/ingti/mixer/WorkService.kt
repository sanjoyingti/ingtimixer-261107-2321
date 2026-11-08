package com.ingti.mixer

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.app.NotificationChannel
import android.app.Notification
import android.os.Build

class WorkService : Service() {
    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "ingtimixer_work"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ingti Mixer")
            .setContentText("Processing audio in background")
            .setSmallIcon(R.drawable.ic_logo)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Processing",
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManagerCompat::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
