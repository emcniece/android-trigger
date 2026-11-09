package com.emcniece.trigger.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

enum class ActionType(val label: String) {
    HTTP("HTTP request"),
    NOTIFY("Show notification"),
    SMS("Send SMS"),
    VIBRATE("Vibrate"),
    OPEN_APP("Open app"),
    OPEN_URL("Open URL"),
    VOLUME("Set volume"),
    DND("Do Not Disturb"),
    INTENT("Send intent"),
    RUN_MACRO("Run another macro");

    fun default(): Action = when (this) {
        HTTP -> Action.HttpRequest()
        NOTIFY -> Action.ShowNotification()
        SMS -> Action.SendSms()
        VIBRATE -> Action.Vibrate()
        OPEN_APP -> Action.OpenApp()
        OPEN_URL -> Action.OpenUrl()
        VOLUME -> Action.SetVolume()
        DND -> Action.DoNotDisturb()
        INTENT -> Action.SendIntent()
        RUN_MACRO -> Action.RunMacro()
    }
}

val HTTP_METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE")

@Serializable
data class HttpHeader(val name: String = "", val value: String = "")

@Serializable
data class IntentExtra(val key: String = "", val value: String = "")

enum class VolumeStream(val label: String) {
    MEDIA("Media"),
    RING("Ring"),
    NOTIFICATION("Notification"),
    ALARM("Alarm"),
}

enum class DndMode(val label: String) {
    OFF("Off (allow everything)"),
    PRIORITY("Priority only"),
    ALARMS("Alarms only"),
    TOTAL_SILENCE("Total silence"),
}

/** Reads headers as a list, and also accepts the original format: one `Name: value` per line in a string. */
object HeaderListSerializer : JsonTransformingSerializer<List<HttpHeader>>(ListSerializer(HttpHeader.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonPrimitive || !element.isString) return element
        return JsonArray(
            element.content.lines().mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) return@mapNotNull null
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(line.substring(0, idx).trim()),
                        "value" to JsonPrimitive(line.substring(idx + 1).trim()),
                    ),
                )
            },
        )
    }
}

@Serializable
sealed interface Action {
    val type: ActionType
    fun describe(): String

    @Serializable
    @SerialName("http")
    data class HttpRequest(
        val method: String = "POST",
        val url: String = "",
        @Serializable(with = HeaderListSerializer::class)
        val headers: List<HttpHeader> = listOf(HttpHeader("Content-Type", "application/json")),
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

    @Serializable
    @SerialName("open_app")
    data class OpenApp(val packageName: String = "", val appLabel: String = "") : Action {
        override val type: ActionType get() = ActionType.OPEN_APP
        override fun describe() = "Open ${appLabel.ifBlank { packageName.ifBlank { "(no app)" } }}"
    }

    @Serializable
    @SerialName("open_url")
    data class OpenUrl(val url: String = "") : Action {
        override val type: ActionType get() = ActionType.OPEN_URL
        override fun describe() = "Open ${url.ifBlank { "(no URL)" }}"
    }

    @Serializable
    @SerialName("volume")
    data class SetVolume(val stream: VolumeStream = VolumeStream.MEDIA, val percent: Int = 50) : Action {
        override val type: ActionType get() = ActionType.VOLUME
        override fun describe() = "${stream.label} volume $percent%"
    }

    @Serializable
    @SerialName("dnd")
    data class DoNotDisturb(val mode: DndMode = DndMode.PRIORITY) : Action {
        override val type: ActionType get() = ActionType.DND
        override fun describe() = "Do Not Disturb: ${mode.label.lowercase()}"
    }

    /** A broadcast intent, e.g. for Tasker or Home Assistant. Extras are sent as strings. */
    @Serializable
    @SerialName("intent")
    data class SendIntent(
        val intentAction: String = "",
        val packageName: String = "",
        val extras: List<IntentExtra> = emptyList(),
    ) : Action {
        override val type: ActionType get() = ActionType.INTENT
        override fun describe() = "Broadcast ${intentAction.ifBlank { "(no action)" }}"
    }

    /** Runs another macro's actions. [macroName] is a display copy so lists don't need a lookup. */
    @Serializable
    @SerialName("run_macro")
    data class RunMacro(
        val macroId: String = "",
        val macroName: String = "",
        val checkConditions: Boolean = true,
    ) : Action {
        override val type: ActionType get() = ActionType.RUN_MACRO
        override fun describe() = "Run \"${macroName.ifBlank { "(no macro)" }}\""
    }
}
