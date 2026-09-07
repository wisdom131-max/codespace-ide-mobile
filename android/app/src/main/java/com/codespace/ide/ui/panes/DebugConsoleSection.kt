package com.codespace.ide.ui.panes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// P1-D1: Debug Console REPL — read-only transcript + expression input row.
// Lives in its own file per the 64KB bytecode rule (ExplorerPane.kt is near the limit).
// Colors match ExplorerPane.kt's file-private palette; duplicated locally since those
// constants are private to that file.
private val ConsoleText = Color(0xFFD4D4D4)
private val ConsoleMuted = Color(0xFF858585)

/**
 * Renders the console transcript lines and an input field. The host (RunDebugPanel)
 * owns the lines list; submitting an expression calls [onEvaluate], which is expected
 * to append both the echo line and the result line to the host's list.
 */
@Composable
internal fun DebugConsoleSection(
    consoleLines: List<String>,
    onEvaluate: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (consoleLines.isNotEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 110.dp)) {
                items(consoleLines) { line ->
                    Text(
                        line,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.startsWith(">")) ConsoleMuted else ConsoleText,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            placeholder = { Text("Evaluate expression\u2026", fontSize = 11.sp, color = ConsoleMuted) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ConsoleText),
            trailingIcon = {
                IconButton(
                    onClick = {
                        val expr = input.trim()
                        if (expr.isNotEmpty()) {
                            onEvaluate(expr)
                            input = ""
                        }
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Evaluate", tint = ConsoleMuted, modifier = Modifier.size(14.dp))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(
                onGo = {
                    val expr = input.trim()
                    if (expr.isNotEmpty()) {
                        onEvaluate(expr)
                        input = ""
                    }
                },
            ),
        )
    }
}
