package com.emcniece.trigger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.emcniece.trigger.Graph
import com.emcniece.trigger.data.LogEntry
import com.emcniece.trigger.model.Macro
import java.text.DateFormat
import java.util.Date

@Composable
fun MacroListScreen(onAdd: () -> Unit, onEdit: (String) -> Unit, onLog: () -> Unit, onAbout: () -> Unit) {
    val macros by Graph.macros.macros.collectAsStateWithLifecycle()
    val tick = rememberResumeTick()
    val context = LocalContext.current
    val missing = remember(macros, tick) {
        macros.associate { m -> m.id to Requirements.unmet(context, m).filterNot { it.optional }.map { it.label } }
    }
    MacroListContent(
        macros = macros,
        missingPermissions = missing,
        onToggle = { id, enabled -> Graph.macros.setEnabled(id, enabled) },
        onAdd = onAdd,
        onEdit = onEdit,
        onLog = onLog,
        onAbout = onAbout,
    )
}

/** Stateless macro list; [missingPermissions] maps macro id to unmet required permission labels. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroListContent(
    macros: List<Macro>,
    missingPermissions: Map<String, List<String>>,
    onToggle: (String, Boolean) -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onLog: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trigger") },
                actions = {
                    IconButton(onClick = onLog) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Activity log") }
                    IconButton(onClick = onAbout) { Icon(Icons.Default.Info, contentDescription = "About") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New macro") },
            )
        },
    ) { padding ->
        if (macros.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No macros yet.\n\nA macro runs actions — like an HTTP request — when something happens on your " +
                        "phone, such as an SMS arriving.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(macros, key = { it.id }) { macro ->
                    MacroCard(
                        macro,
                        missingPermissions[macro.id].orEmpty(),
                        onToggle = { onToggle(macro.id, it) },
                        onClick = { onEdit(macro.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MacroCard(macro: Macro, missing: List<String>, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(macro.displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "When: ${macro.trigger.describe()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Then: " + macro.actions.joinToString(", ") { it.describe() }.ifEmpty { "nothing" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                if (macro.enabled && missing.isNotEmpty()) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Needs: ${missing.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Switch(checked = macro.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
fun LogScreen(onBack: () -> Unit) {
    val entries by Graph.log.entries.collectAsStateWithLifecycle()
    LogContent(entries, onBack = onBack, onClear = { Graph.log.clear() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogContent(entries: List<LogEntry>, onBack: () -> Unit, onClear: () -> Unit) {
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Activity log") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = onClear) { Icon(Icons.Default.Delete, contentDescription = "Clear log") }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Nothing has run yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                items(entries) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row {
                            Text(e.macroName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                fmt.format(Date(e.time)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            e.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (e.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
