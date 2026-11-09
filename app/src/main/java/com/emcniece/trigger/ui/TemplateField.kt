package com.emcniece.trigger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.emcniece.trigger.model.TriggerType
import com.emcniece.trigger.model.VariableFormat
import com.emcniece.trigger.model.VariableInfo
import com.emcniece.trigger.model.Variables

/**
 * Text field for values that support `{variables}`: flags placeholders that won't be filled in,
 * and has a `{ }` button that opens a picker to insert a variable at the cursor.
 */
@Composable
fun TemplateField(
    label: String,
    value: String,
    triggerType: TriggerType,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    singleLine: Boolean = true,
    monospace: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    defaultFormat: VariableFormat = VariableFormat.PLAIN,
) {
    // Keep cursor/selection locally so insertion lands where the user was typing.
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    val shown = if (field.text == value) field else TextFieldValue(value, TextRange(value.length))
    var picking by remember { mutableStateOf(false) }
    val problems = remember(value, triggerType) { Variables.problems(value, triggerType) }

    OutlinedTextField(
        value = shown,
        onValueChange = {
            field = it
            if (it.text != value) onChange(it.text)
        },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 2,
        textStyle = if (monospace) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyLarge
        },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = {
            IconButton(onClick = { picking = true }, modifier = Modifier.semantics { contentDescription = "Insert variable" }) {
                Text("{ }", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        isError = problems.isNotEmpty(),
        supportingText = if (problems.isEmpty()) null else { { Text(problems.joinToString("\n")) } },
        modifier = modifier,
    )

    if (picking) {
        VariablePickerDialog(
            triggerType = triggerType,
            defaultFormat = defaultFormat,
            onDismiss = { picking = false },
            onPick = { token ->
                picking = false
                val sel = shown.selection
                val text = shown.text.replaceRange(sel.min, sel.max, token)
                field = TextFieldValue(text, TextRange(sel.min + token.length))
                onChange(text)
            },
        )
    }
}

@Composable
fun VariablePickerDialog(
    triggerType: TriggerType,
    defaultFormat: VariableFormat,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert variable") },
        text = { VariablePickerContent(triggerType, defaultFormat, onPick) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The picker body, separate from the dialog so it can be rendered in screenshots. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VariablePickerContent(triggerType: TriggerType, defaultFormat: VariableFormat, onPick: (String) -> Unit) {
    var format by remember { mutableStateOf(defaultFormat) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VariableFormat.entries.forEach {
                FilterChip(selected = format == it, onClick = { format = it }, label = { Text(it.label) })
            }
        }
        val fromTrigger = Variables.forTrigger(triggerType)
        if (fromTrigger.isNotEmpty()) {
            GroupLabel("From “${triggerType.label}”")
            fromTrigger.forEach { VariableRow(it, format, onPick) }
        }
        GroupLabel("Always available")
        Variables.common.forEach { VariableRow(it, format, onPick) }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun VariableRow(variable: VariableInfo, format: VariableFormat, onPick: (String) -> Unit) {
    val token = "{${variable.name}${format.suffix}}"
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(token) }
            .padding(vertical = 8.dp),
    ) {
        Text(token, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
        if (variable.description.isNotEmpty()) {
            Text(variable.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
