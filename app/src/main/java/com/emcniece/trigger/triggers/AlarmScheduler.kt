package com.emcniece.trigger.triggers

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.net.toUri
import com.emcniece.trigger.Graph
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.runAsync
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.Trigger
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/** One wake-up alarm per enabled time-of-day macro. Alarms are re-armed after each firing and on boot. */
object AlarmScheduler {
    private const val ACTION = "com.emcniece.trigger.ALARM"
    private const val EXTRA_ID = "macro_id"
    private val scheduled = mutableSetOf<String>()

    fun canScheduleExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    @Synchronized
    fun sync(context: Context, macros: List<Macro>) {
        val wanted = macros.filter { it.enabled && it.trigger is Trigger.DailyTime }
        (scheduled - wanted.map { it.id }.toSet()).forEach { cancel(context, it) }
        // Also cancel disabled/changed macros that may have alarms from a previous process.
        macros.filter { it !in wanted }.forEach { cancel(context, it.id) }
        wanted.forEach { schedule(context, it.id, it.trigger as Trigger.DailyTime) }
    }

    private fun pendingIntent(context: Context, macroId: String, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION)
            .setData("trigger://alarm/$macroId".toUri()) // makes each macro's PendingIntent distinct
            .putExtra(EXTRA_ID, macroId)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun schedule(context: Context, macroId: String, trigger: Trigger.DailyTime) {
        val now = ZonedDateTime.now()
        var next = ZonedDateTime.of(LocalDate.now(), LocalTime.of(trigger.hour, trigger.minute), now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val at = next.toInstant().toEpochMilli()
        val pi = pendingIntent(context, macroId, PendingIntent.FLAG_UPDATE_CURRENT)!!
        val am = context.getSystemService(AlarmManager::class.java)
        if (canScheduleExact(context)) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
        scheduled += macroId
    }

    private fun cancel(context: Context, macroId: String) {
        pendingIntent(context, macroId, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
        scheduled -= macroId
    }

    fun macroIdFrom(intent: Intent): String? = intent.getStringExtra(EXTRA_ID)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = AlarmScheduler.macroIdFrom(intent) ?: return
        val macro = Graph.macros.get(id) ?: return
        val time = (macro.trigger as? Trigger.DailyTime)?.let { "%02d:%02d".format(it.hour, it.minute) }.orEmpty()
        // Re-arm for tomorrow before running.
        AlarmScheduler.sync(context, Graph.macros.macros.value)
        runAsync {
            MacroEngine.dispatch(context, TriggerEvent(TriggerType.TIME, mapOf("time" to time), targetMacroId = id)).join()
        }
    }
}
