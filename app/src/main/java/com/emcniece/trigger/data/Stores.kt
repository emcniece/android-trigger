package com.emcniece.trigger.data

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import com.emcniece.trigger.model.AppJson
import com.emcniece.trigger.model.Macro
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** A small list persisted as JSON in app-private storage, cached in memory. */
open class JsonListStore<T>(context: Context, fileName: String, private val serializer: KSerializer<T>) {
    private val file = AtomicFile(File(context.filesDir, fileName))
    private val listSerializer = ListSerializer(serializer)
    private val lock = Any()
    private val state = MutableStateFlow(load())
    val items: StateFlow<List<T>> = state

    private fun load(): List<T> = try {
        if (!file.baseFile.exists()) emptyList()
        else AppJson.decodeFromString(listSerializer, file.readFully().decodeToString())
    } catch (e: Exception) {
        Log.e("Trigger", "Failed to read ${file.baseFile.name}", e)
        emptyList()
    }

    protected fun update(transform: (List<T>) -> List<T>) {
        synchronized(lock) {
            val next = transform(state.value)
            val out = file.startWrite()
            try {
                out.write(AppJson.encodeToString(listSerializer, next).encodeToByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
            state.value = next
        }
    }
}

class MacroRepository(context: Context) : JsonListStore<Macro>(context, "macros.json", Macro.serializer()) {
    val macros: StateFlow<List<Macro>> get() = items

    fun get(id: String): Macro? = items.value.find { it.id == id }

    fun upsert(macro: Macro) = update { list ->
        if (list.any { it.id == macro.id }) list.map { if (it.id == macro.id) macro else it } else list + macro
    }

    fun delete(id: String) = update { list -> list.filterNot { it.id == id } }

    /** Adds [incoming] macros, replacing any existing macro with the same ID in place. */
    fun importAll(incoming: List<Macro>) = update { list ->
        val byId = incoming.associateBy { it.id }
        val existingIds = list.map { it.id }.toSet()
        list.map { byId[it.id] ?: it } + incoming.filter { it.id !in existingIds }
    }

    fun setEnabled(id: String, enabled: Boolean) = update { list ->
        list.map { if (it.id == id) it.copy(enabled = enabled) else it }
    }
}

@Serializable
data class LogEntry(
    val time: Long,
    val macroName: String,
    val message: String,
    val success: Boolean,
)

class EventLog(context: Context) : JsonListStore<LogEntry>(context, "log.json", LogEntry.serializer()) {
    val entries: StateFlow<List<LogEntry>> get() = items

    fun add(macroName: String, message: String, success: Boolean) {
        Log.i("Trigger", "[$macroName] $message")
        update { list -> (listOf(LogEntry(System.currentTimeMillis(), macroName, message, success)) + list).take(MAX) }
    }

    fun clear() = update { emptyList() }

    private companion object {
        const val MAX = 300
    }
}
