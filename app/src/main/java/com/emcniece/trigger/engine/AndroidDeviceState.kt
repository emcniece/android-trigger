package com.emcniece.trigger.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.emcniece.trigger.model.DeviceState
import java.time.LocalDateTime

/** Reads device conditions on demand; each value is fetched at most once per instance. */
class AndroidDeviceState(context: Context) : DeviceState {
    private val app = context.applicationContext

    override val now: LocalDateTime by lazy { LocalDateTime.now() }

    override val batteryLevel: Int by lazy {
        app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    /** True while plugged in and charging, or plugged in and full. */
    override val isCharging: Boolean by lazy { app.getSystemService(BatteryManager::class.java).isCharging }

    override val networkType: String by lazy {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }

    override val screenOn: Boolean by lazy { app.getSystemService(PowerManager::class.java).isInteractive }
}
