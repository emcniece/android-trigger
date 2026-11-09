package com.emcniece.trigger.ui

import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.emcniece.trigger.Graph
import com.emcniece.trigger.engine.MacroEngine
import com.emcniece.trigger.engine.AndroidDeviceState
import com.emcniece.trigger.model.Action
import com.emcniece.trigger.model.Constraint
import com.emcniece.trigger.model.ActionType
import com.emcniece.trigger.model.AppJson
import com.emcniece.trigger.model.HTTP_METHODS
import com.emcniece.trigger.model.HttpHeader
import com.emcniece.trigger.model.Macro
import com.emcniece.trigger.model.MatchMode
import com.emcniece.trigger.model.NetworkTransport
import com.emcniece.trigger.model.TextMatch
import com.emcniece.trigger.model.Trigger
import com.emcniece.trigger.model.TriggerType
import com.emcniece.trigger.model.VariableFormat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val MacroSaver = Saver<Macro, String>(
    save = { AppJson.encodeToString(Macro.serializer(), it) },
    restore = { AppJson.decodeFromString(Macro.serializer(), it) },
)

/** The README's canonical example: forward incoming SMS to a webhook. */
private fun newMacro() = Macro(
    trigger = Trigger.SmsReceived(),
    actions = listOf(
        Action.HttpRequest(
            body = "{\"from\": \"{sms_sender:json}\", \"text\": \"{sms_body:json}\", \"at\": \"{timestamp}\"}",
        ),
    ),
)

@Composable
fun MacroEditScreen(macroId: String?, onDone: () -> Unit) {
    val context = LocalContext.current
    val existing = remember(macroId) { macroId?.let { Graph.macros.get(it) } }
    // Re-read device state each time the screen resumes (e.g. after plugging in a charger).
    val tick = rememberResumeTick()
    val deviceState = remember(tick) { AndroidDeviceState(context) }
    MacroEditContent(
        initial = existing ?: newMacro(),
        isNew = existing == null,
        onBack = onDone,
        onSave = {
            Graph.macros.upsert(it)
            onDone()
        },
        onDelete = {
            Graph.macros.delete(it.id)
            onDone()
        },
        onTest = { macro ->
            // Test runs ignore constraints, but say so if they'd currently block a real run.
            val blocking = macro.blockingConstraints(AndroidDeviceState(context))
            MacroEngine.runMacro(context.applicationContext, macro, macro.trigger.type.sampleVars(), allowRetry = false)
            val result = Graph.log.entries.value.firstOrNull()?.message ?: "Test run finished"
            if (blocking.isEmpty()) result else "$result (ran anyway; conditions not met now)"
        },
        constraintStatus = { it.isMet(deviceState) },
    )
}

/**
 * Stateless-from-the-outside macro editor. [onTest] runs the draft and returns a status message.
 * [requirements] renders the permissions card; previews replace it with static content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MacroEditContent(
    initial: Macro,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (Macro) -> Unit,
    onDelete: (Macro) -> Unit,
    onTest: suspend (Macro) -> String,
    requirements: @Composable (Macro) -> Unit = { RequirementsCard(it) },
    constraintStatus: (Constraint) -> Boolean? = { null },
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var draft by rememberSaveable(stateSaver = MacroSaver) { mutableStateOf(initial) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New macro" else "Edit macro") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Discard") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { snackbar.showSnackbar(onTest(draft)) }
                    }) { Icon(Icons.Default.PlayArrow, contentDescription = "Test run") }
                    if (!isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    IconButton(onClick = { onSave(draft.copy(name = draft.name.trim())) }) {
                        Icon(Icons.Default.Check, contentDescription = "Save")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("Name") },
                placeholder = { Text(draft.trigger.type.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Enabled", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = draft.enabled, onCheckedChange = { draft = draft.copy(enabled = it) })
            }

            requirements(draft)

            SectionHeader("When")
            ChoiceField(
                label = "Trigger",
                options = TriggerType.entries,
                selected = draft.trigger.type,
                optionLabel = { it.label },
                onSelect = { if (it != draft.trigger.type) draft = draft.copy(trigger = it.default()) },
            )
            TriggerEditor(draft.trigger) { draft = draft.copy(trigger = it) }

            SectionHeader("Only if")
            ConstraintsEditor(
                constraints = draft.constraints,
                match = draft.constraintMatch,
                onConstraintsChange = { draft = draft.copy(constraints = it) },
                onMatchChange = { draft = draft.copy(constraintMatch = it) },
                status = constraintStatus,
            )

            SectionHeader("Then")
            VariablesHint(draft.trigger.type)
            draft.actions.forEachIndexed { index, action ->
                ActionCard(
                    action = action,
                    triggerType = draft.trigger.type,
                    canMoveUp = index > 0,
                    canMoveDown = index < draft.actions.lastIndex,
                    onChange = { updated -> draft = draft.copy(actions = draft.actions.toMutableList().also { it[index] = updated }) },
                    onMove = { delta ->
                        val list = draft.actions.toMutableList()
                        list.add(index + delta, list.removeAt(index))
                        draft = draft.copy(actions = list)
                    },
                    onDelete = { draft = draft.copy(actions = draft.actions.filterIndexed { i, _ -> i != index }) },
                )
            }
            AddActionButton { draft = draft.copy(actions = draft.actions + it.default()) }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete macro?") },
            text = { Text("\"${draft.displayName}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(draft)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun RequirementsCard(macro: Macro) {
    val context = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val unmet = remember(macro.trigger.type, macro.actions.map { it.type }, tick, refresh) { Requirements.unmet(context, macro) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    RequirementsCardContent(unmet.map { it.label to it.optional }) { index ->
        when (val req = unmet[index]) {
            is Requirement.Runtime -> launcher.launch(req.permissions.toTypedArray())
            is Requirement.Special -> try {
                context.startActivity(req.intent(context))
            } catch (_: ActivityNotFoundException) {
                context.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }
    }
}

/** [items] are (label, optional) pairs; [onGrant] receives the tapped item's index. */
@Composable
fun RequirementsCardContent(items: List<Pair<String, Boolean>>, onGrant: (Int) -> Unit) {
    if (items.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Permissions", style = MaterialTheme.typography.titleSmall)
            items.forEachIndexed { index, (label, optional) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        label + if (optional) " (recommended)" else "",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onGrant(index) }) { Text("Grant") }
                }
            }
        }
    }
}

