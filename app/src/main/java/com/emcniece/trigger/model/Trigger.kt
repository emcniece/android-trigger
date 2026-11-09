package com.emcniece.trigger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class TriggerType(val label: String, val vars: List<String>) {
    SMS("SMS received", listOf("sms_sender", "sms_body")),
    NOTIFICATION("Notification posted", listOf("notif_package", "notif_title", "notif_text")),
    POWER("Charger connected / disconnected", listOf("power_state")),
    BATTERY("Battery level", listOf("battery_level", "battery_previous")),
    NETWORK("Network connected / lost", listOf("network_state", "network_type", "network_scope")),
    BOOT("Device boot", emptyList()),
    TIME("Time of day", listOf("time")),
    CALL("Phone call", listOf("call_state", "call_number")),
    HEADPHONES("Headphones connected / disconnected", listOf("headphones_state", "headphones_type", "headphones_name")),
    BLUETOOTH("Bluetooth device connected / disconnected", listOf("bt_state", "bt_name", "bt_address")),
    SCREEN("Screen on / off / unlocked", listOf("screen_state"));

    fun default(): Trigger = when (this) {
        SMS -> Trigger.SmsReceived()
        NOTIFICATION -> Trigger.NotificationPosted()
        POWER -> Trigger.PowerChanged()
        BATTERY -> Trigger.BatteryLevel()
        NETWORK -> Trigger.NetworkChanged()
        BOOT -> Trigger.Boot
        TIME -> Trigger.DailyTime()
        CALL -> Trigger.PhoneCall()
        HEADPHONES -> Trigger.Headphones()
        BLUETOOTH -> Trigger.BluetoothDevice()
        SCREEN -> Trigger.ScreenEvent()
    }

    /** Fake event data used by the "Test" button. */
    fun sampleVars(): Map<String, String> = when (this) {
        SMS -> mapOf("sms_sender" to "+15555550123", "sms_body" to "Test message")
        NOTIFICATION -> mapOf(
            "notif_package" to "com.example.app",
            "notif_title" to "Test title",
            "notif_text" to "Test notification text",
        )
        POWER -> mapOf("power_state" to "connected")
        BATTERY -> mapOf("battery_level" to "15", "battery_previous" to "16")
        NETWORK -> mapOf("network_state" to "connected", "network_type" to "wifi", "network_scope" to "wifi")
        BOOT -> emptyMap()
        TIME -> mapOf("time" to "08:00")
        CALL -> mapOf("call_state" to "missed", "call_number" to "+15555550123")
        HEADPHONES -> mapOf("headphones_state" to "connected", "headphones_type" to "bluetooth", "headphones_name" to "Test Buds")
        BLUETOOTH -> mapOf("bt_state" to "connected", "bt_name" to "Car Audio", "bt_address" to "00:11:22:33:44:55")
        SCREEN -> mapOf("screen_state" to "unlocked")
    }
}

enum class CallEvent(val id: String, val label: String) {
    RINGING("ringing", "Incoming call ringing"),
    ANSWERED("answered", "Incoming call answered"),
    ENDED("ended", "Call ended"),
    MISSED("missed", "Missed call"),
}

enum class HeadphoneKind(val id: String, val label: String) {
    ANY("any", "Any headphones"),
    WIRED("wired", "Wired"),
    BLUETOOTH("bluetooth", "Bluetooth"),
    USB("usb", "USB"),
}

enum class ScreenEventKind(val id: String, val label: String) {
    ON("on", "Screen turned on"),
    OFF("off", "Screen turned off"),
    UNLOCKED("unlocked", "Phone unlocked"),
}

enum class NetworkTransport(val id: String, val label: String) {
    ANY("any", "Any network"),
    WIFI("wifi", "Wi-Fi"),
    CELLULAR("cellular", "Mobile data"),
}

/** An OS event delivered to the engine. [targetMacroId] restricts delivery to one macro (alarms). */
data class TriggerEvent(
    val type: TriggerType,
    val vars: Map<String, String> = emptyMap(),
    val targetMacroId: String? = null,
)

@Serializable
sealed interface Trigger {
    val type: TriggerType
    fun matches(event: TriggerEvent): Boolean
    fun describe(): String

    @Serializable
    @SerialName("sms")
    data class SmsReceived(
        val sender: TextMatch = TextMatch(),
        val body: TextMatch = TextMatch(),
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.SMS
        override fun matches(event: TriggerEvent) = event.type == type &&
            sender.matches(event.vars["sms_sender"]) &&
            body.matches(event.vars["sms_body"])

        override fun describe() = buildString {
            append("SMS received")
            if (sender.isActive) append(", sender ${sender.describe()}")
            if (body.isActive) append(", text ${body.describe()}")
        }
    }

