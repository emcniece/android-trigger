package com.emcniece.trigger.data

import com.emcniece.trigger.model.AppJson
import com.emcniece.trigger.model.Macro
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.util.UUID

/** File format for exported macros. */
@Serializable
data class MacroExport(
    val format: String = MacroTransfer.FORMAT,
    val version: Int = 1,
    val exportedAt: String = "",
    val appVersion: String = "",
    val macros: List<Macro> = emptyList(),
)

class ImportException(message: String) : Exception(message)

object MacroTransfer {
    const val FORMAT = "trigger-macros"
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    private val PrettyJson = Json(AppJson) { prettyPrint = true }

    fun encode(macros: List<Macro>, appVersion: String): String = PrettyJson.encodeToString(
        MacroExport.serializer(),
        MacroExport(exportedAt = OffsetDateTime.now().toString(), appVersion = appVersion, macros = macros),
    )

    /** Accepts an export file, or a bare JSON array of macros (the app's internal storage format). */
    fun decode(text: String): List<Macro> {
        val root = try {
            AppJson.parseToJsonElement(text)
        } catch (e: Exception) {
            throw ImportException("file isn't valid JSON")
        }
        return try {
            when (root) {
                is JsonArray -> AppJson.decodeFromJsonElement(ListSerializer(Macro.serializer()), root)
                is JsonObject -> {
                    if (root["format"]?.jsonPrimitive?.contentOrNull != FORMAT) throw ImportException("not a Trigger macro export")
                    AppJson.decodeFromJsonElement(MacroExport.serializer(), root).macros
                }
                else -> throw ImportException("not a Trigger macro export")
            }
        } catch (e: ImportException) {
            throw e
        } catch (e: Exception) {
            throw ImportException("unrecognised macro data (${e.message?.lineSequence()?.firstOrNull()})")
        }
    }

    /** Gives macros whose IDs already exist a fresh ID and a "(copy)" suffix so nothing is overwritten. */
    fun asCopies(incoming: List<Macro>, existingIds: Set<String>): List<Macro> = incoming.map {
        if (it.id in existingIds) it.copy(id = UUID.randomUUID().toString(), name = "${it.displayName} (copy)") else it
    }
}
