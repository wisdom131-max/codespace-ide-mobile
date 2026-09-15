package com.codespace.ide.ui.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.codespace.ide.debug.UniversalDebugManager

/**
 * CW3 — breakpoint condition editor (VS Code "Edit Breakpoint" analog).
 *
 * Long-pressing a gutter breakpoint opens this: expression condition (pause only
 * when it evaluates truthy — the DAP server evaluates it), optional log message
 * (prints without pausing when supported), plus remove. Rendered ring-vs-dot in
 * the gutter comes from conditionalBreakpointLines in CodeEditor.
 *
 * UI RULE: rounded corners (12dp dialog, 8dp fields) + padding (12dp horiz,
 * 10dp vert) — same as all other IDE dialogs.
 */
@androidx.compose.runtime.Composable
internal fun BreakpointConditionDialog(
    filePath: String,
    line0: Int?,
    onDismiss: () -> Unit,
) {
    if (line0 == null) return  // dialog closed
    val udm = UniversalDebugManager
    val existing = udm.getBreakpoints(filePath).firstOrNull { it.line == line0 }
    if (existing == null) {
        // Toggled off while the dialog was open — nothing to edit
        onDismiss()
        return
    }
    var condition by remember(existing) { mutableStateOf(existing.condition ?: "") }
    var logMessage by remember(existing) { mutableStateOf(existing.logMessage ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        containerColor = Color(0xFF252526),
        titleContentColor = Color(0xFFD4D4D4),
        textContentColor = Color(0xFFD4D4D4),
        title = {
            Text(
                "Breakpoint — ${filePath.substringAfterLast("/")} : ${line0 + 1}",
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "Condition (pause only when true)",
                    fontSize = 11.sp, color = Color(0xFF858585),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = condition,
                    onValueChange = { condition = it },
                    placeholder = { Text("e.g. i > 10", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD4D4D4)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Log message (print without pausing)",
                    fontSize = 11.sp, color = Color(0xFF858585),
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = logMessage,
                    onValueChange = { logMessage = it },
                    placeholder = { Text("e.g. loop i=\${i}", fontSize = 12.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFD4D4D4)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                udm.setBreakpointCondition(filePath, line0, condition, logMessage)
                onDismiss()
            }) { Text("Apply", fontSize = 12.sp) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    udm.removeBreakpoint(filePath, line0)
                    onDismiss()
                }) { Text("Remove", fontSize = 12.sp, color = Color(0xFFE51400)) }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onDismiss) { Text("Cancel", fontSize = 12.sp) }
            }
        },
    )
}
