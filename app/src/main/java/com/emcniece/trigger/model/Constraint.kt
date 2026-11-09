package com.emcniece.trigger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.LocalTime

/** Snapshot of device conditions that constraints are checked against. */
interface DeviceState {
    val now: LocalDateTime
    val batteryLevel: Int
    val isCharging: Boolean

    /** "wifi", "cellular", "ethernet", "vpn", "other", or "none" when offline. */
    val networkType: String
    val screenOn: Boolean

    /** Current Wi-Fi network name; null when not on Wi-Fi, [UNKNOWN_SSID] when Android won't say. */
    val wifiSsid: String?
}

/** Android hides the SSID without location permission (and location services on). */
const val UNKNOWN_SSID = "<unknown ssid>"

enum class ConstraintType(val label: String) {
    TIME("Time of day"),
    DAYS("Day of week"),
    CHARGING("Charging"),
    BATTERY("Battery level"),
    NETWORK("Network"),
    SCREEN("Screen on / off"),
    WIFI("Wi-Fi network");

    fun default(): Constraint = when (this) {
        TIME -> Constraint.TimeWindow()
        DAYS -> Constraint.DaysOfWeek()
        CHARGING -> Constraint.Charging()
        BATTERY -> Constraint.Battery()
        NETWORK -> Constraint.NetworkIs()
        SCREEN -> Constraint.Screen()
        WIFI -> Constraint.WifiNetwork()
    }
}

@Serializable
enum class ConstraintMatch(val label: String) {
    ALL("All of these"),
    ANY("Any of these"),
}

enum class NetworkRequirement(val label: String) {
    WIFI("On Wi-Fi"),
    CELLULAR("On mobile data"),
    ONLINE("Online"),
    OFFLINE("Offline"),
}

/** A condition that must hold, when the trigger fires, for the macro to run. */
@Serializable
sealed interface Constraint {
    val type: ConstraintType
    fun isMet(state: DeviceState): Boolean
    fun describe(): String

    /** Why this isn't met right now; defaults to the description. */
    fun explainUnmet(state: DeviceState): String = describe()

    /** [start] inclusive to [end] exclusive; wraps past midnight when start > end. Equal times mean all day. */
    @Serializable
    @SerialName("time")
    data class TimeWindow(
        val startHour: Int = 9,
        val startMinute: Int = 0,
        val endHour: Int = 17,
        val endMinute: Int = 0,
    ) : Constraint {
        override val type: ConstraintType get() = ConstraintType.TIME
        private val start: LocalTime get() = LocalTime.of(startHour, startMinute)
        private val end: LocalTime get() = LocalTime.of(endHour, endMinute)

        override fun isMet(state: DeviceState): Boolean {
            val t = state.now.toLocalTime()
            return when {
                start == end -> true
                start < end -> !t.isBefore(start) && t.isBefore(end)
                else -> !t.isBefore(start) || t.isBefore(end)
            }
        }

        override fun describe() = "between %02d:%02d and %02d:%02d".format(startHour, startMinute, endHour, endMinute)
    }

    /** [days] are ISO day numbers: 1 = Monday … 7 = Sunday. */
    @Serializable
    @SerialName("days")
    data class DaysOfWeek(val days: Set<Int> = setOf(1, 2, 3, 4, 5)) : Constraint {
        override val type: ConstraintType get() = ConstraintType.DAYS
        override fun isMet(state: DeviceState) = state.now.dayOfWeek.value in days

        override fun describe() = when (days) {
            emptySet<Int>() -> "on no days"
            (1..7).toSet() -> "every day"
            setOf(1, 2, 3, 4, 5) -> "on weekdays"
            setOf(6, 7) -> "on weekends"
            else -> "on " + days.sorted().joinToString(", ") { DAY_NAMES[it - 1] }
        }

        companion object {
            val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }
    }

    @Serializable
    @SerialName("charging")
    data class Charging(val charging: Boolean = true) : Constraint {
        override val type: ConstraintType get() = ConstraintType.CHARGING
        override fun isMet(state: DeviceState) = state.isCharging == charging
        override fun describe() = if (charging) "charging" else "not charging"
    }

    @Serializable
    @SerialName("battery")
    data class Battery(val threshold: Int = 50, val atLeast: Boolean = true) : Constraint {
        override val type: ConstraintType get() = ConstraintType.BATTERY
        override fun isMet(state: DeviceState) =
            if (atLeast) state.batteryLevel >= threshold else state.batteryLevel <= threshold

        override fun describe() = "battery ${if (atLeast) "at least" else "at most"} $threshold%"
    }

    @Serializable
    @SerialName("network")
    data class NetworkIs(val requirement: NetworkRequirement = NetworkRequirement.WIFI) : Constraint {
        override val type: ConstraintType get() = ConstraintType.NETWORK
        override fun isMet(state: DeviceState) = when (requirement) {
            NetworkRequirement.WIFI -> state.networkType == "wifi"
            NetworkRequirement.CELLULAR -> state.networkType == "cellular"
            NetworkRequirement.ONLINE -> state.networkType != "none"
            NetworkRequirement.OFFLINE -> state.networkType == "none"
        }

        override fun describe() = requirement.label.lowercase()
    }

    @Serializable
    @SerialName("screen")
    data class Screen(val on: Boolean = true) : Constraint {
        override val type: ConstraintType get() = ConstraintType.SCREEN
        override fun isMet(state: DeviceState) = state.screenOn == on
        override fun describe() = if (on) "screen on" else "screen off"
    }

    /**
     * Connected (or not) to a Wi-Fi network whose name matches. When Android won't reveal the
     * name, the condition is treated as not met either way, rather than guessing.
     */
    @Serializable
    @SerialName("wifi")
    data class WifiNetwork(
        val ssid: TextMatch = TextMatch(MatchMode.EQUALS, ""),
        val connected: Boolean = true,
    ) : Constraint {
        override val type: ConstraintType get() = ConstraintType.WIFI
        override fun isMet(state: DeviceState): Boolean {
            val current = state.wifiSsid
            if (current == UNKNOWN_SSID) return false
            val onMatching = current != null && ssid.matches(current)
            return if (connected) onMatching else !onMatching
        }

        override fun describe() = (if (connected) "on Wi-Fi " else "not on Wi-Fi ") +
            if (ssid.isActive) ssid.describe() else "(any network)"

        override fun explainUnmet(state: DeviceState) =
            if (state.wifiSsid == UNKNOWN_SSID) "${describe()} (Android is hiding the Wi-Fi name: allow location \"all the time\" and turn location on)"
            else describe()
    }
}
