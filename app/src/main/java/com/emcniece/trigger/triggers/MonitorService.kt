package com.emcniece.trigger.triggers

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.Notifications
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.TriggerEvent
import com.emcniece.trigger.model.TriggerType

/**
 * Battery, charger, connectivity, screen and call-state broadcasts (and audio device changes)
 * can't be received by manifest receivers since Android 8, so this foreground service registers
 * for them at runtime. It only runs while at least one enabled macro needs it.
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
                Intent.ACTION_SCREEN_ON -> emitScreen("on")
                Intent.ACTION_SCREEN_OFF -> emitScreen("off")
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

    /**
     * Unlock and call-state broadcasts come from SystemUI and the telephony process rather than
     * system_server, so a not-exported receiver never sees them. Both are protected broadcasts
     * (only system components can send them), so exporting this receiver is safe.
     */
    private val systemAppReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TelephonyManager.ACTION_PHONE_STATE_CHANGED -> onPhoneState(intent)
                Intent.ACTION_USER_PRESENT -> emitScreen("unlocked")
            }
        }
    }

    // Some devices (e.g. OnePlus/ColorOS) send USER_PRESENT twice per unlock.
    private var lastScreenState = ""
    private var lastScreenAt = 0L

    private fun emitScreen(state: String) {
        val now = SystemClock.elapsedRealtime()
        if (state == lastScreenState && now - lastScreenAt < 2_000) return
        lastScreenState = state
        lastScreenAt = now
        emit(TriggerType.SCREEN, "screen_state" to state)
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

    // ---- Phone calls: turn IDLE/RINGING/OFFHOOK transitions into ringing/answered/ended/missed ----

    private var callState: String = TelephonyManager.EXTRA_STATE_IDLE
    private var callNumber = ""

    private fun onPhoneState(intent: Intent) {
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        // With call log access each change arrives twice, with and without the number.
        // Keep the number from whichever copy has it.
        if (number.isNotEmpty()) callNumber = number
        if (state == callState) return
        val prev = callState
        callState = state
        when (state) {
            // Give the numbered duplicate a moment to arrive before reporting the ring.
            TelephonyManager.EXTRA_STATE_RINGING -> main.postDelayed({ emitCall("ringing") }, 400)
            TelephonyManager.EXTRA_STATE_OFFHOOK -> if (prev == TelephonyManager.EXTRA_STATE_RINGING) emitCall("answered")
            TelephonyManager.EXTRA_STATE_IDLE -> {
                emitCall(if (prev == TelephonyManager.EXTRA_STATE_RINGING) "missed" else "ended")
                callNumber = ""
            }
        }
    }

    private fun emitCall(state: String) = emit(TriggerType.CALL, "call_state" to state, "call_number" to callNumber)

    // ---- Headphones: wired, Bluetooth and USB audio outputs ----

    private data class Headset(val kind: String, val name: String)

    private val headsets = mutableMapOf<Int, Headset>()
    private var audioPrimed = false

    @SuppressLint("InlinedApi") // TYPE_BLE_HEADSET is a compile-time int; it simply never appears before API 31
    private fun headphoneKind(d: AudioDeviceInfo): String? {
        if (!d.isSink) return null
        return when (d.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
            else -> null
        }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            // Registration reports every current device first; record those without firing.
            val primed = audioPrimed
            audioPrimed = true
            for (d in addedDevices) {
                val kind = headphoneKind(d) ?: continue
                val headset = Headset(kind, d.productName?.toString().orEmpty())
                val alreadyConnected = headset in headsets.values // same headset exposed as several outputs
                headsets[d.id] = headset
                if (primed && !alreadyConnected) emitHeadset("connected", headset)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            for (d in removedDevices) {
                val headset = headsets.remove(d.id) ?: continue
                if (headset !in headsets.values) emitHeadset("disconnected", headset)
            }
        }
    }

    private fun emitHeadset(state: String, h: Headset) = emit(
        TriggerType.HEADPHONES,
        "headphones_state" to state,
        "headphones_type" to h.kind,
        "headphones_name" to h.name,
    )

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
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            val systemAppFilter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED) // delivered only with READ_PHONE_STATE
            }
            ContextCompat.registerReceiver(this, systemAppReceiver, systemAppFilter, ContextCompat.RECEIVER_EXPORTED)
            val cm = getSystemService(ConnectivityManager::class.java)
            // When online, the first callback seeds state silently (lastNet == null). When offline
            // there's no initial callback, so seed "disconnected" now and the next network fires.
            lastNet = if (cm.activeNetwork == null) NetState(false, "none") else null
            cm.registerDefaultNetworkCallback(netCallback, main)
            getSystemService(AudioManager::class.java).registerAudioDeviceCallback(audioCallback, main)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            unregisterReceiver(receiver)
            unregisterReceiver(systemAppReceiver)
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
            getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioCallback)
            main.removeCallbacksAndMessages(null)
            registered = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val NEEDS_SERVICE = setOf(
            TriggerType.POWER,
            TriggerType.BATTERY,
            TriggerType.NETWORK,
            TriggerType.CALL,
            TriggerType.HEADPHONES,
            TriggerType.SCREEN,
        )

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
