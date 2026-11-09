package com.emcniece.trigger.triggers

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.Notifications
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType

/**
 * Battery, charger and connectivity broadcasts can't be received by manifest receivers since
 * Android 8, so this foreground service registers for them at runtime. It only runs while at
 * least one enabled macro needs it.
 */
class MonitorService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var lastBattery = -1
    private var lastNet: NetState? = null
    private var registered = false

    private data class NetState(val connected: Boolean, val type: String)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> emit(TriggerType.POWER, "power_state" to "connected")
                Intent.ACTION_POWER_DISCONNECTED -> emit(TriggerType.POWER, "power_state" to "disconnected")
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level < 0 || scale <= 0) return
                    val pct = level * 100 / scale
                    val prev = lastBattery
                    lastBattery = pct
                    if (prev >= 0 && prev != pct) {
                        emit(TriggerType.BATTERY, "battery_level" to "$pct", "battery_previous" to "$prev")
                    }
                }
            }
        }
    }

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
            onNetwork(NetState(true, type))
        }

        override fun onLost(network: Network) {
            onNetwork(NetState(false, "none"))
        }
    }

    /** Emits separate events for "any network", "wifi" and "cellular" scopes when each changes. */
    private fun onNetwork(state: NetState) {
        val prev = lastNet
        lastNet = state
        if (prev == null || prev == state) return
        fun scopeUp(s: NetState, scope: String) = s.connected && (scope == "any" || s.type == scope)
        for (scope in listOf("any", "wifi", "cellular")) {
            val was = scopeUp(prev, scope)
            val now = scopeUp(state, scope)
            if (was != now) {
                emit(
                    TriggerType.NETWORK,
                    "network_scope" to scope,
                    "network_state" to if (now) "connected" else "disconnected",
                    "network_type" to if (now) state.type else prev.type,
                )
            }
        }
    }

    private fun emit(type: TriggerType, vararg vars: Pair<String, String>) {
        MacroEngine.dispatch(this, TriggerEvent(type, mapOf(*vars)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.MONITOR_ID,
            Notifications.monitorNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (!registered) {
            registered = true
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val cm = getSystemService(ConnectivityManager::class.java)
            // When online, the first callback seeds state silently (lastNet == null). When offline
            // there's no initial callback, so seed "disconnected" now and the next network fires.
            lastNet = if (cm.activeNetwork == null) NetState(false, "none") else null
            cm.registerDefaultNetworkCallback(netCallback, main)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            unregisterReceiver(receiver)
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
            registered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val NEEDS_SERVICE = setOf(TriggerType.POWER, TriggerType.BATTERY, TriggerType.NETWORK)

        fun isNeeded(macros: List<Macro>) = macros.any { it.enabled && it.trigger.type in NEEDS_SERVICE }

        fun sync(context: Context, macros: List<Macro>) {
            val intent = Intent(context, MonitorService::class.java)
            try {
                if (isNeeded(macros)) ContextCompat.startForegroundService(context, intent)
                else context.stopService(intent)
            } catch (e: Exception) {
                // Starting a foreground service from the background is restricted on Android 12+.
                // The next time the app is opened (or the device boots) we'll try again.
                Log.w("Trigger", "Could not start monitor service: ${e.message}")
            }
        }
    }
}
