package com.emcniece.trigger.model

data class VariableInfo(val name: String, val description: String)

enum class VariableFormat(val label: String, val suffix: String) {
    PLAIN("As is", ""),
    JSON("JSON-escaped", ":json"),
    URL("URL-encoded", ":url"),
}

/** Catalogue of template variables, and a checker for `{placeholders}` that won't be filled in. */
object Variables {
    /** Set by MacroEngine for every run, whatever the trigger. */
    val common = listOf(
        VariableInfo("macro_name", "Name of this macro"),
        VariableInfo("trigger", "Trigger type, e.g. sms"),
        VariableInfo("timestamp", "When the macro ran (ISO-8601)"),
        VariableInfo("epoch_ms", "When the macro ran, in ms since 1970"),
        VariableInfo("device", "Phone model"),
    )

    private val descriptions = mapOf(
        "sms_sender" to "Sender's phone number",
        "sms_body" to "Message text",
        "notif_package" to "Package of the app that posted it",
        "notif_title" to "Notification title",
        "notif_text" to "Notification text",
        "power_state" to "connected or disconnected",
        "battery_level" to "Battery % now",
        "battery_previous" to "Battery % before this change",
        "network_state" to "connected or disconnected",
        "network_type" to "wifi, cellular, ethernet…",
        "network_scope" to "Which network the trigger watches: any, wifi or cellular",
        "time" to "Scheduled time, HH:MM",
        "call_state" to "ringing, answered, ended or missed",
        "call_number" to "Caller's number (needs call log access)",
        "headphones_state" to "connected or disconnected",
        "headphones_type" to "wired, bluetooth or usb",
        "headphones_name" to "Device name reported by Android",
        "bt_state" to "connected or disconnected",
        "bt_name" to "Bluetooth device name",
        "bt_address" to "Bluetooth device address",
        "screen_state" to "on, off or unlocked",
    )

    fun forTrigger(type: TriggerType): List<VariableInfo> =
        type.vars.map { VariableInfo(it, descriptions[it].orEmpty()) }

    fun available(type: TriggerType): Set<String> = (type.vars + common.map { it.name }).toSet()

    // Looser than Templates' pattern so typos in the format (e.g. {sms_body:xml}) are caught too.
    private val TOKEN = Regex("""\{([A-Za-z0-9_]+)(?::([A-Za-z0-9_]*))?\}""")
    private val FORMATS = VariableFormat.entries.map { it.suffix.removePrefix(":") }.filter { it.isNotEmpty() }.toSet()
    private val allTriggerVars: Map<String, TriggerType> =
        TriggerType.entries.flatMap { t -> t.vars.map { it to t } }.toMap()

    /** Human-readable problems with placeholders in [text] for a macro with trigger [type]. */
    fun problems(text: String, type: TriggerType): List<String> {
        val known = available(type)
        return TOKEN.findAll(text).mapNotNull { m ->
            val token = m.value
            val name = m.groupValues[1]
            val format = m.groups[2]?.value
            when {
                name !in known -> allTriggerVars[name]?.let { "$token isn't set by the “${type.label}” trigger" }
                    ?: "Unknown variable $token"
                format != null && format !in FORMATS -> "Unknown format in $token; use :json or :url"
                else -> null
            }
        }.distinct().toList()
    }
}
