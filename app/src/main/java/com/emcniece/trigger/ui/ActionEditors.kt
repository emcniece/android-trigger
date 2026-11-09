package com.emcniece.trigger.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.DndMode
import com.emcniece.trigger.model.IntentExtra
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.TriggerType
import com.emcniece.trigger.model.VariableFormat
import com.emcniece.trigger.model.VolumeStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val hintStyle @Composable get() = MaterialTheme.typography.bodySmall

@Composable
private fun Hint(text: String) = Text(text, style = hintStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---- Open app ----

data class LaunchableApp(val label: String, val packageName: String)

@Composable
internal fun OpenAppFields(action: Action.OpenApp, onChange: (Action) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
        Text(if (action.packageName.isEmpty()) "Choose app" else action.appLabel.ifBlank { action.packageName })
    }
    if (action.packageName.isNotEmpty()) {
        Text(action.packageName, style = hintStyle, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (picking) {
        AppPickerDialog(onDismiss = { picking = false }) {
            picking = false
            onChange(action.copy(packageName = it.packageName, appLabel = it.label))
        }
    }
}

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (LaunchableApp) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<LaunchableApp>?>(null) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { LaunchableApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                .distinctBy { it.packageName }
                .sortedBy { it.label.lowercase() }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filtered = list.filter {
                        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                    }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(app.packageName, style = hintStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---- Open URL ----

@Composable
internal fun OpenUrlFields(action: Action.OpenUrl, triggerType: TriggerType, onChange: (Action) -> Unit) {
    TemplateField(
        "URL", action.url, triggerType,
        onChange = { onChange(action.copy(url = it)) },
        placeholder = "https://… , geo:… , tel:…",
        defaultFormat = VariableFormat.URL,
    )
    Hint("Opens in whichever app handles the link, e.g. the browser or Maps.")
}

// ---- Sound ----

@Composable
internal fun VolumeFields(action: Action.SetVolume, onChange: (Action) -> Unit) {
    ChoiceField("Stream", VolumeStream.entries, action.stream, { it.label }) { onChange(action.copy(stream = it)) }
    Text("${action.percent}%", style = MaterialTheme.typography.titleLarge)
    Slider(
        value = action.percent.toFloat(),
        onValueChange = { onChange(action.copy(percent = it.roundToInt())) },
        valueRange = 0f..100f,
    )
}

@Composable
internal fun DndFields(action: Action.DoNotDisturb, onChange: (Action) -> Unit) {
    ChoiceField("Mode", DndMode.entries, action.mode, { it.label }) { onChange(action.copy(mode = it)) }
}

// ---- Intent ----

@Composable
internal fun SendIntentFields(action: Action.SendIntent, triggerType: TriggerType, onChange: (Action) -> Unit) {
    TemplateField(
        "Action", action.intentAction, triggerType,
        onChange = { onChange(action.copy(intentAction = it)) },
        monospace = true,
        placeholder = "com.example.ACTION_DO_THING",
    )
    TemplateField(
        "Package (optional)", action.packageName, triggerType,
        onChange = { onChange(action.copy(packageName = it)) },
        monospace = true,
        placeholder = "com.example.app",
    )
    Hint("Most apps only receive broadcasts addressed to their package.")
    ExtrasEditor(action.extras, triggerType) { onChange(action.copy(extras = it)) }
}

@Composable
private fun ExtrasEditor(extras: List<IntentExtra>, triggerType: TriggerType, onChange: (List<IntentExtra>) -> Unit) {
    fun replace(i: Int, e: IntentExtra) = onChange(extras.toMutableList().also { it[i] = e })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Extras (sent as text)", style = MaterialTheme.typography.labelLarge)
        extras.forEachIndexed { i, extra ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = extra.key,
                        onValueChange = { replace(i, extra.copy(key = it)) },
                        label = { Text("Key") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TemplateField("Value", extra.value, triggerType, onChange = { replace(i, extra.copy(value = it)) }, monospace = true)
                }
                IconButton(onClick = { onChange(extras.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove extra")
                }
            }
        }
        TextButton(onClick = { onChange(extras + IntentExtra()) }) {
            Icon(Icons.Default.Add, null)
            Text("Add extra", Modifier.padding(start = 8.dp))
        }
    }
}

// ---- Run another macro ----

@Composable
internal fun RunMacroFields(action: Action.RunMacro, otherMacros: List<Macro>, onChange: (Action) -> Unit) {
    if (otherMacros.isEmpty() && action.macroId.isEmpty()) {
        Hint("Create another macro first, then pick it here.")
        return
    }
    val ids = otherMacros.map { it.id }.let { if (action.macroId in it || action.macroId.isEmpty()) it else listOf(action.macroId) + it }
    val selected = action.macroId.ifEmpty { "" }
    ChoiceField(
        label = "Macro",
        options = if (selected.isEmpty()) listOf("") + ids else ids,
        selected = selected,
        optionLabel = { id ->
            when {
                id.isEmpty() -> "Choose a macro"
                else -> otherMacros.find { it.id == id }?.displayName ?: "${action.macroName.ifBlank { "Unknown" }} (deleted)"
            }
        },
        onSelect = { id ->
            val m = otherMacros.find { it.id == id } ?: return@ChoiceField
            onChange(action.copy(macroId = m.id, macroName = m.displayName))
        },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = action.checkConditions, onCheckedChange = { onChange(action.copy(checkConditions = it)) })
        Text("Only if its conditions are met", style = MaterialTheme.typography.bodyMedium)
    }
    Hint("Runs that macro's actions, ignoring its trigger. Variables from this trigger are passed along.")
}