@Composable
private fun VariablesHint(type: TriggerType) {
    val vars = type.vars.joinToString(", ") { "{$it}" }
    Text(
        buildString {
            append("Tap { } in a field to insert a variable")
            if (vars.isNotEmpty()) append(" such as $vars")
            append(". Add :json or :url to escape a value.")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------- Trigger editors ----------

@Composable
private fun TriggerEditor(trigger: Trigger, onChange: (Trigger) -> Unit) {
    val context = LocalContext.current
    when (trigger) {
        is Trigger.SmsReceived -> {
            TextMatchEditor("Sender", trigger.sender) { onChange(trigger.copy(sender = it)) }
            TextMatchEditor("Message text", trigger.body) { onChange(trigger.copy(body = it)) }
        }
        is Trigger.NotificationPosted -> {
            OutlinedTextField(
                value = trigger.packageName,
                onValueChange = { onChange(trigger.copy(packageName = it)) },
                label = { Text("App package (blank = any app)") },
                placeholder = { Text("com.whatsapp") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextMatchEditor("Title or text", trigger.text) { onChange(trigger.copy(text = it)) }
        }
        is Trigger.PowerChanged -> BinaryChoice("Connected", "Disconnected", trigger.connected) {
            onChange(trigger.copy(connected = it))
        }
        is Trigger.BatteryLevel -> {
            BinaryChoice("Drops to", "Rises to", trigger.below) { onChange(trigger.copy(below = it)) }
            Text("${trigger.threshold}%", style = MaterialTheme.typography.titleLarge)
            Slider(
                value = trigger.threshold.toFloat(),
                onValueChange = { onChange(trigger.copy(threshold = it.roundToInt())) },
                valueRange = 1f..99f,
            )
        }
        is Trigger.NetworkChanged -> {
            ChoiceField("Network", NetworkTransport.entries, trigger.transport, { it.label }) {
                onChange(trigger.copy(transport = it))
            }
            BinaryChoice("Connected", "Lost", trigger.connected) { onChange(trigger.copy(connected = it)) }
        }
        Trigger.Boot -> Text("Runs once each time the device finishes booting.", style = MaterialTheme.typography.bodyMedium)
        is Trigger.DailyTime -> OutlinedButton(onClick = {
            TimePickerDialog(
                context,
                { _, h, m -> onChange(trigger.copy(hour = h, minute = m)) },
                trigger.hour,
                trigger.minute,
                DateFormat.is24HourFormat(context),
            ).show()
        }) { Text("Every day at %02d:%02d".format(trigger.hour, trigger.minute)) }
    }
}

@Composable
private fun TextMatchEditor(label: String, match: TextMatch, onChange: (TextMatch) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChoiceField(label, MatchMode.entries, match.mode, { it.label }) { onChange(match.copy(mode = it)) }
        if (match.isActive) {
            OutlinedTextField(
                value = match.pattern,
                onValueChange = { onChange(match.copy(pattern = it)) },
                label = { Text(if (match.mode == MatchMode.REGEX) "Regular expression" else "Text") },
                singleLine = true,
                isError = match.mode == MatchMode.REGEX && runCatching { Regex(match.pattern) }.isFailure,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = match.ignoreCase, onCheckedChange = { onChange(match.copy(ignoreCase = it)) })
                Text("Ignore case", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun BinaryChoice(first: String, second: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = value, onClick = { onChange(true) }, label = { Text(first) })
        FilterChip(selected = !value, onClick = { onChange(false) }, label = { Text(second) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

// ---------- Action editors ----------

@Composable
internal fun ActionCard(
    action: Action,
    triggerType: TriggerType,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (Action) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(action.type.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Remove action") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionFields(action, triggerType, onChange)
            }
        }
    }
}

@Composable
private fun ActionFields(action: Action, triggerType: TriggerType, onChange: (Action) -> Unit) {
    when (action) {
        is Action.HttpRequest -> {
            ChoiceField("Method", HTTP_METHODS, action.method, { it }) { onChange(action.copy(method = it)) }
            TemplateField(
                "URL", action.url, triggerType,
                onChange = { onChange(action.copy(url = it)) },
                keyboard = KeyboardType.Uri,
                placeholder = "https://example.com/webhook",
                defaultFormat = VariableFormat.URL,
            )
            HeadersEditor(action.headers, triggerType) { onChange(action.copy(headers = it)) }
            if (action.method != "GET") {
                val jsonBody = action.headers.any {
                    it.name.trim().equals("Content-Type", ignoreCase = true) && it.value.contains("json", ignoreCase = true)
                }
                TemplateField(
                    "Body", action.body, triggerType,
                    onChange = { onChange(action.copy(body = it)) },
                    singleLine = false,
                    monospace = true,
                    defaultFormat = if (jsonBody) VariableFormat.JSON else VariableFormat.PLAIN,
                )
            }
        }
        is Action.ShowNotification -> {
            TemplateField("Title", action.title, triggerType, onChange = { onChange(action.copy(title = it)) })
            TemplateField("Text", action.text, triggerType, onChange = { onChange(action.copy(text = it)) }, singleLine = false)
        }
        is Action.SendSms -> {
            TemplateField(
                "Phone number", action.phoneNumber, triggerType,
                onChange = { onChange(action.copy(phoneNumber = it)) },
                keyboard = KeyboardType.Phone,
            )
            TemplateField("Message", action.message, triggerType, onChange = { onChange(action.copy(message = it)) }, singleLine = false)
        }
        is Action.Vibrate -> {
            Text("${action.durationMs} ms", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = action.durationMs.toFloat(),
                onValueChange = { onChange(action.copy(durationMs = (it / 100).roundToInt() * 100L)) },
                valueRange = 100f..3000f,
            )
        }
    }
}

@Composable
private fun HeadersEditor(headers: List<HttpHeader>, triggerType: TriggerType, onChange: (List<HttpHeader>) -> Unit) {
    fun replace(i: Int, h: HttpHeader) = onChange(headers.toMutableList().also { it[i] = h })
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Headers", style = MaterialTheme.typography.labelLarge)
        headers.forEachIndexed { i, header ->
            // Name above value: side-by-side fields are too narrow for typical header values on a phone.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = header.name,
                        onValueChange = { replace(i, header.copy(name = it)) },
                        label = { Text("Name") },
                        placeholder = { Text("X-Webhook-Secret") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (header.name.trim().equals("Content-Type", ignoreCase = true)) {
                        ContentTypeField(header.value, Modifier.fillMaxWidth()) { replace(i, header.copy(value = it)) }
                    } else {
                        TemplateField(
                            "Value", header.value, triggerType,
                            onChange = { replace(i, header.copy(value = it)) },
                            monospace = true,
                        )
                    }
                }
                IconButton(onClick = { onChange(headers.filterIndexed { j, _ -> j != i }) }) {
                    Icon(Icons.Default.Close, contentDescription = "Remove header")
                }
            }
        }
        TextButton(onClick = { onChange(headers + HttpHeader()) }) {
            Icon(Icons.Default.Add, null)
            Text("Add header", Modifier.padding(start = 8.dp))
        }
    }
}

private val CONTENT_TYPES = listOf(
    "application/json",
    "application/x-www-form-urlencoded",
    "text/plain",
    "application/xml",
    "text/csv",
)

/** Dropdown of common content types; still editable for custom values (e.g. with a charset). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentTypeField(value: String, modifier: Modifier, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text("Value") },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CONTENT_TYPES.forEach { type ->
                DropdownMenuItem(
                    text = { Text(type, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onChange(type)
                        expanded = false
                    },
                )
            }
        }
    }
}


@Composable
private fun AddActionButton(onAdd: (ActionType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, null)
            Text("Add action", Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ActionType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = {
                    onAdd(type)
                    open = false
                })
            }
        }
    }
}
