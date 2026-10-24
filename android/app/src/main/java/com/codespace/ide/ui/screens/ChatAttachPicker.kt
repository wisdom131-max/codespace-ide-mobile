package com.codespace.ide.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
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

// CW7: .vscode REMOVED (user config — attachable, matches Explorer visibility).
// App-internal dirs + .svn/.hg added so the picker hides the same set as the
// Explorer's default-hide list. .codespace is intentionally NOT skipped — it is
// user-authored content (modes/skills/snippets/exports) and CW7's core case.
private val PICKER_SKIP_DIRS = setOf(
    ".git", "node_modules", ".gradle", "build", ".idea",
    "__pycache__", ".venv", "venv", "dist", ".next", ".nuxt",
    "target", "bin", "obj", ".cache", ".expo", ".dart_tool",
    ".svn", ".hg", ".ide-trash", ".versionhistory", ".autosave",
)

private const val PICKER_MAX_FILES = 400

// A2 (advisor ruling 2026-10-24): the walk keeps its 400-entry LIST cap (with an
// honest "showing first 400" row) but loses the silent 512 KB SIZE filter —
// any file can be PICKED; classification, the 10 MB intake cap and honest
// refusals all happen at attach/send time (verdict + ChatAttachment.INTAKE_CAP_BYTES).
private const val PICKER_SEARCH_SCAN_CAP = 20000
private const val PICKER_SEARCH_RESULT_CAP = 120

/** Returns (files, truncated): truncated = the 400-entry walk cap was hit. */
internal fun walkProjectFiles(projectRoot: String): Pair<List<String>, Boolean> {
    val out = ArrayList<String>()
    var truncated = false
    try {
        val queue = ArrayDeque<File>()
        queue.add(File(projectRoot))
        while (queue.isNotEmpty()) {
            if (out.size >= PICKER_MAX_FILES) { truncated = true; break }
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (out.size >= PICKER_MAX_FILES) break
                if (child.isDirectory) {
                    // CW7: blanket dot-dir skip removed — the targeted PICKER_SKIP_DIRS
                    // list above now carries .git/.ide-trash/.versionhistory/.autosave
                    // etc., so user dot-dirs (.codespace, .wisdom, .env...) attach fine.
                    if (child.name !in PICKER_SKIP_DIRS) queue.add(child)
                } else if (child.isFile && child.length() > 0L && !child.name.endsWith(".chatapply.tmp")) {
                    out.add(child.relativeTo(File(projectRoot)).path)
                }
            }
        }
    } catch (_: Exception) { }
    return out to truncated
}

/**
 * A2: search walks the WHOLE project (bounded by a 20,000-file scan budget) so
 * "search to find more" is honest for files beyond the 400-file list. Only
 * invoked when the in-list filter found nothing, keeping the common keystroke
 * path an in-memory filter. Returns (matches, scanTruncated).
 */
internal fun searchProjectFiles(projectRoot: String, query: String): Pair<List<String>, Boolean> {
    val matches = ArrayList<String>()
    var scanned = 0
    var scanTruncated = false
    try {
        val queue = ArrayDeque<File>()
        queue.add(File(projectRoot))
        while (queue.isNotEmpty()) {
            if (scanned >= PICKER_SEARCH_SCAN_CAP) { scanTruncated = true; break }
            val dir = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children.sortedBy { it.name }) {
                if (scanned >= PICKER_SEARCH_SCAN_CAP) { scanTruncated = true; break }
                if (child.isDirectory) {
                    if (child.name !in PICKER_SKIP_DIRS) queue.add(child)
                } else if (child.isFile && !child.name.endsWith(".chatapply.tmp")) {
                    scanned++
                    val rel = child.relativeTo(File(projectRoot)).path
                    if (rel.contains(query, ignoreCase = true)) {
                        matches.add(rel)
                        if (matches.size >= PICKER_SEARCH_RESULT_CAP) return matches to false
                    }
                }
            }
        }
    } catch (_: Exception) { }
    return matches to scanTruncated
}

