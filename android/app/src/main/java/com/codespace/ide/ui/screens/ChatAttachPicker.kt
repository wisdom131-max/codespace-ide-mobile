package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.codespace.ide.chat.ChatAttachment
import java.io.File

/**
 * R3-CHAT-PARITY: attachment UI — extracted to its own file (64KB rule).
 *
 *  - ChatAttachmentChips: removable chips above the input, one per pending attachment.
 *  - ChatAttachPickerDialog: in-app project-file picker (search + tap-to-attach).
 *
 * UI rules: rounded 8-12dp, item padding 12h/10v minimum.
 */

private val PICKER_SKIP_DIRS = setOf(
    ".git", "node_modules", ".gradle", "build", ".idea", ".vscode",
    "__pycache__", ".venv", "venv", "dist", ".next", ".nuxt",
    "target", "bin", "obj", ".cache", ".expo", ".dart_tool",
)

private const val PICKER_MAX_FILES = 400
private const val PICKER_MAX_FILE_KB = 512

private fun walkProjectFiles(projectRoot: String): List<String> {
    val out = ArrayList<String>()
    try {
        val queue = ArrayDeque<File>()
        queue.add(File(projectRoot))
        while (queue.isNotEmpty() && out.size < PICKER_MAX_FILES) {
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (out.size >= PICKER_MAX_FILES) break
                if (child.isDirectory) {
                    if (child.name !in PICKER_SKIP_DIRS && !child.name.startsWith(".")) queue.add(child)
                } else if (child.isFile && child.length() in 1..(PICKER_MAX_FILE_KB * 1024)) {
                    out.add(child.relativeTo(File(projectRoot)).path)
                }
            }
        }
    } catch (_: Exception) { }
    return out
}

