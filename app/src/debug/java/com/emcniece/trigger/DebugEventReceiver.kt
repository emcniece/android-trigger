package com.emcniece.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.runAsync
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType

/**
 * Debug-only entry point for simulating trigger events from adb, e.g.
 *
 *   adb shell am broadcast -n com.emcniece.trigger.debug/com.emcniece.trigger.DebugEventReceiver \
 *       --es type sms --es sms_sender +15555550123 --es sms_body "hello"
 *
 * `type` is a TriggerType name (sms, notification, power, battery, network, boot, time);
 * every other string extra becomes an event variable. Filters are applied exactly as for real events.
 *
 * The debug app ID has a ".debug" suffix but classes keep their package, so the component
 * must be given in full rather than as ".DebugEventReceiver".
 */
class DebugEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras ?: return
        val type = extras.getString("type")?.let { name ->
            TriggerType.entries.find { it.name.equals(name, ignoreCase = true) }
        } ?: TriggerType.SMS
        val vars = extras.keySet()
            .filter { it != "type" }
            .mapNotNull { key -> extras.getString(key)?.let { key to it } }
            .toMap()
        Graph.log.add("adb", "Simulated ${type.name.lowercase()} event: $vars", true)
        runAsync { MacroEngine.dispatch(context, TriggerEvent(type, vars)).join() }
    }
}
