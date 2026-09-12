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
import androidx.compose.material.icons.filled.Description
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
                        Icons.Default.Description, null,
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
                            Icons.Default.Highlight, null,
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