@Composable
internal fun ChatAttachmentChips(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    colors: ChatPanelColors,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        attachments.forEach { att ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colors.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.accent),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        when (att.kind) {
    ChatAttachment.Kind.IMAGE -> Icons.Default.Image
    ChatAttachment.Kind.AUDIO -> Icons.Default.MusicNote
    else -> Icons.Default.Description
},
                        null,
                        tint = colors.accent,
                        modifier = Modifier.padding(end = 4.dp).height(12.dp).width(12.dp),
                    )
                    Text(
                        att.relPath,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Default.Close, "Remove attachment",
                        tint = colors.textSecondary,
                        modifier = Modifier.height(12.dp).width(12.dp).clickable { onRemove(att) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ChatAttachPickerDialog(
    projectRoot: String?,
    onPick: (ChatAttachment) -> Unit,
    onDismiss: () -> Unit,
    colors: ChatPanelColors,
    // R4-ATTACH-SELECTION: non-null enables the "current editor selection" row
    onPickSelection: ((ChatAttachment) -> Unit)? = null,
    // R8-VISION: non-null enables the "attach image from device" row
    onPickImage: (() -> Unit)? = null,
) {
    if (projectRoot.isNullOrBlank()) { onDismiss(); return }
    var query by remember { mutableStateOf("") }
    val allFiles = remember(projectRoot) { walkProjectFiles(projectRoot) }
    val shown = remember(query, allFiles) {
        if (query.isBlank()) allFiles
        else allFiles.filter { it.contains(query, ignoreCase = true) }.take(120)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Attach file to chat",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // I2 — TERMINAL BRIDGE attach sources (computed unconditionally)
                val termCtx = LocalContext.current
                val termPasted = remember { com.codespace.ide.terminal.TerminalAiBridge.lastPastedCommand }
                val termHist = remember(termCtx) {
                    com.codespace.ide.ui.panes.TerminalHistoryStore.load(termCtx).takeLast(5)
                }
                val termOut = remember { com.codespace.ide.terminal.TerminalAiBridge.transcriptTail() }
                // I4 — context attach completion: problems + debug console
                val problemsSnapshot = remember {
                    com.codespace.ide.diagnostics.DiagnosticManager.diagnostics
                        .filter { !it.isStale }
                        .take(50)
                }
                val debugConsoleOut = remember { com.codespace.ide.chat.DebugConsoleCapture.tail() }
                // R4-ATTACH-SELECTION: attach the editor's live selection (VS Code parity)
                val liveSel = remember { com.codespace.ide.editor.EditorSelectionStore.take() }
                if (liveSel != null && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                val f = File(liveSel.filePath)
                                val rel = try {
                                    if (f.path.startsWith(projectRoot)) f.relativeTo(File(projectRoot)).path else f.name
                                } catch (_: Exception) { f.name }
                                onPickSelection(
                                    ChatAttachment(
                                        path = liveSel.filePath,
                                        relPath = rel,
                                        name = f.name,
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = liveSel.selText,
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ContentCopy, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text(
                                "Attach current editor selection",
                                fontSize = 11.sp,
                                color = colors.text,
                            )
                            Text(
                                liveSel.filePath.substringAfterLast('/') + " — " +
                                    liveSel.selText.count { it != '\n' }.toString() + " chars selected",
                                fontSize = 9.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // I2 — TERMINAL BRIDGE rows: last pasted command, recent shell history, output tail
                if (termPasted != null && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "terminal", relPath = "terminal", name = "terminal",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = "$ " + termPasted,
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ContentCopy, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach last pasted terminal command", fontSize = 11.sp, color = colors.text)
                            Text(termPasted, fontSize = 9.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (termHist.isNotEmpty() && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "terminal", relPath = "terminal", name = "terminal",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = "Recent shell history:\n" + termHist.joinToString("\n") { "$ " + it },
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.History, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach recent shell history (5)", fontSize = 11.sp, color = colors.text)
                            Text(termHist.lastOrNull() ?: "", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (termOut != null && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "terminal", relPath = "terminal", name = "terminal",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = "Terminal output (newest last):\n" + termOut,
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Description, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach terminal output (tail)", fontSize = 11.sp, color = colors.text)
                            Text("Latest scrollback, ANSI-stripped", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                        }
                    }
                }
                // I4 — "Attach problems" row (VS Code chatDynamicVariables #problems analog)
                if (problemsSnapshot.isNotEmpty() && onPickSelection != null) {
                    val errCount = problemsSnapshot.count { it.severity == com.codespace.ide.diagnostics.DiagnosticManager.Severity.ERROR }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "problems", relPath = "problems", name = "problems",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = problemsSnapshot.joinToString("\n") { d ->
                                            (if (d.severity == com.codespace.ide.diagnostics.DiagnosticManager.Severity.ERROR) "[ERROR] " else "[WARN] ") +
                                                d.filePath.substringAfterLast('/') + ":" + d.range.startLine + " " + d.message
                                        },
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Warning, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach problems ($errCount errors)", fontSize = 11.sp, color = colors.text)
                            Text("Live diagnostics, newest 50", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                        }
                    }
                }
                // I4 — "Attach debug console output" row
                if (debugConsoleOut != null && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "debug-console", relPath = "debug-console", name = "debug-console",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = "Debug console (newest last):\n" + debugConsoleOut,
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Description, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach debug console output", fontSize = 11.sp, color = colors.text)
                            Text("Newest REPL + output lines", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                        }
                    }
                }
                // I4 — "Paste from clipboard" row (VS Code chatPasteTargetService analog)
                if (onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                val cm = termCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                val clip = cm.primaryClip
                                val item = clip?.getItemAt(0)
                                val text = item?.coerceToText(termCtx)?.toString()?.trim()
                                if (!text.isNullOrEmpty() && text.length <= 12000) {
                                    onPickSelection(
                                        ChatAttachment(
                                            path = "clipboard", relPath = "clipboard", name = "clipboard",
                                            kind = ChatAttachment.Kind.SELECTION,
                                            selText = text,
                                        )
                                    )
                                } else {
                                    // Image on the clipboard? Attach via the same URI import path.
                                    val uri = item?.uri
                                    val imgAtt = if (uri != null) {
                                        try {
                                            com.codespace.ide.chat.ChatImageAttachments.importFromUri(termCtx, uri)
                                        } catch (_: Exception) { null }
                                    } else null
                                    if (imgAtt != null) {
                                        onPickSelection(imgAtt)
                                    } else {
                                        android.widget.Toast.makeText(
                                            termCtx, "Clipboard has no attachable content",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.ContentPaste, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Paste from clipboard", fontSize = 11.sp, color = colors.text)
                            Text("Text → context · image → image attachment", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
                        }
                    }
                }
                // R8-VISION: attach an image from the device (multimodal request)
                if (onPickImage != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable { onPickImage() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Image, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach image or audio from device", fontSize = 11.sp, color = colors.text)
                            Text("Images: JPEG/PNG/GIF/WebP \u2264 5 MB · Audio: MP3/WAV \u2264 10 MB — sent with your next message",
                                fontSize = 9.sp, color = colors.textSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    placeholder = { Text("Search project files\u2026", color = colors.textSecondary, fontSize = 11.sp) },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.divider,
                    ),
                )
                if (allFiles.isEmpty()) {
                    Text(
                        "No project files found",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(320.dp),
                    ) {
                        items(shown) { rel ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(colors.surface)
                                    .clickable {
                                        val abs = File(projectRoot, rel).absolutePath
                                        onPick(
                                            ChatAttachment(
                                                path = abs, relPath = rel,
                                                name = File(rel).name,
                                                kind = ChatAttachment.Kind.FILE,
                                            )
                                        )
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.Description, null,
                                    tint = colors.accent,
                                    modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                                )
                                Text(
                                    rel,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
