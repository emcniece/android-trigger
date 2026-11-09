package com.emcniece.trigger.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "kind"
}

/** A macro: when [trigger] fires and its filters match, run [actions] in order. */
@Serializable
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val trigger: Trigger = Trigger.SmsReceived(),
    val constraints: List<Constraint> = emptyList(),
    val constraintMatch: ConstraintMatch = ConstraintMatch.ALL,
    val actions: List<Action> = emptyList(),
) {
    val displayName: String get() = name.ifBlank { trigger.type.label }

    /** Constraints that block this macro right now, or empty if it may run. */
    fun blockingConstraints(state: DeviceState): List<Constraint> {
        if (constraints.isEmpty()) return emptyList()
        val unmet = constraints.filterNot { it.isMet(state) }
        return when (constraintMatch) {
            ConstraintMatch.ALL -> unmet
            ConstraintMatch.ANY -> if (unmet.size == constraints.size) unmet else emptyList()
        }
    }

    fun describeConstraints(): String =
        constraints.joinToString(if (constraintMatch == ConstraintMatch.ALL) " and " else " or ") { it.describe() }
}

@Serializable
enum class MatchMode(val label: String) {
    ANY("Anything"),
    CONTAINS("Contains"),
    EQUALS("Equals"),
    STARTS_WITH("Starts with"),
    ENDS_WITH("Ends with"),
    REGEX("Matches regex"),
}

/** A text filter applied to a trigger field (SMS body, sender, notification text...). */
@Serializable
data class TextMatch(
    val mode: MatchMode = MatchMode.ANY,
    val pattern: String = "",
    val ignoreCase: Boolean = true,
) {
    val isActive: Boolean get() = mode != MatchMode.ANY

    fun matches(input: String?): Boolean {
        val s = input.orEmpty()
        return when (mode) {
            MatchMode.ANY -> true
            MatchMode.CONTAINS -> s.contains(pattern, ignoreCase)
            MatchMode.EQUALS -> s.equals(pattern, ignoreCase)
            MatchMode.STARTS_WITH -> s.startsWith(pattern, ignoreCase)
            MatchMode.ENDS_WITH -> s.endsWith(pattern, ignoreCase)
            MatchMode.REGEX -> runCatching {
                val opts = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(pattern, opts).containsMatchIn(s)
            }.getOrDefault(false)
        }
    }

    fun describe(): String =
        if (!isActive) "anything" else "${mode.label.lowercase()} \"$pattern\""
}