    @Serializable
    @SerialName("notification")
    data class NotificationPosted(
        val packageName: String = "",
        val text: TextMatch = TextMatch(),
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.NOTIFICATION
        override fun matches(event: TriggerEvent): Boolean {
            if (event.type != type) return false
            if (packageName.isNotBlank() && packageName.trim() != event.vars["notif_package"]) return false
            return text.matches(event.vars["notif_title"]) || text.matches(event.vars["notif_text"])
        }

        override fun describe() = buildString {
            append("Notification")
            if (packageName.isNotBlank()) append(" from $packageName")
            if (text.isActive) append(", text ${text.describe()}")
        }
    }

    @Serializable
    @SerialName("power")
    data class PowerChanged(val connected: Boolean = true) : Trigger {
        override val type: TriggerType get() = TriggerType.POWER
        override fun matches(event: TriggerEvent) = event.type == type &&
            event.vars["power_state"] == if (connected) "connected" else "disconnected"

        override fun describe() = if (connected) "Charger connected" else "Charger disconnected"
    }

    /** Fires when the level crosses [threshold] in the chosen direction. */
    @Serializable
    @SerialName("battery")
    data class BatteryLevel(val threshold: Int = 20, val below: Boolean = true) : Trigger {
        override val type: TriggerType get() = TriggerType.BATTERY
        override fun matches(event: TriggerEvent): Boolean {
            if (event.type != type) return false
            val level = event.vars["battery_level"]?.toIntOrNull() ?: return false
            val prev = event.vars["battery_previous"]?.toIntOrNull() ?: return false
            return if (below) prev > threshold && level <= threshold
            else prev < threshold && level >= threshold
        }

        override fun describe() = "Battery ${if (below) "drops to" else "rises to"} $threshold%"
    }

    @Serializable
    @SerialName("network")
    data class NetworkChanged(
        val connected: Boolean = true,
        val transport: NetworkTransport = NetworkTransport.ANY,
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.NETWORK
        override fun matches(event: TriggerEvent) = event.type == type &&
            event.vars["network_scope"] == transport.id &&
            event.vars["network_state"] == if (connected) "connected" else "disconnected"

        override fun describe() = "${transport.label} ${if (connected) "connected" else "lost"}"
    }

    @Serializable
    @SerialName("boot")
    data object Boot : Trigger {
        override val type: TriggerType get() = TriggerType.BOOT
        override fun matches(event: TriggerEvent) = event.type == type
        override fun describe() = "Device boots"
    }

    @Serializable
    @SerialName("time")
    data class DailyTime(val hour: Int = 8, val minute: Int = 0) : Trigger {
        override val type: TriggerType get() = TriggerType.TIME
        // Alarm events are targeted at a single macro, so the type check is sufficient here.
        override fun matches(event: TriggerEvent) = event.type == type
        override fun describe() = "Every day at %02d:%02d".format(hour, minute)
    }

    @Serializable
    @SerialName("call")
    data class PhoneCall(
        val event: CallEvent = CallEvent.MISSED,
        val number: TextMatch = TextMatch(),
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.CALL
        override fun matches(event: TriggerEvent) = event.type == type &&
            event.vars["call_state"] == this.event.id &&
            number.matches(event.vars["call_number"])

        override fun describe() = buildString {
            append(event.label)
            if (number.isActive) append(", number ${number.describe()}")
        }
    }

    @Serializable
    @SerialName("headphones")
    data class Headphones(
        val connected: Boolean = true,
        // Not "kind": that name is reserved for the JSON type discriminator.
        val outputType: HeadphoneKind = HeadphoneKind.ANY,
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.HEADPHONES
        override fun matches(event: TriggerEvent) = event.type == type &&
            event.vars["headphones_state"] == (if (connected) "connected" else "disconnected") &&
            (outputType == HeadphoneKind.ANY || event.vars["headphones_type"] == outputType.id)

        override fun describe() = buildString {
            append(if (outputType == HeadphoneKind.ANY) "Headphones" else "${outputType.label} headphones")
            append(if (connected) " connected" else " disconnected")
        }
    }

    @Serializable
    @SerialName("bluetooth")
    data class BluetoothDevice(
        val connected: Boolean = true,
        val name: TextMatch = TextMatch(),
    ) : Trigger {
        override val type: TriggerType get() = TriggerType.BLUETOOTH
        override fun matches(event: TriggerEvent) = event.type == type &&
            event.vars["bt_state"] == (if (connected) "connected" else "disconnected") &&
            name.matches(event.vars["bt_name"])

        override fun describe() = buildString {
            append("Bluetooth device ")
            append(if (connected) "connected" else "disconnected")
            if (name.isActive) append(", name ${name.describe()}")
        }
    }

    @Serializable
    @SerialName("screen")
    data class ScreenEvent(val event: ScreenEventKind = ScreenEventKind.UNLOCKED) : Trigger {
        override val type: TriggerType get() = TriggerType.SCREEN
        override fun matches(event: TriggerEvent) = event.type == type && event.vars["screen_state"] == this.event.id
        override fun describe() = event.label
    }
}
