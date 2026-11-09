package com.emcniece.trigger

import android.app.Application
import android.content.Context
import com.emcniece.trigger.data.EventLog
import com.emcniece.trigger.data.MacroRepository
import com.emcniece.trigger.engine.Notifications
import com.emcniece.trigger.triggers.AlarmScheduler
import com.emcniece.trigger.triggers.MonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Minimal service locator; initialised once per process in [TriggerApp]. */
object Graph {
    lateinit var macros: MacroRepository
        private set
    lateinit var log: EventLog
        private set
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        macros = MacroRepository(context)
        log = EventLog(context)
    }

    /** Bring alarms and the monitor service in line with the current macro set. */
    fun syncBackgroundWork(context: Context) {
        val list = macros.macros.value
        AlarmScheduler.sync(context, list)
        MonitorService.sync(context, list)
    }
}

class TriggerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifications.createChannels(this)
        Graph.macros.macros
            .onEach { Graph.syncBackgroundWork(this) }
            .launchIn(Graph.scope)
    }
}
