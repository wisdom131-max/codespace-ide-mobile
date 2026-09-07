package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.debug.DebugBreakpoint
import com.codespace.ide.debug.DebugVariable

// P1-D2 / P1-D3: breakpoint + variable edit dialogs. Own file per the 64KB bytecode rule.

private val DialogMuted = Color(0xFF858585)
private val DialogText = Color(0xFFD4D4D4)

/**
 * P1-D2: Edit a breakpoint's condition / log message / hit condition.
 * All three fields optional — blank means "none" (removes that property).
 */
@Composable
internal fun EditBreakpointDialog(
    breakpoint: DebugBreakpoint,
    onSave: (condition: String?, logMessage: String?, hitCondition: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var condition by remember(breakpoint) { mutableStateOf(breakpoint.condition.orEmpty()) }
    var logMessage by remember(breakpoint) { mutableStateOf(breakpoint.logMessage.orEmpty()) }
    var hitCondition by remember(breakpoint) { mutableStateOf(breakpoint.hitCondition.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit breakpoint — line ${breakpoint.line + 1}") },
        text = {
            Column(Modifier.padding(vertical = 2.dp)) {
                OutlinedTextField(
                    value = condition,
                    onValueChange = { condition = it },
                    label = { Text("Condition") },
                    placeholder = { Text("e.g. x == 5", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = logMessage,
                    onValueChange = { logMessage = it },
                    label = { Text("Log message") },
                    placeholder = { Text("e.g. got here, x={x}", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = hitCondition,
                    onValueChange = { hitCondition = it },
                    label = { Text("Hit condition") },
                    placeholder = { Text("e.g. >=5 or 4", fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Blank = none. Log-only breakpoints do not stop execution.",
                    fontSize = 10.sp, color = DialogMuted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(condition.trim(), logMessage.trim(), hitCondition.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * P1-D3: Set a variable's value via DAP setVariable.
 */
@Composable
internal fun EditVariableDialog(
    variable: DebugVariable,
    onSave: (newValue: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(variable) { mutableStateOf(variable.value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set ${variable.name}") },
        text = {
            Column(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "Type: ${variable.type.ifBlank { "?" }}",
                    fontSize = 11.sp, color = DialogMuted, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = DialogText),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * P2-FUNCBP: Add a function breakpoint by function/method name.
 * Blank input is rejected inline; duplicate names are reported by UDM (false return).
 */
@Composable
internal fun AddFunctionBreakpointDialog(
    existingNames: List<String>,
    onAdd: (name: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Function Breakpoint", color = DialogText, fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
        text = {
            Column {
                Text(
                    "Break by function or method name (e.g. main, MyClass.handleRequest).",
                    color = DialogMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = DialogText),
                    placeholder = { Text("functionName", color = DialogMuted, fontSize = 13.sp) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Text(error!!, color = Color(0xFFF48771), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                when {
                    trimmed.isEmpty() -> error = "Enter a function name"
                    trimmed in existingNames -> error = "Already set"
                    onAdd(trimmed) -> onDismiss()
                    else -> error = "Could not add"
                }
            }) { Text("Add", color = Color(0xFF007ACC), fontSize = 13.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = DialogMuted, fontSize = 13.sp) } }
    )
}
