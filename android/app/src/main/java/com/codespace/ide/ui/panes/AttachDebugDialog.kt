package com.codespace.ide.ui.panes

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.debug.UniversalDebugManager
import com.codespace.ide.domain.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P26-4a: AttachDebugDialog — port/PID picker for attaching to a running Node.js process.
 *
 * Shows when:
 *   - The active file is a .js / .mjs / .cjs / .ts file
 *   - The user taps the "Attach…" button in DebugConsolePanel's header
 *
 * Behaviour:
 *   - Default port: 9229 (Node.js default --inspect port)
 *   - PID field is optional; when non-empty it overrides port-based attach
 *   - "Attach" button calls UDM.attachDebug() on an IO thread; shows progress indicator
 *   - Error message shown inline if attach returns null (no adapter found)
 *   - Dialog is rotation-safe via key(orientation) at the call site in DebugConsolePanel
 */
@Composable
fun AttachDebugDialog(
    context: Context,
    activeFilePath: String,
    onDismiss: () -> Unit,
    onAttached: (sessionId: String) -> Unit,
    onAttachFailed: (reason: String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var portText by remember { mutableStateOf("9229") }
    var pidText by remember { mutableStateOf("") }
    var isAttaching by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val lang = Language.fromPath(activeFilePath)
    val fileName = activeFilePath.substringAfterLast("/")

    Dialog(onDismissRequest = { if (!isAttaching) onDismiss() }) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF252526),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {

                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Cable, null,
                            tint = Color(0xFF4EC9B0),
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            "Attach to Process",
                            color = Color(0xFFD4D4D4),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (!isAttaching) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Default.Close, "Close", tint = Color(0xFF808080), modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    "Node.js — $fileName",
                    color = Color(0xFF808080),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF3C3C3C))
                Spacer(Modifier.height(16.dp))

                // Port field
                Text("Port (--inspect)", color = Color(0xFF808080), fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = portText,
                    onValueChange = { v ->
                        portText = v.filter { it.isDigit() }.take(5)
                        errorMsg = null
                    },
                    singleLine = true,
                    enabled = !isAttaching,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFD4D4D4),
                    ),
                    placeholder = {
                        Text("9229", color = Color(0xFF555555), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF569CD6),
                        unfocusedBorderColor = Color(0xFF3C3C3C),
                        cursorColor = Color(0xFF569CD6),
                    ),
                )

                Spacer(Modifier.height(12.dp))

                // PID field (optional)
                Text("Process ID (optional — overrides port)", color = Color(0xFF808080), fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = pidText,
                    onValueChange = { v ->
                        pidText = v.filter { it.isDigit() }.take(7)
                        errorMsg = null
                    },
                    singleLine = true,
                    enabled = !isAttaching,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFD4D4D4),
                    ),
                    placeholder = {
                        Text("leave blank to use port", color = Color(0xFF555555), fontSize = 12.sp)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF569CD6),
                        unfocusedBorderColor = Color(0xFF3C3C3C),
                        cursorColor = Color(0xFF569CD6),
                    ),
                )

                // Inline hint
                Spacer(Modifier.height(6.dp))
                Text(
                    "Start your Node.js process with --inspect or --inspect-brk first:\n  node --inspect-brk app.js",
                    color = Color(0xFF555555),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp,
                )

                // Error message
                if (errorMsg != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        errorMsg!!,
                        color = Color(0xFFF48771),
                        fontSize = 11.sp,
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = Color(0xFF3C3C3C))
                Spacer(Modifier.height(12.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isAttaching) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = Color(0xFF808080))
                        }
                        Spacer(Modifier.width(8.dp))
                    }

                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 9229
                            val pid = pidText.toIntOrNull() ?: -1
                            isAttaching = true
                            errorMsg = null
                            scope.launch(Dispatchers.IO) {
                                val sessionId = UniversalDebugManager.attachDebug(
                                    context = context,
                                    language = lang,
                                    filePath = activeFilePath,
                                    port = port,
                                    pid = pid,
                                )
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    isAttaching = false
                                    if (sessionId != null) {
                                        onAttached(sessionId)
                                        onDismiss()
                                    } else {
                                        val reason = if (pid > 0)
                                            "Could not attach to PID $pid. Is the process running?"
                                        else
                                            "Could not attach to port $port. Is node running with --inspect?"
                                        errorMsg = reason
                                        onAttachFailed(reason)
                                    }
                                }
                            }
                        },
                        enabled = !isAttaching && portText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0E639C),
                            disabledContainerColor = Color(0xFF3C3C3C),
                        ),
                    ) {
                        if (isAttaching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFD4D4D4),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Attaching...", color = Color(0xFFD4D4D4), fontSize = 13.sp)
                        } else {
                            Icon(Icons.Default.Cable, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Attach", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
