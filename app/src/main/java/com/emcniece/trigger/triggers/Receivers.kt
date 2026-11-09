package com.emcniece.trigger.triggers

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.emcniece.trigger.Graph
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.runAsync
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType
import kotlinx.coroutines.joinAll

/** SMS_RECEIVED is exempt from implicit-broadcast limits, so a manifest receiver works. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // Multipart messages arrive as several PDUs; stitch them back together per sender.
        val events = messages
            .groupBy { it.originatingAddress.orEmpty() }
            .map { (sender, parts) ->
                TriggerEvent(
                    TriggerType.SMS,
                    mapOf("sms_sender" to sender, "sms_body" to parts.joinToString("") { it.messageBody.orEmpty() }),
                )
            }
        runAsync { events.map { MacroEngine.dispatch(context, it) }.joinAll() }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                Graph.syncBackgroundWork(context)
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runAsync { MacroEngine.dispatch(context, TriggerEvent(TriggerType.BOOT)).join() }
                }
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> Graph.syncBackgroundWork(context)
        }
    }
}

class NotificationListener : NotificationListenerService() {
    // Apps frequently re-post identical notifications; remember recent ones to avoid duplicate runs.
    private val recent = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 64
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        val key = "${sbn.key}|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            val seen = recent[key]
            recent[key] = now
            if (seen != null && now - seen < 60_000) return
        }
        MacroEngine.dispatch(
            this,
            TriggerEvent(
                TriggerType.NOTIFICATION,
                mapOf("notif_package" to sbn.packageName, "notif_title" to title, "notif_text" to text),
            ),
        )
    }
}
