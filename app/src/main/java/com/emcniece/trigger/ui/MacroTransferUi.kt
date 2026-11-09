package com.emcniece.trigger.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.emcniece.trigger.Graph
import com.emcniece.trigger.data.ImportException
import com.emcniece.trigger.data.MacroTransfer
import com.emcniece.trigger.model.Macro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MacroTransferActions(val startImport: () -> Unit, val startExport: () -> Unit)

private fun count(n: Int) = if (n == 1) "1 macro" else "$n macros"

private fun appVersion(context: Context) =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

/** File pickers plus the confirmation dialogs for importing and exporting macros. */
@Composable
fun rememberMacroTransfer(snackbar: SnackbarHostState): MacroTransferActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmExport by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<List<Macro>?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val macros = Graph.macros.macros.value
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    val json = MacroTransfer.encode(macros, appVersion(context))
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.encodeToByteArray()) }
                        ?: error("couldn't open file")
                }
                "Exported ${count(macros.size)}"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            snackbar.showSnackbar(message)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val macros = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw ImportException("couldn't open file")
                    if (bytes.size > MacroTransfer.MAX_FILE_BYTES) throw ImportException("file is too large")
                    MacroTransfer.decode(bytes.decodeToString())
                }
                if (macros.isEmpty()) snackbar.showSnackbar("No macros in that file") else pendingImport = macros
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't import: ${e.message}")
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text("Export macros?") },
            text = {
                Text(
                    "The file contains everything in your macros, including URLs, phone numbers and header " +
                        "values such as webhook secrets. Keep it somewhere private.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExport = false
                    exportLauncher.launch("trigger-macros-${LocalDate.now()}.json")
                }) { Text("Export") }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("Cancel") } },
        )
    }

    pendingImport?.let { incoming ->
        val existingIds = Graph.macros.macros.value.map { it.id }.toSet()
        val conflicts = incoming.count { it.id in existingIds }
        fun finish(macros: List<Macro>) {
            Graph.macros.importAll(macros)
            pendingImport = null
            scope.launch { snackbar.showSnackbar("Imported ${count(macros.size)}") }
        }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import ${count(incoming.size)}?") },
            text = {
                Text(
                    buildString {
                        incoming.take(8).forEach { append("• ").append(it.displayName).append('\n') }
                        if (incoming.size > 8) append("…and ${incoming.size - 8} more\n")
                        if (conflicts > 0) {
                            append("\n${count(conflicts)} already ")
                            append(if (conflicts == 1) "exists" else "exist")
                            append(" on this phone. Replace, or keep both?")
                        }
                    }.trimEnd(),
                )
            },
            confirmButton = {
                TextButton(onClick = { finish(incoming) }) { Text(if (conflicts > 0) "Replace" else "Import") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) { Text("Cancel") }
                    if (conflicts > 0) {
                        TextButton(onClick = { finish(MacroTransfer.asCopies(incoming, existingIds)) }) { Text("Keep both") }
                    }
                }
            },
        )
    }

    return remember {
        MacroTransferActions(
            // Some file providers report .json as text or octet-stream, so accept those too.
            startImport = { importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
            startExport = { confirmExport = true },
        )
    }
}
