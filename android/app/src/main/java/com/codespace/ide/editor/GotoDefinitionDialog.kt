package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.ui.LocalEditorColors

@Composable
fun GotoDefinitionDialog(
    results: List<DefResult>,
    crossFileResults: List<CrossFileDefResult>?,
    onDismiss: () -> Unit,
    onScrollToLine: (Int) -> Unit,
    onOpenFileAtLine: (String, Int) -> Unit,
) {
    val colors = LocalEditorColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.background,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (results.isEmpty() && (crossFileResults == null || crossFileResults.isEmpty())) "Not found" else "Go to Definition",
                    color = Color(0xFFD4D4D4),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
                if (!(results.isEmpty() && (crossFileResults == null || crossFileResults.isEmpty()))) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            .background(Color(0xFFCC7832))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text("Fallback", color = colors.background, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        text = {
            if (results.isEmpty() && (crossFileResults == null || crossFileResults.isEmpty())) {
                Text("No declaration found in current file or project.", color = Color(0xFF888888), fontSize = 12.sp)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (results.isNotEmpty()) {
                        Text("In this file", color = Color(0xFF888888), fontSize = 10.sp)
                    }
                    results.forEach { def ->
                        TextButton(
                            onClick = { onScrollToLine(def.line); onDismiss() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text("Line ${def.line + 1}", color = Color(0xFF007ACC), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                Text(def.lineText.take(60), color = Color(0xFFD4D4D4), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                    if (crossFileResults != null && crossFileResults.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("In project", color = Color(0xFF888888), fontSize = 10.sp)
                        crossFileResults.forEach { cf ->
                            TextButton(
                                onClick = { onOpenFileAtLine(cf.filePath, cf.line); onDismiss() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(cf.kind, color = Color(0xFF569CD6), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
                                        Text(cf.name, color = Color(0xFFD4D4D4), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                    }
                                    Text("${cf.fileName}:${cf.line}", color = Color(0xFF888888), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF888888), fontSize = 12.sp) } },
    )
}
