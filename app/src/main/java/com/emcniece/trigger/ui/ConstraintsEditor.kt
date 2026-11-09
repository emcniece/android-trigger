package com.emcniece.trigger.ui

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.emcniece.trigger.engine.AndroidDeviceState
import com.emcniece.trigger.model.Constraint
import com.emcniece.trigger.model.ConstraintMatch
import com.emcniece.trigger.model.ConstraintType
import com.emcniece.trigger.model.MatchMode
import com.emcniece.trigger.model.NetworkRequirement
import com.emcniece.trigger.model.TextMatch
import com.emcniece.trigger.model.UNKNOWN_SSID
import kotlin.math.roundToInt

/**
 * The editor's "Only if" section. [status] reports whether a constraint holds right now
 * (null when unknown, e.g. in previews).
 */
@Composable
internal fun ConstraintsEditor(
    constraints: List<Constraint>,
    match: ConstraintMatch,
    onConstraintsChange: (List<Constraint>) -> Unit,
    onMatchChange: (ConstraintMatch) -> Unit,
    status: (Constraint) -> Boolean?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (constraints.isEmpty()) {
                "Optional. Add conditions like weekdays only or while charging; the macro is skipped when they aren't met."
            } else {
                "The macro only runs if these are true when the trigger fires."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (constraints.size >= 2) {
            BinaryChoice(ConstraintMatch.ALL.label, ConstraintMatch.ANY.label, match == ConstraintMatch.ALL) {
                onMatchChange(if (it) ConstraintMatch.ALL else ConstraintMatch.ANY)
            }
        }
        constraints.forEachIndexed { index, constraint ->
            ConstraintCard(
                constraint = constraint,
                metNow = status(constraint),
                onChange = { updated -> onConstraintsChange(constraints.toMutableList().also { it[index] = updated }) },
                onDelete = { onConstraintsChange(constraints.filterIndexed { i, _ -> i != index }) },
            )
        }
        AddConstraintButton { onConstraintsChange(constraints + it.default()) }
    }
}

@Composable
private fun ConstraintCard(constraint: Constraint, metNow: Boolean?, onChange: (Constraint) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(constraint.type.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                if (metNow != null) {
                    Text(
                        if (metNow) "✓ Met now" else "✗ Not met now",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (metNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Close, "Remove condition") }
            }
            Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConstraintFields(constraint, onChange)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConstraintFields(constraint: Constraint, onChange: (Constraint) -> Unit) {
    val context = LocalContext.current
    when (constraint) {
        is Constraint.TimeWindow -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    pickTime(context, constraint.startHour, constraint.startMinute) { h, m ->
                        onChange(constraint.copy(startHour = h, startMinute = m))
                    }
                }) { Text("From %02d:%02d".format(constraint.startHour, constraint.startMinute)) }
                OutlinedButton(onClick = {
                    pickTime(context, constraint.endHour, constraint.endMinute) { h, m ->
                        onChange(constraint.copy(endHour = h, endMinute = m))
                    }
                }) { Text("Until %02d:%02d".format(constraint.endHour, constraint.endMinute)) }
            }
            Text(
                "Overnight windows like 22:00 until 06:00 work too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is Constraint.DaysOfWeek -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Constraint.DaysOfWeek.DAY_NAMES.forEachIndexed { i, name ->
                val day = i + 1
                FilterChip(
                    selected = day in constraint.days,
                    onClick = {
                        onChange(constraint.copy(days = if (day in constraint.days) constraint.days - day else constraint.days + day))
                    },
                    label = { Text(name) },
                )
            }
        }
        is Constraint.Charging -> BinaryChoice("Charging", "Not charging", constraint.charging) {
            onChange(constraint.copy(charging = it))
        }
        is Constraint.Battery -> {
            BinaryChoice("At least", "At most", constraint.atLeast) { onChange(constraint.copy(atLeast = it)) }
            Text("${constraint.threshold}%", style = MaterialTheme.typography.titleLarge)
            Slider(
                value = constraint.threshold.toFloat(),
                onValueChange = { onChange(constraint.copy(threshold = it.roundToInt())) },
                valueRange = 1f..100f,
            )
        }
        is Constraint.NetworkIs -> ChoiceField("Network", NetworkRequirement.entries, constraint.requirement, { it.label }) {
            onChange(constraint.copy(requirement = it))
        }
        is Constraint.Screen -> BinaryChoice("Screen on", "Screen off", constraint.on) { onChange(constraint.copy(on = it)) }
        is Constraint.WifiNetwork -> WifiFields(constraint, onChange)
    }
}

@Composable
private fun WifiFields(constraint: Constraint.WifiNetwork, onChange: (Constraint) -> Unit) {
    val context = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    BinaryChoice("Connected to", "Not connected to", constraint.connected) { onChange(constraint.copy(connected = it)) }
    TextMatchEditor("Network name (SSID)", constraint.ssid) { onChange(constraint.copy(ssid = it)) }
    TextButton(onClick = {
        note = when (val ssid = AndroidDeviceState(context).wifiSsid) {
            null -> "Not connected to Wi-Fi right now."
            UNKNOWN_SSID -> "Android won't share the network name yet: grant location access above and turn location on."
            else -> {
                onChange(constraint.copy(ssid = TextMatch(MatchMode.EQUALS, ssid, ignoreCase = false)))
                null
            }
        }
    }) { Text("Use current network") }
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}

private fun pickTime(context: Context, hour: Int, minute: Int, onSet: (Int, Int) -> Unit) {
    TimePickerDialog(context, { _, h, m -> onSet(h, m) }, hour, minute, DateFormat.is24HourFormat(context)).show()
}

@Composable
private fun AddConstraintButton(onAdd: (ConstraintType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Icon(Icons.Default.Add, null)
            Text("Add condition", Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ConstraintType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = {
                    onAdd(type)
                    open = false
                })
            }
        }
    }
}
