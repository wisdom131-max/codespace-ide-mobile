package com.codespace.ide.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspCodeAction
import com.codespace.ide.lsp.LspManager

@Composable
internal fun LightbulbMenuOverlay(
    showLightbulbMenuState: MutableState<Boolean>,
    lightbulbActions: List<LspCodeAction>,
    onAiFixRequest: ((String) -> Unit)?,
    value: TextFieldValue,
    positionMapper: PositionMapper,
    filePath: String?,
    language: Language,
    lintErrors: List<LintError>,
    extraCursorsState: MutableState<List<Int>>,
    programmaticTextChange: (String, TextRange, String) -> Unit,
) {
    var showLightbulbMenu by showLightbulbMenuState
    DropdownMenu(
        expanded = showLightbulbMenu,
        onDismissRequest = { showLightbulbMenu = false },
    ) {
        if (lightbulbActions.isNotEmpty()) {
            val categorized = com.codespace.ide.lsp.categorizeCodeActions(lightbulbActions)
            categorized.forEach { (groupLabel, actions) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            groupLabel.uppercase(),
                            color = Color(0xFF858585),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = {},
                    enabled = false,
                )
                actions.forEach { fix ->
                    val icon = com.codespace.ide.lsp.CodeActionKind.icon(fix.kind)
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(icon, fontSize = 12.sp)
                                Text(
                                    fix.title,
                                    color = if (fix.disabled != null) Color(0xFF666666)
                                           else if (fix.isPreferred) Color(0xFFFFD700)
                                           else Color(0xFFD4D4D4),
                                    fontSize = 13.sp,
                                    fontWeight = if (fix.isPreferred) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        },
                        enabled = fix.disabled == null,
                        onClick = {
                            if (fix.kind != null && fix.kind.startsWith("ai.") && onAiFixRequest != null) {
                                val hasSelection2 = value.selection.start != value.selection.end
                                val selText2 = if (hasSelection2) {
                                    value.text.substring(
                                        value.selection.start.coerceIn(0, value.text.length),
                                        value.selection.end.coerceIn(0, value.text.length)
                                    )
                                } else {
                                    val lineStart2 = positionMapper.lineStart(positionMapper.offsetToLine(value.selection.start))
                                    val lineEnd2 = value.text.indexOf('\n', value.selection.start)
                                    value.text.substring(lineStart2, if (lineEnd2 < 0) value.text.length else lineEnd2)
                                }
                                val fileName2 = filePath?.substringAfterLast('/') ?: "untitled"
                                val langName2 = language.displayName
                                val imports2 = value.text.lines().take(30).filter {
                                    it.trim().startsWith("import ") || it.trim().startsWith("from ") || it.trim().startsWith("package ") || it.trim().startsWith("#include")
                                }.joinToString("\n")
                                val contextHeader2 = "File: $fileName2 ($langName2)\n" +
                                    (if (imports2.isNotEmpty()) "Imports:\n$imports2\n" else "") +
                                    "Selection (${if (hasSelection2) "selected text" else "current line"}):\n"
                                val diagAtCursor2 = if (fix.kind == com.codespace.ide.lsp.CodeActionKind.AIExplainError) {
                                    val cursorOff2 = value.selection.start.coerceIn(0, value.text.length)
                                    val matchingErr2 = lintErrors.firstOrNull { err ->
                                        err.start <= cursorOff2 && err.end >= cursorOff2
                                    }
                                    if (matchingErr2 != null) "Error: ${matchingErr2.message}${if (matchingErr2.code != null) " [${matchingErr2.code}]" else ""}\n" else ""
                                } else ""
                                val prompt = when (fix.kind) {
                                    com.codespace.ide.lsp.CodeActionKind.AIExplain -> contextHeader2 + "Explain this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIGenerateDoc -> contextHeader2 + "Generate documentation for this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIGenerateTests -> contextHeader2 + "Generate unit tests for this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIOptimize -> contextHeader2 + "Optimize this code for better performance:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIRewrite -> contextHeader2 + "Rewrite this code for better clarity:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AISimplify -> contextHeader2 + "Simplify this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIRefactor -> contextHeader2 + "Refactor this code for better structure and readability:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIAddComments -> contextHeader2 + "Add inline comments to this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIExplainError -> contextHeader2 + diagAtCursor2 + "Explain the error in this code:\n" + selText2
                                    com.codespace.ide.lsp.CodeActionKind.AIImprovePerf -> contextHeader2 + "Suggest performance improvements for:\n" + selText2
                                    else -> contextHeader2 + fix.title + ":\n" + selText2
                                }
                                onAiFixRequest!!.invoke(prompt)
                            } else if (fix.edit != null) {
                                try {
                                    val newText = com.codespace.ide.lsp.applyWorkspaceEdit(
                                        fix.edit, value.text, null
                                    )
                                    if (newText != null && newText != value.text) {
                                        extraCursorsState.value = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursorsState.value)
                                        programmaticTextChange(newText, TextRange(value.selection.start), "ai_apply")
                                    }
                                } catch (_: Exception) {}
                            } else if (fix.command != null) {
                                try {
                                    val cmdJson = org.json.JSONObject(fix.command)
                                    val cmdName = cmdJson.optString("command", "")
                                    val cmdArgs = cmdJson.optJSONArray("arguments")
                                    if (cmdName.isNotEmpty()) {
                                        LspManager.executeCommand(language, cmdName, cmdArgs)
                                    }
                                } catch (_: Exception) {}
                            }
                            showLightbulbMenu = false
                        }
                    )
                }
            }
        } else {
            DropdownMenuItem(
                text = { Text("No actions available", color = Color(0xFF666666), fontSize = 13.sp) },
                onClick = { showLightbulbMenu = false },
            )
        }
    }
}
