package com.emcniece.trigger.engine

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.emcniece.trigger.R
import com.emcniece.trigger.ui.MainActivity
import java.util.concurrent.atomic.AtomicInteger

object Notifications {
    const val CHANNEL_MONITOR = "monitor"
    const val CHANNEL_MACRO = "macro"
    const val MONITOR_ID = 1
    private val nextId = AtomicInteger(1000)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, "Background monitoring", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Trigger watches battery, charger and network events"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MACRO, "Macro notifications", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifications posted by your macros"
            },
        )
    }

    private fun openApp(context: Context) = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun monitorNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Trigger is watching for events")
            .setContentText("Battery, charger and network triggers are active")
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    fun showMacroNotification(context: Context, title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) {
            throw ActionException("notification permission not granted")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_MACRO)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(nextId.getAndIncrement(), n)
    }
}
