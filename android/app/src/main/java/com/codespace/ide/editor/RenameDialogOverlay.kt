package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.Language
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.lsp.applyWorkspaceEditToFilesystem
import com.codespace.ide.ui.EditorColors
import org.json.JSONObject

// P-BYTECODE-EXTRACT: Rename dialog + preview extracted from CodeEditor.kt to avoid Method too large
@Composable
fun RenameDialogOverlay(
    renameDialogWordState: MutableState<String?>,
    renameNewNameState: MutableState<String>,
    renameCountState: MutableState<Int>,
    renameProjectWideState: MutableState<Boolean>,
    renameCrossFileCountState: MutableState<Int>,
    renameInProgressState: MutableState<Boolean>,
    renameUsedLspState: MutableState<Boolean>,
    renamePreviewEditState: MutableState<JSONObject?>,
    renamePreviewFilesState: MutableState<List<Pair<String, Int>>>,
    extraCursorsState: MutableState<List<Int>>,
    value: TextFieldValue,
    colors: EditorColors,
    context: android.content.Context,
    language: Language,
    filePath: String,
    projectRoot: String?,
    positionMapper: PositionMapper,
    programmaticTextChange: (String, TextRange, String) -> Unit,
    onRenameSymbol: ((String, String) -> Unit)?,
    coroutineScope: CoroutineScope,
) {
    var renameDialogWord by renameDialogWordState
    var renameNewName by renameNewNameState
    val renameCount by renameCountState
    var renameProjectWide by renameProjectWideState
    var renameCrossFileCount by renameCrossFileCountState
    var renameInProgress by renameInProgressState
    var renameUsedLsp by renameUsedLspState
    var renamePreviewEdit by renamePreviewEditState
    var renamePreviewFiles by renamePreviewFilesState
    var extraCursors by extraCursorsState

    if (renameDialogWord != null) {
        val wordToRename = renameDialogWord!!
        AlertDialog(
            onDismissRequest = { renameDialogWord = null },
            containerColor = colors.background,
            title = {
                Text(
                    "Rename Symbol",
                    color = Color(0xFFD4D4D4),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "$renameCount occurrence${if (renameCount != 1) "s" else ""} of '$wordToRename'" +
                        (if (renameProjectWide && renameCrossFileCount > 0) " + $renameCrossFileCount in other files" else "") +
                        (if (renameUsedLsp) " [LSP]" else " [regex]"),
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                .background(if (renameUsedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832))
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                if (renameUsedLsp) "LSP" else "Fallback",
                                color = colors.background,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            if (renameUsedLsp) "Renamed via LSP (workspace-aware)" else "Regex replace in current file only",
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                        )
                    }
                    OutlinedTextField(
                        value = renameNewName,
                        onValueChange = { renameNewName = it },
                        singleLine = true,
                        label = { Text("New name", color = Color(0xFF888888), fontSize = 11.sp) },
                        textStyle = TextStyle(
                            color = Color(0xFFD4D4D4),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF007ACC),
                            unfocusedBorderColor = colors.gutter.copy(alpha = 0.3f),
                            cursorColor = Color(0xFF007ACC),
                        ),
                    )
                    if (projectRoot != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { renameProjectWide = !renameProjectWide },
                        ) {
                            Checkbox(
                                checked = renameProjectWide,
                                onCheckedChange = { renameProjectWide = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF007ACC)),
                            )
                            Text(
                                "Rename in all project files",
                                color = Color(0xFFD4D4D4),
                                fontSize = 12.sp,
                            )
                        }
                    }
                    if (renameInProgress) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF007ACC),
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (LspManager.isServerRunning(language) && filePath.startsWith("/")) {
                        TextButton(onClick = {
                            val newName = renameNewName.trim()
                            if (newName.isNotEmpty() && newName != wordToRename) {
                                val ctx = context
                                val uri = LspManager.fileUriFromHostPath(ctx, filePath)
                                if (uri != null) {
                                    val cOff = value.selection.end
                                    val cPos = positionMapper.offsetToPosition(cOff)
                                    val cLine = cPos.line
                                    val cCol = cPos.column
                                    try {
                                        val wsEdit = LspManager.rename(language, uri, cLine, cCol, newName)
                                        if (wsEdit != null) {
                                            val files = mutableListOf<Pair<String, Int>>()
                                            val docChanges = wsEdit.optJSONArray("documentChanges")
                                            val changes = wsEdit.optJSONObject("changes")
                                            if (docChanges != null) {
                                                for (j in 0 until docChanges.length()) {
                                                    val dc = docChanges.optJSONObject(j) ?: continue
                                                    val editUri = dc.optString("uri", "")
                                                    val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                    val decoded = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                    val editCount = dc.optJSONArray("edits")?.length() ?: 0
                                                    files.add(decoded.substringAfterLast("/") to editCount)
                                                }
                                            } else if (changes != null) {
                                                val keys = changes.keys()
                                                while (keys.hasNext()) {
                                                    val editUri = keys.next()
                                                    val editPath = if (editUri.startsWith("file://")) editUri.removePrefix("file://") else editUri
                                                    val decoded = try { java.net.URLDecoder.decode(editPath, "UTF-8") } catch (_: Exception) { editPath }
                                                    val editCount = changes.optJSONArray(editUri)?.length() ?: 0
                                                    files.add(decoded.substringAfterLast("/") to editCount)
                                                }
                                            }
                                            renamePreviewEdit = wsEdit
                                            renamePreviewFiles = files
                                        }
                                    } catch (_: Exception) {}
                                }
                            }
                        }) {
                            Text("Preview", color = Color(0xFF4EC9B0), fontSize = 12.sp)
                        }
                    }
                    Button(
                    onClick = {
                        val newName = renameNewName.trim()
                        if (newName.isNotEmpty() && newName != wordToRename) {
                            var lspSucceeded = false
                            if (LspManager.isServerRunning(language) && filePath.startsWith("/")) {
                                val ctx = context
                                val uri = LspManager.fileUriFromHostPath(ctx, filePath)
                                if (uri != null) {
                                    val cOff = value.selection.end
                                    val cPos = positionMapper.offsetToPosition(cOff)
                                    val cLine = cPos.line
                                    val cCol = cPos.column
                                    val prep = try { LspManager.prepareRename(language, uri, cLine, cCol) } catch (_: Exception) { null }
                                    if (prep != null) {
                                        val wsEdit = try { LspManager.rename(language, uri, cLine, cCol, newName) } catch (_: Exception) { null }
                                        if (wsEdit != null) {
                                            val (newText, appliedAny) = applyWorkspaceEditToFilesystem(wsEdit, value.text, filePath)
                                            if (appliedAny) {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                programmaticTextChange(newText, value.selection, "snippet_apply")
                                                lspSucceeded = true
                                                renameUsedLsp = true
                                                onRenameSymbol?.invoke(wordToRename, newName)
                                            }
                                        }
                                    }
                                }
                            }
                            if (!lspSucceeded) {
                                renameUsedLsp = false
                                val pattern = Regex("""\b${Regex.escape(wordToRename)}\b""")
                                val newText = pattern.replace(value.text, newName)
                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                programmaticTextChange(newText, value.selection, "rename_refactor")
                                if (renameProjectWide && projectRoot != null) {
                                    renameInProgress = true
                                    coroutineScope.launch(Dispatchers.IO) {
                                        val root = File(projectRoot)
                                        var totalCrossFile = 0
                                        root.walkTopDown()
                                            .filter { it.isFile && !it.path.contains("/.git/") && !it.path.contains("/build/") && !it.path.contains("/node_modules/") && !it.path.contains("/.gradle/") }
                                            .forEach { file ->
                                                try {
                                                    val text = file.readText()
                                                    if (pattern.containsMatchIn(text)) {
                                                        val updated = pattern.replace(text, newName)
                                                        file.writeText(updated)
                                                        totalCrossFile += pattern.findAll(text).count()
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        renameCrossFileCount = totalCrossFile
                                        renameInProgress = false
                                    }
                                }
                            }
                            renameDialogWord = null
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))) {
                        Text("Rename", color = Color.White, fontSize = 12.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { renameDialogWord = null }) {
                    Text("Cancel", color = Color(0xFF888888), fontSize = 12.sp)
                }
            },
        )
    }

    // P39-FULL: Rename Preview dialog
    if (renamePreviewEdit != null) {
        AlertDialog(
            onDismissRequest = { renamePreviewEdit = null; renamePreviewFiles = emptyList() },
            containerColor = colors.background,
            title = { Text("Rename Preview", color = Color(0xFFD4D4D4), fontSize = 14.sp, fontFamily = FontFamily.Monospace) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${renamePreviewFiles.size} file${if (renamePreviewFiles.size != 1) "s" else ""} affected",
                        color = Color(0xFF4EC9B0), fontSize = 12.sp)
                    HorizontalDivider(color = colors.gutter.copy(alpha = 0.3f), modifier = Modifier.padding(vertical = 4.dp))
                    renamePreviewFiles.forEach { (fileName, editCount) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("\uD83D\uDCC4", fontSize = 10.sp)
                            Text(fileName, color = Color(0xFFD4D4D4), fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("$editCount edit${if (editCount != 1) "s" else ""}", color = Color(0xFF888888), fontSize = 10.sp)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val wsEdit = renamePreviewEdit!!
                    val (newText, appliedAny) = applyWorkspaceEditToFilesystem(wsEdit, value.text, filePath)
                    if (appliedAny) {
                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                        programmaticTextChange(newText, TextRange(value.selection.start), "snippet_applied")
                    }
                    renamePreviewEdit = null; renamePreviewFiles = emptyList(); renameDialogWord = null
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007ACC))) {
                    Text("Apply", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamePreviewEdit = null; renamePreviewFiles = emptyList() }) {
                    Text("Cancel", color = Color(0xFF888888), fontSize = 12.sp)
                }
            },
        )
    }
}