@Composable
internal fun ChatAttachmentChips(
    attachments: List<ChatAttachment>,
    onRemove: (ChatAttachment) -> Unit,
    colors: ChatPanelColors,
    /** C12 s1-b (2026-10-08): chip body tap opens the AttachmentSheet. */
    onOpen: (ChatAttachment) -> Unit = { },
    /** C12 s1-d (2026-10-08): post-send chips are read-only — no x/remove button. */
    readOnly: Boolean = false,
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
                // C12 s1-b, advisor item 6: the chip BODY is one large tap target
                // (opens the AttachmentSheet) — the x removal is a SEPARATE
                // trailing button with a 48dp touch target, so open-tap and
                // remove-tap cannot be confused on a phone.
                // C12-U6a (advisor fix 2026-10-22, owner device report): the
                // chip is COMPACT — filename + type label only. The long
                // capability reason (118 chars for PDFs) and ALL content stay
                // in the sheet (tap = the separate preview). One uniform shape
                // for every kind; width-capped so one chip cannot eat the row.
                modifier = Modifier.clickable { onOpen(att) }.widthIn(max = 240.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp).height(48.dp),
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
                    Column {
                        Text(
                            att.relPath + if (att.snapshot != null && att.snapshot.edited) " (edited)" else "",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // C12-U6a (2026-10-22): the chip shows the TYPE LABEL
                        // only ("PDF document"), not the full capability line —
                        // the reason stays in the sheet's separate preview.
                        att.typeLine?.let { line ->
                            Text(
                                line.substringBefore(" — ").ifBlank { line },
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (!readOnly) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .width(48.dp)
                                .fillMaxHeight()
                                .clickable { onRemove(att) },
                            contentAlignment = androidx.compose.ui.Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Close, "Remove attachment",
                                tint = colors.textSecondary,
                                modifier = Modifier.height(12.dp).width(12.dp),
                            )
                        }
                    }
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
    /** I6 — prompt files: tap inserts the file's content into the chat input. */
    onInsertPrompt: ((String) -> Unit)? = null,
    /** R9-B — skills: tap RUNS the skill (prefill input + auto-attach hinted context; NEVER auto-send — D4). */
    onRunSkill: ((com.codespace.ide.chat.SkillsCatalog.Skill) -> Unit)? = null,
    // R8-VISION: non-null enables the "attach image from device" row
    onPickImage: (() -> Unit)? = null,
    /** A3 (advisor 2026-10-24): non-null enables the "attach file from device" row (system picker). */
    onPickDeviceFile: (() -> Unit)? = null,
    // R10-E: non-null enables the "attach screenshot of the app" row
    onPickScreenshot: (() -> Unit)? = null,
    /**
     * G-E-c (advisor GO 2026-10-21): unknown-model image offer. The caller
     * computes modelImagesUnknown via ChatCapabilitySeam.imagesUnknownFor —
     * which is FALSE while the capability switch is OFF, so the row never
     * exists while the switch is off (no dead controls, the ruling).
     */
    modelImagesUnknown: Boolean = false,
    modelImagesLabel: String = "",
    onAcceptImagesForModel: (() -> Unit)? = null,
) {
    if (projectRoot.isNullOrBlank()) { onDismiss(); return }
    // R3-PICKER-FIX (2026-09-14): the dialog body overflowed short screens with
    // no scroll \u2014 sections below the fold (search bar, file list) were simply
    // unreachable. Whole body now scrolls; the file list keeps its fixed height.
    val maxDialogH = (android.content.res.Resources.getSystem().displayMetrics.heightPixels /
        android.content.res.Resources.getSystem().displayMetrics.density * 0.85f).dp
    var query by remember { mutableStateOf("") }
    val walk = remember(projectRoot) { walkProjectFiles(projectRoot) }
    val allFiles = walk.first
    val listTruncated = walk.second
    val shownState = remember(query, allFiles, projectRoot) {
        if (query.isBlank()) allFiles to false
        else {
            val inList = allFiles.filter { it.contains(query, ignoreCase = true) }.take(120)
            // A2: the in-list filter is instant; only when it finds nothing does
            // the search do a full (budgeted) project walk to find more.
            if (inList.isNotEmpty()) inList to false
            else searchProjectFiles(projectRoot, query)
        }
    }
    val shown = shownState.first
    val searchTruncated = shownState.second

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = colors.background,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // F4-FIX-2 (2026-09-16): heightIn MUST WRAP verticalScroll (order matters!).
                // Old order put heightIn INSIDE the scroll — it clamped the CONTENT to 85%
                // of the screen, so the scroll modifier saw content == viewport height
                // and had ZERO scroll range: the dialog rendered but could never scroll.
                // Correct: heightIn limits the DIALOG height; scroll handles overflow inside.
                Column(Modifier.padding(12.dp).heightIn(max = maxDialogH).verticalScroll(rememberScrollState())) {
                Text(
                    "Attach file to chat",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                )
                // G-E-c (2026-10-21): the unknown-model offer row — only when
                // the caller says the ACTIVE model is UNKNOWN for images (the
                // caller's computation is flag-gated, so this row cannot exist
                // while the switch is off).
                if (modelImagesUnknown && onAcceptImagesForModel != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .background(colors.surface, RoundedCornerShape(8.dp))
                            .clickable { onAcceptImagesForModel() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "'$modelImagesLabel' may accept images — set it to accepted?",
                            fontSize = 11.sp,
                            color = colors.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Accepts", fontSize = 11.sp, color = colors.accent)
                    }
                }
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
                // I6 — VS Code prompt-file analog: workspace .github/prompts/*.md + .codespace/prompts/*.md
                val promptFiles = remember(projectRoot) {
                    val root = projectRoot ?: return@remember emptyList<Pair<String, String>>()
                    val dirs = listOf(java.io.File(root, ".github/prompts"), java.io.File(root, ".codespace/prompts"))
                    dirs.flatMap { d ->
                        d.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }?.toList() ?: emptyList()
                    }.sortedBy { it.name }.map { it.name.removeSuffix(".md") to it.readText() }
                }
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
                // CW2 — "Attach git history" row (VS Code chat git-context analog; B07/B13)
                val gitLog = remember(projectRoot) {
                    try {
                        if (projectRoot == null) emptyList()
                        else com.codespace.ide.scm.GitService(termCtx).log(projectRoot, 50)
                    } catch (_: Exception) { emptyList() }
                }
                if (gitLog.isNotEmpty() && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "git-history", relPath = "git-history", name = "git-history",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = gitLog.joinToString("\n") { c ->
                                            c.hash.take(7) + " " + c.author + " " + c.date + " " +
                                                c.message.replace('\n', ' ').take(160)
                                        }.take(12000),
                                    )
                                )
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.History, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(16.dp).width(16.dp),
                        )
                        Column {
                            Text("Attach git history (" + gitLog.size + " commits)", fontSize = 11.sp, color = colors.text)
                            Text("Latest commits from this repo", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1)
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
                // D15-b B2 — LAST SHELL COMMAND row (OSC 633-observed): command + its
                // real output window + exit code, manual attach only (fail-closed AI
                // consumption stays parked for D15-c per the approved D15 plan).
                val lastShellRun = com.codespace.ide.terminal.TerminalAiBridge.lastShellRun.value
                if (lastShellRun != null && onPickSelection != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable {
                                onPickSelection(
                                    ChatAttachment(
                                        path = "terminal-command", relPath = "terminal-command", name = "terminal-command",
                                        kind = ChatAttachment.Kind.SELECTION,
                                        selText = "$ " + lastShellRun.command + "\n" +
                                            (lastShellRun.output ?: "(no output captured)") + "\n" +
                                            "[exit: " + (lastShellRun.exitCode?.toString() ?: "unknown") +
                                            (if (lastShellRun.nonceValidated) " | shell-verified" else "") + "]",
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
                            Text(
                                "Attach last terminal command (exit " + (lastShellRun.exitCode?.toString() ?: "?") + ")",
                                fontSize = 11.sp,
                                color = colors.text,
                            )
                            Text(
                                lastShellRun.command,
                                fontSize = 9.sp,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
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
                // I6 — "Prompts" section (VS Code promptSyntax/prompt-files analog)
                if (promptFiles.isNotEmpty() && onInsertPrompt != null) {
                    Text(
                        "Prompts",
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                    promptFiles.forEach { (pname, ptext) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(colors.surface)
                                .clickable { onInsertPrompt(ptext) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Description, null,
                                tint = colors.accent,
                                modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                            )
                            Column {
                                Text("/" + pname, fontSize = 11.sp, color = colors.text)
                                Text(ptext.lineSequence().firstOrNull()?.take(60) ?: "Empty prompt", fontSize = 9.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // R9-B — "Skills" section (VS Code agentPluginsView/skills analog).
                // Tap = RUN: prefill input + auto-attach hinted context, then stop (D4).
                if (onRunSkill != null) {
                    val skills = remember(projectRoot) {
                        com.codespace.ide.chat.SkillsCatalog.all(projectRoot) +
                            com.codespace.ide.chat.SkillsCatalog.mcpSkills()
                    }
                    if (skills.isNotEmpty()) {
                        Text(
                            "Skills",
                            color = colors.text,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                        skills.forEach { sk ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(colors.surface)
                                    .clickable { onRunSkill(sk) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.AutoAwesome, null,
                                    tint = colors.accent,
                                    modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(sk.name, fontSize = 11.sp, color = colors.text)
                                    if (sk.description.isNotBlank()) {
                                        Text(sk.description, fontSize = 9.sp, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                Text(
                                    (if (sk.context != null) sk.context + " \u00b7 " else "") + sk.source,
                                    fontSize = 9.sp, color = colors.textSecondary,
                                )
                            }
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
                // A3 (advisor ruling 2026-10-24): attach ANY file from the device
                // via the SYSTEM picker — staged privately, capped at 10 MB while
                // streaming, classified after import by the same sniffer.
                if (onPickDeviceFile != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable { onPickDeviceFile() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Description, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach file from device", fontSize = 11.sp, color = colors.text)
                            Text("Any type ≤ 10 MB — staged privately, classified like project files",
                                fontSize = 9.sp, color = colors.textSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // R10-E: attach a screenshot of the app itself (vision context)
                if (onPickScreenshot != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface)
                            .clickable { onPickScreenshot() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.PhotoCamera, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach screenshot of app", fontSize = 11.sp, color = colors.text)
                            Text("Captures the current window (PNG) — sent with your next message",
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
                // R10-D: attach project-wide search RESULTS for the typed query
                if (query.isNotBlank()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(8.dp))
                            .clickable {
                                val att = com.codespace.ide.chat.SearchResultsAttach.buildAttachment(projectRoot, query)
                                if (att != null) onPick(att)
                                else android.widget.Toast.makeText(
                                    termCtx, "No content matches found", android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Search, null,
                            tint = colors.accent,
                            modifier = Modifier.padding(end = 8.dp).height(14.dp).width(14.dp),
                        )
                        Column {
                            Text("Attach search results for “" + query + "”", fontSize = 11.sp, color = colors.text)
                            Text("Project-wide content matches — sent with your next message",
                                fontSize = 9.sp, color = colors.textSecondary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                // A2: the honest replacement for the silent 400-file cutoff.
                if (query.isBlank() && listTruncated) {
                    Text(
                        "Showing first " + allFiles.size + " \u2014 search to find more",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (query.isNotBlank() && shown.isEmpty() && searchTruncated) {
                    Text(
                        "No matches in the first " + "20,000" + " files \u2014 refine your search",
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
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
