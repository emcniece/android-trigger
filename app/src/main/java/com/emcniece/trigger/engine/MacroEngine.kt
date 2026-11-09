package com.emcniece.trigger.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.os.Build
import com.emcniece.trigger.Graph
import com.emcniece.trigger.model.ConstraintMatch
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.TriggerEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

object MacroEngine {

    /** Find enabled macros matching [event] and run them on the background scope. */
    fun dispatch(context: Context, event: TriggerEvent): Job {
        val app = context.applicationContext
        val matched = Graph.macros.macros.value.filter { macro ->
            macro.enabled &&
                (event.targetMacroId == null || event.targetMacroId == macro.id) &&
                macro.trigger.matches(event)
        }
        return Graph.scope.launch {
            val state = AndroidDeviceState(app)
            matched.forEach { macro ->
                val blocking = macro.blockingConstraints(state)
                if (blocking.isEmpty()) {
                    runMacro(app, macro, event.vars, allowRetry = true)
                } else {
                    val joiner = if (macro.constraintMatch == ConstraintMatch.ALL) ", " else " or "
                    Graph.log.add(macro.displayName, "Skipped, needs: ${blocking.joinToString(joiner) { it.describe() }}", true)
                }
            }
        }
    }

    suspend fun runMacro(context: Context, macro: Macro, eventVars: Map<String, String>, allowRetry: Boolean) {
        val now = OffsetDateTime.now()
        val vars = buildMap {
            put("trigger", macro.trigger.type.name.lowercase())
            put("macro_name", macro.displayName)
            put("timestamp", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("epoch_ms", now.toInstant().toEpochMilli().toString())
            put("device", Build.MODEL)
            putAll(eventVars)
        }
        if (macro.actions.isEmpty()) {
            Graph.log.add(macro.displayName, "Triggered (no actions)", true)
            return
        }
        for (action in macro.actions) {
            try {
                val result = ActionRunner.run(context, macro, action, vars, allowRetry)
                Graph.log.add(macro.displayName, "${action.type.label}: $result", true)
            } catch (e: Exception) {
                Graph.log.add(macro.displayName, "${action.type.label} failed: ${e.message ?: e.javaClass.simpleName}", false)
            }
        }
    }
}

/**
 * Keeps a manifest receiver's process alive while macros run.
 * Broadcast receivers get ~10s in the foreground and longer in the background; we cap at 25s,
 * and HTTP failures fall back to WorkManager anyway.
 */
fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    Graph.scope.launch {
        try {
            withTimeoutOrNull(25_000) { block() }
        } finally {
            pending.finish()
        }
    }
}
