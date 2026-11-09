package com.emcniece.trigger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ActionType(val label: String) {
    HTTP("HTTP request"),
    NOTIFY("Show notification"),
    SMS("Send SMS"),
    VIBRATE("Vibrate");

    fun default(): Action = when (this) {
        HTTP -> Action.HttpRequest()
        NOTIFY -> Action.ShowNotification()
        SMS -> Action.SendSms()
        VIBRATE -> Action.Vibrate()
    }
}

val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

@Serializable
sealed interface Action {
    val type: ActionType
    fun describe(): String

    @Serializable
    @SerialName("http")
    data class HttpRequest(
        val method: String = "POST",
        val url: String = "",
        /** One `Name: value` header per line. */
        val headers: String = "Content-Type: application/json",
        val body: String = "{\"trigger\": \"{trigger}\", \"macro\": \"{macro_name:json}\"}",
    ) : Action {
        override val type: ActionType get() = ActionType.HTTP
        override fun describe() = "$method ${url.ifBlank { "(no URL)" }}"
    }

    @Serializable
    @SerialName("notify")
    data class ShowNotification(
        val title: String = "{macro_name}",
        val text: String = "Triggered at {timestamp}",
    ) : Action {
        override val type: ActionType get() = ActionType.NOTIFY
        override fun describe() = "Notify \"$title\""
    }

    @Serializable
    @SerialName("sms")
    data class SendSms(
        val phoneNumber: String = "",
        val message: String = "",
    ) : Action {
        override val type: ActionType get() = ActionType.SMS
        override fun describe() = "SMS to ${phoneNumber.ifBlank { "(no number)" }}"
    }

    @Serializable
    @SerialName("vibrate")
    data class Vibrate(val durationMs: Long = 500) : Action {
        override val type: ActionType get() = ActionType.VIBRATE
        override fun describe() = "Vibrate ${durationMs}ms"
    }
}
