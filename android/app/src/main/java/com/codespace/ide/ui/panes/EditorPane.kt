package com.codespace.ide.ui.panes

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.CodeEditor
import org.json.JSONArray
import com.codespace.ide.editor.PeekDefResult
import com.codespace.ide.editor.FileCache
import com.codespace.ide.editor.MergeConflictParser
import com.codespace.ide.editor.ConflictHunk
import com.codespace.ide.editor.ConflictResolution
import com.codespace.ide.editor.DocumentFormatter
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.lsp.DocumentSymbolCache
import com.codespace.ide.lsp.parseHoverContent
import com.codespace.ide.lsp.parseLspCompletions
import com.codespace.ide.lsp.parseImportEdits
import com.codespace.ide.lsp.lspDiagnosticsToLintErrors
import com.codespace.ide.lsp.parseCodeActions
import com.codespace.ide.lsp.LspCodeAction
import com.codespace.ide.editor.SignatureInfo
import androidx.compose.ui.zIndex
import java.io.File
import com.codespace.ide.R
import com.codespace.ide.data.SessionStateStore
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Legacy global session prefs removed — workspace memory now handled by SessionStateStore.
// Kept only for migration: read once then clear.
private fun migrateLegacySession(context: Context): Pair<List<String>, String?> {
    val prefs = context.getSharedPreferences("editor_session", Context.MODE_PRIVATE)
    val paths = prefs.getString("open_paths", "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
    val active = prefs.getString("active_path", null)
    prefs.edit().clear().apply()   // one-time migration — delete legacy data
    return Pair(paths, active)
}


private val TabBarBg = Color(0xFFECECEC)
private val TabActiveBg = Color(0xFFFFFFFF)
private val TabInactiveBg = Color(0xFFECECEC)
private val TabActiveIndicator = Color(0xFF007ACC)
private val TabText = Color(0xFF333333)
private val TabTextInactive = Color(0xFF717171)
private val DividerColor = Color(0xFFE0E0E0)

fun detectLanguage(name: String): Language = when {
    name.endsWith(".kt") || name.endsWith(".kts") -> Language.KOTLIN
    name.endsWith(".py") -> Language.PYTHON
    name.endsWith(".ts") || name.endsWith(".tsx") -> Language.TYPESCRIPT
    name.endsWith(".js") || name.endsWith(".jsx") -> Language.JAVASCRIPT
    name.endsWith(".java") -> Language.JAVA
    name.endsWith(".json") -> Language.JSON
    name.endsWith(".md") -> Language.MARKDOWN
    name.endsWith(".html") || name.endsWith(".htm") -> Language.HTML
    name.endsWith(".css") -> Language.CSS
    name.endsWith(".xml") -> Language.XML
    name.endsWith(".sh") -> Language.SHELL
    name.endsWith(".c") || name.endsWith(".h") -> Language.C
    name.endsWith(".cpp") || name.endsWith(".cc") -> Language.CPP
    name.endsWith(".rs") -> Language.RUST
    name.endsWith(".go") -> Language.GO
    else -> Language.PLAINTEXT
}

fun loadFileContent(path: String): String = try {
    FileCache.get(path).content
} catch (e: Exception) {
    "// Could not read file: ${e.message}"
}

/**
 * Multi-tab editor. Tabs are opened by the Explorer passing a full file path.
 * Actual file content is read from disk and displayed in [CodeEditor].
 * Saving writes back to disk.
 */
@Composable
fun EditorPane(
    openFilePath: String? = null,
    onFileOpened: (() -> Unit)? = null,
    onInsertRequest: (((String) -> Unit) -> Unit)? = null,
    fontSize: Int = 13,
    onCursorChange: ((Int, Int) -> Unit)? = null,
    wordWrap: Boolean = false,
    showInlayHints: Boolean = true,  // P2-11
    scrollToLine: Int = 0,
    projectId: String? = null,
    sessionStateStore: SessionStateStore? = null,
    udm: com.codespace.ide.debug.UniversalDebugManager? = null,
) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    // P18-C: project root for cross-file rename
    val projectRootPath = projectId?.let { java.io.File(context.filesDir, "projects/$it").absolutePath }
    val tabs = remember { mutableStateListOf<EditorTab>() }
    var activeId by remember { mutableStateOf<String?>(null) }
    // P20-A: Git Blame
    var showBlame by remember { mutableStateOf(false) }
    var blameData by remember { mutableStateOf<Map<Int, com.codespace.ide.editor.BlameLine>?>(null) }
    // P22-D: Merge conflict detection
    var conflictHunks by remember { mutableStateOf<List<ConflictHunk>?>(null) }
    // P22-E: Format Document
    var formatting by remember { mutableStateOf(false) }
    // P22-G: LSP diagnostics + hover
    val lspOpenedFiles = remember { mutableStateMapOf<String, Boolean>() }

    // P24-2: LSP server teardown — stop all servers when EditorPane leaves composition
    DisposableEffect(Unit) {
        onDispose {
            // stopAll sends LSP shutdown + exit to every running server, killing their processes.
            // No need to individually didClose each file — shutdown covers it.
            try { LspManager.stopAll() } catch (_: Exception) {}
            lspOpenedFiles.clear()
        }
    }
    var lspCursorLine by remember { mutableStateOf(0) }
    var lspCursorCol by remember { mutableStateOf(0) }
    // P35: Guards to prevent redundant LSP requests when cursor position hasn't changed.
    // TextFieldValue changes (IME events, scroll, auto-save) can re-fire LaunchedEffects
    // even when the actual cursor line/col are the same. These track the last position
    // that was actually queried so we can skip duplicate requests.
    var lastHoverLine by remember { mutableStateOf(-1) }
    var lastHoverCol by remember { mutableStateOf(-1) }
    var lastHighlightLine by remember { mutableStateOf(-1) }
    var lastHighlightCol by remember { mutableStateOf(-1) }
    var showLspHover by remember { mutableStateOf(true) }  // P33: auto-hover enabled by default
    var lspHoverContent by remember { mutableStateOf<String?>(null) }
    // P24-1: LSP diagnostic squiggles — updated by setDiagnosticsHandler callback
    var lspSquiggles by remember { mutableStateOf<List<com.codespace.ide.editor.LintError>>(emptyList()) }
    // P24: visible banner shown when LSP server fails to start (not just logcat)
    var lspStatusMessage by remember { mutableStateOf<String?>(null) }
    var splitId by remember { mutableStateOf<String?>(null) }
    // P2-9 Bookmarks: path → set of bookmarked line indices
    val fileBookmarks = remember { mutableStateMapOf<String, Set<Int>>() }
    // P8-1 Breakpoints: path → set of breakpoint line indices (0-based)
    val fileBreakpoints = remember { mutableStateMapOf<String, Set<Int>>() }
    // P26-1: Scroll to line (from debug call stack click)
    var scrollToLine by remember { mutableStateOf(0) }
    // P26-1: LSP Document Highlight — auto-highlight all occurrences of symbol under cursor
    var lspHighlightLines by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    // P26-1: LSP Completion Resolve — richer completion info
    var lspResolvedDetail by remember { mutableStateOf<String?>(null) }
    // P26-1: LSP Document Symbol — outline structure (classes, functions, etc.)
    var lspDocumentSymbols by remember { mutableStateOf<JSONArray?>(null) }
    // P26-1: LSP Folding Range — LSP-based code folding
    var lspFoldingRanges by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    // P26-1: LSP Code Lens — inline annotations (ref count, run/test)
    var lspCodeLenses by remember { mutableStateOf<JSONArray?>(null) }
    // P26-1: LSP Inlay Hints — inline type/parameter hints
    var lspInlayHints by remember { mutableStateOf<JSONArray?>(null) }
    // P26-1: LSP Document Links — clickable links in comments
    var lspDocumentLinks by remember { mutableStateOf<JSONArray?>(null) }
    // P26-1: LSP Type Definition — Go to Type Definition result
    var lspTypeDefResult by remember { mutableStateOf<PeekDefResult?>(null) }
    // P26-1: LSP Implementation — Find Implementations result
    var lspImplResults by remember { mutableStateOf<List<Triple<String, Int, String>>>(emptyList()) }
    // P26-1: LSP Workspace Symbol search results
    var showBookmarkPanel by remember { mutableStateOf(false) }
    var findReplaceOpen by remember { mutableStateOf(false) }
    var goToLineOpen by remember { mutableStateOf(false) }
    // Pinned tab paths set
    val pinnedPaths = remember { mutableStateListOf<String>() }
    // Per-file scroll line memory (path → first visible line)
    val tabScrollLines = remember { mutableStateMapOf<String, Int>() }
    // Per-file cursor offset memory (path → char offset) — persisted via EditorTab.cursorOffset
    val tabCursorOffsets = remember { mutableStateMapOf<String, Int>() }

    // ── Workspace memory restore ──────────────────────────────────────────
    LaunchedEffect(Unit) {
        if (tabs.isEmpty()) {
            val store = sessionStateStore
            val pid = projectId
            // Try per-project restore first, fall back to legacy migration
            val restoredPaths: List<String>
            val restoredActive: String?
            val restoredPinned: List<String>
            val restoredSplit: String?
            if (store != null && pid != null) {
                val state = store.loadShellState(pid)
                restoredPaths  = state?.openFilePaths ?: emptyList()
                restoredActive = state?.activeFilePath
                restoredPinned = state?.pinnedFilePaths ?: emptyList()
                restoredSplit  = state?.splitFilePath
                // Restore per-file scroll and cursor positions
                store.loadScrollPositions(pid).forEach { (p, line) -> tabScrollLines[p] = line }
                store.loadCursors(pid).forEach { (p, off) -> tabCursorOffsets[p] = off }
            } else {
                // One-time legacy migration
                val (legacy, legacyActive) = migrateLegacySession(context)
                restoredPaths  = legacy
                restoredActive = legacyActive
                restoredPinned = emptyList()
                restoredSplit  = null
            }
            restoredPaths.forEach { path ->
                val file = File(path)
                if (file.exists()) {
                    val tab = EditorTab(
                        id = path,
                        path = path,
                        name = file.name,
                        content = loadFileContent(path),
                        language = detectLanguage(file.name),
                        isDirty = false,
                        cursorOffset = tabCursorOffsets[path] ?: 0,
                    )
                    tabs.add(tab)
                }
            }
            pinnedPaths.addAll(restoredPinned.filter { p -> tabs.any { it.path == p } })
            if (restoredSplit != null && tabs.any { it.path == restoredSplit }) {
                splitId = restoredSplit
            }
            activeId = tabs.firstOrNull { it.path == restoredActive }?.id ?: tabs.firstOrNull()?.id
        }
    }

    // Unsaved changes warning
    var showUnsavedDialog by remember { mutableStateOf(false) }
    val hasDirtyTabs = tabs.any { it.isDirty }

    BackHandler(enabled = hasDirtyTabs) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        key(orientation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            title = { androidx.compose.material3.Text("Unsaved Changes") },
            text = { androidx.compose.material3.Text("You have unsaved changes. Save before leaving?") },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    tabs.forEachIndexed { idx, tab ->
                        if (tab.isDirty && tab.path.startsWith("/")) {
                            try {
                                java.io.File(tab.path).writeText(tab.content)
                                tabs[idx] = tab.copy(isDirty = false)
                            } catch (_: Exception) {}
                        }
                    }
                    showUnsavedDialog = false
                    Toast.makeText(context, "Saved ✓", Toast.LENGTH_SHORT).show()
                }) { androidx.compose.material3.Text("Yes, Save") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showUnsavedDialog = false
                }) { androidx.compose.material3.Text("No") }
            },
        )
        }
    }

    // Wire up keyboard toolbar insert callback
    LaunchedEffect(onInsertRequest) {
        onInsertRequest?.invoke { text ->
            val active = tabs.firstOrNull { it.id == activeId } ?: return@invoke
            val idx = tabs.indexOfFirst { it.id == activeId }
            val newContent = active.content + text
            if (idx >= 0) tabs[idx] = active.copy(content = newContent, isDirty = true)
            if (active.path.startsWith("/")) {
                try { File(active.path).writeText(newContent); FileCache.invalidate(active.path) } catch (_: Exception) {}
            }
        }
    }

    // Open a new tab when the explorer requests a file
    LaunchedEffect(openFilePath) {
        if (openFilePath != null) {
            val existing = tabs.firstOrNull { it.path == openFilePath }
            if (existing != null) {
                activeId = existing.id
            } else {
                val name = File(openFilePath).name
                val content = loadFileContent(openFilePath)
                if (FileCache.isLargeFile(openFilePath)) {
                    // P9-4: Large file — content still loads but isLargeFile flag is available
                    // for the editor to render in read-only/streamed mode
                }
                val tab = EditorTab(
                    id = openFilePath,
                    path = openFilePath,
                    name = name,
                    content = content,
                    language = detectLanguage(name),
                )
                tabs.add(tab)
                activeId = tab.id
            }
            onFileOpened?.invoke()
        }
    }

    // ── Workspace memory: persist on every state change ─────────────────
    val currentTabList = tabs.toList()
    LaunchedEffect(currentTabList, activeId, pinnedPaths.toList(), splitId) {
        val store = sessionStateStore
        val pid = projectId
        if (store != null && pid != null) {
            val state = SessionStateStore.ShellState(
                projectId      = pid,
                activeFilePath = tabs.firstOrNull { it.id == activeId }?.path,
                openFilePaths  = tabs.map { it.path },
                pinnedFilePaths = pinnedPaths.toList(),
                splitFilePath  = splitId?.let { id -> tabs.firstOrNull { it.id == id }?.path },
                // activePanel / bottomTab / showBottomPanel managed by ProjectShellScreen
            )
            store.saveShellState(pid, state)
            // Persist cursor offsets
            val cursors = tabs.associate { it.path to it.cursorOffset }
            store.saveCursors(pid, cursors)
            // Persist scroll lines
            store.saveScrollPositions(pid, tabScrollLines.toMap())
        }
    }


    // ── Autosave: every 30s write dirty tabs to .autosave/ ──────────────
    // On next launch (see restore block below), these are offered back to the user
    // as a restore dialog so no work is lost after a crash or force-close.
    val autosaveDir = remember(projectId) {
        projectId?.let {
            File(context.filesDir, "projects/$it/.autosave").also { d -> d.mkdirs() }
        }
    }

    LaunchedEffect(projectId) {
        while (true) {
            delay(30_000L)
            val dir = autosaveDir ?: continue
            val dirty = tabs.filter { it.isDirty && it.path.startsWith("/") }
            withContext(Dispatchers.IO) {
                dirty.forEach { tab ->
                    try {
                        // P37-CORRUPTION-FIX: Use URL-encoded full path as autosave filename
                        // instead of just the file basename. Two files named "test.js" in
                        // different directories were overwriting each other's autosave,
                        // causing content from one file to be restored into the other on
                        // next launch — manifesting as "merged/tangled" file content.
                        val safeName = java.net.URLEncoder.encode(tab.path, "UTF-8")
                        File(dir, "$safeName.autosave").writeText(tab.content)
                    } catch (_: Exception) {}
                }
                // Remove autosave files for tabs that are now clean (saved or closed)
                val activePaths = tabs.filter { it.path.startsWith("/") }
                    .map { java.net.URLEncoder.encode(it.path, "UTF-8") + ".autosave" }.toSet()
                dir.listFiles()?.forEach { f -> if (f.name !in activePaths) f.delete() }
            }
        }
    }

    // ── Autosave restore: offer recovery dialog on first open if stale saves exist ──
    var showAutosaveRestoreDialog by remember { mutableStateOf(false) }
    var autosaveFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(projectId) {
        val dir = autosaveDir ?: return@LaunchedEffect
        delay(1_500L) // let normal session restore settle first
        // P37-CORRUPTION-FIX: Delete old basename-based autosave files that
        // can't be matched to the correct file by full path. New format
        // starts with %2F (URL-encoded /). Old format was just the basename.
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { f ->
                if (f.name.endsWith(".autosave") && !f.name.startsWith("%2F")) {
                    f.delete()
                }
            }
        }
        val stale = withContext(Dispatchers.IO) {
            dir.listFiles()?.filter { it.name.endsWith(".autosave") } ?: emptyList()
        }
        if (stale.isNotEmpty()) {
            autosaveFiles = stale
            showAutosaveRestoreDialog = true
        }
    }

    if (showAutosaveRestoreDialog && autosaveFiles.isNotEmpty()) {
        key(orientation) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {
                    showAutosaveRestoreDialog = false
                    // User dismissed without restoring — delete the autosave files
                    autosaveFiles.forEach { it.delete() }
                    autosaveFiles = emptyList()
                },
                title = { androidx.compose.material3.Text("Restore unsaved edits?") },
                text = {
                    Column {
                        androidx.compose.material3.Text(
                            "${autosaveFiles.size} file(s) have unsaved edits from the last session:",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        autosaveFiles.forEach { f ->
                            androidx.compose.material3.Text(
                                "• ${try { java.net.URLDecoder.decode(f.name.removeSuffix(".autosave"), "UTF-8") } catch (_: Exception) { f.name.removeSuffix(".autosave") }}",
                                fontSize = 12.sp,
                                color = Color(0xFF89B4FA)
                            )
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.Button(onClick = {
                        showAutosaveRestoreDialog = false
                        autosaveFiles.forEach { autosave ->
                            try {
                                val originalName = autosave.name.removeSuffix(".autosave")
                                // Find matching open tab by name, restore its content
                                val idx = tabs.indexOfFirst { File(it.path).name == originalName }
                                val recoveredContent = autosave.readText()
                                if (idx >= 0) {
                                    tabs[idx] = tabs[idx].copy(content = recoveredContent, isDirty = true)
                                }
                                autosave.delete()
                            } catch (_: Exception) {}
                        }
                        autosaveFiles = emptyList()
                        Toast.makeText(context, "Edits restored ✓", Toast.LENGTH_SHORT).show()
                    }) { androidx.compose.material3.Text("Restore") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showAutosaveRestoreDialog = false
                        autosaveFiles.forEach { it.delete() }
                        autosaveFiles = emptyList()
                    }) { androidx.compose.material3.Text("Discard") }
                },
            )
        }
    }

    // No sample tabs — editor starts empty, waiting for Explorer

    Column(Modifier.fillMaxSize()) {
        // Tab bar
        if (tabs.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(TabBarBg)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.Bottom,
            ) {
                tabs.forEach { tab ->
                    val isActive = tab.id == activeId
                    Column(
                        Modifier
                            .clickable { activeId = tab.id }
                            .background(if (isActive) TabActiveBg else TabInactiveBg)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                (if (tab.isDirty) "● " else "") + tab.name,
                                fontSize = 11.sp,
                                color = if (isActive) TabText else TabTextInactive,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = TabTextInactive,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        val idx = tabs.indexOfFirst { it.id == tab.id }
                                        // P24-2: LSP didClose before removing tab
                                        val closedPath = tab.path
                                        val closedLang = tab.language
                                        val closedUri = "file://$closedPath"
                                        if (lspOpenedFiles[closedPath] == true && LspManager.isServerRunning(closedLang)) {
                                            try { LspManager.didClose(closedLang, closedUri) } catch (_: Exception) {}
                                            lspOpenedFiles.remove(closedPath)
                                        }
                                        tabs.remove(tab)
                                        if (activeId == tab.id) {
                                            activeId = tabs.getOrNull(idx - 1)?.id ?: tabs.firstOrNull()?.id
                                        }
                                        if (splitId == tab.id) splitId = null
                                        // P24-2: Stop server if no more files open for this language (30s grace)
                                        val remainingForLang = tabs.count { it.language == closedLang }
                                        if (remainingForLang == 0 && LspManager.isServerRunning(closedLang)) {
                                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                kotlinx.coroutines.delay(30_000) // 30s idle grace period
                                                val stillZero = tabs.count { it.language == closedLang } == 0
                                                if (stillZero) {
                                                    try { LspManager.stopServer(closedLang) } catch (_: Exception) {}
                                                }
                                            }
                                        }
                                    },
                            )
                        }
                        if (isActive) Box(Modifier.fillMaxWidth().height(1.dp).background(TabActiveIndicator))
                    }
                    Box(Modifier.width(1.dp).height(28.dp).background(DividerColor))
                }
                // Split view button
                IconButton(onClick = { findReplaceOpen = !findReplaceOpen }, modifier = Modifier.size(35.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.FindReplace,
                        contentDescription = "Find & Replace",
                        tint = if (findReplaceOpen) androidx.compose.ui.graphics.Color(0xFF007ACC)
                               else androidx.compose.ui.graphics.Color(0xFF858585),
                        modifier = Modifier.size(18.dp),
                    )
                }
                // P20-A: Git Blame toggle
                IconButton(onClick = { showBlame = !showBlame }, modifier = Modifier.size(35.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = "Git Blame",
                        tint = if (showBlame) androidx.compose.ui.graphics.Color(0xFF007ACC)
                               else androidx.compose.ui.graphics.Color(0xFF858585),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { goToLineOpen = !goToLineOpen }, modifier = Modifier.size(35.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                        contentDescription = "Go to Line",
                        tint = if (goToLineOpen) androidx.compose.ui.graphics.Color(0xFF007ACC)
                               else androidx.compose.ui.graphics.Color(0xFF858585),
                        modifier = Modifier.size(18.dp),
                    )
                }
                // P22-E: Format Document button (built-in regex formatter)
                IconButton(
                    onClick = {
                        val activeTab = tabs.firstOrNull { it.id == activeId }
                        if (activeTab != null && !formatting) {
                            formatting = true
                            kotlinx.coroutines.MainScope().launch(kotlinx.coroutines.Dispatchers.IO) {
                                val result = DocumentFormatter.format(context, activeTab.path, activeTab.language)
                                if (result.success && result.formattedContent != null) {
                                    val idx2 = tabs.indexOfFirst { it.id == activeTab.id }
                                    if (idx2 >= 0) {
                                        tabs[idx2] = activeTab.copy(content = result.formattedContent, isDirty = true)
                                        if (activeTab.path.startsWith("/")) {
                                            try { File(activeTab.path).writeText(result.formattedContent); FileCache.invalidate(activeTab.path) } catch (_: Exception) {}
                                        }
                                    }
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Formatted ✓ (${activeTab.language.displayName} formatter)", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // P37-TEST4-FIX: surface the failure/no-op reason instead of silently doing nothing
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        Toast.makeText(context, "Format: ${result.message}", Toast.LENGTH_LONG).show()
                                    }
                                    AppOutputLog.log("[Format] regex-button result: ${result.message}", "format")
                                }
                                formatting = false
                            }
                        }
                    },
                    modifier = Modifier.size(35.dp)
                ) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        if (formatting) {
                            CircularProgressIndicator(color = androidx.compose.ui.graphics.Color(0xFF007ACC), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.Default.Functions,
                                contentDescription = "Format Document",
                                tint = androidx.compose.ui.graphics.Color(0xFF858585),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        // P37-3: tiny "regex" label under the icon
                        Text("regex", fontSize = 6.sp, color = Color(0xFF858585))
                    }
                }
                // P22-G: LSP Hover toggle
                IconButton(onClick = { showLspHover = !showLspHover }, modifier = Modifier.size(35.dp)) {
                    Text(
                        text = "?",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (showLspHover) Color(0xFF007ACC) else TabTextInactive,
                    )
                }
                // P25-LSP: Format document button — calls LSP formatting when available
                IconButton(
                    onClick = {
                        val tab = tabs.firstOrNull { it.id == activeId }
                        if (tab != null && LspManager.isServerRunning(tab.language)) {
                            val uri = LspManager.fileUriFromHostPath(context, tab.path)
                            if (uri != null) {
                                val edits = try { LspManager.getFormatting(tab.language, uri) } catch (_: Exception) { null }
                                if (edits != null && edits.length() > 0) {
                                    val newContent = applyTextEdits(tab.content, edits)
                                    if (newContent != tab.content) {
                                        val idx = tabs.indexOfFirst { it.id == tab.id }
                                        if (idx >= 0) tabs[idx] = tab.copy(content = newContent, isDirty = true)
                                        if (tab.path.startsWith("/")) {
                                            try { java.io.File(tab.path).writeText(newContent); FileCache.invalidate(tab.path) } catch (_: Exception) {}
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.size(35.dp)
                ) {
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text(
                            text = "{}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = run {
                                val t = tabs.firstOrNull { it.id == activeId }
                                if (t != null && LspManager.isServerRunning(t.language)) Color(0xFF4EC9B0) else TabTextInactive
                            },
                        )
                        // P37-3: tiny LSP/regex label under the icon
                        val t = tabs.firstOrNull { it.id == activeId }
                        Text(
                            if (t != null && LspManager.isServerRunning(t.language)) "LSP" else "",
                            fontSize = 7.sp,
                            color = Color(0xFF4EC9B0),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                IconButton(onClick = { splitId = if (splitId == null) activeId else null }, modifier = Modifier.size(35.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Split", tint = TabTextInactive, modifier = Modifier.size(16.dp))
                }
                // P2-9 Bookmarks panel toggle
                IconButton(onClick = { showBookmarkPanel = !showBookmarkPanel }, modifier = Modifier.size(35.dp)) {
                    Text(
                        text = "◆",
                        fontSize = 14.sp,
                        color = if (showBookmarkPanel) Color(0xFF61AFEF) else TabTextInactive,
                    )
                }
            }
            HorizontalDivider(color = DividerColor)
        }

        // P2-9 Bookmark panel — dropdown list of all bookmarks across open files
        if (showBookmarkPanel) {
            val allBookmarks = fileBookmarks.flatMap { (filePath, lines) ->
                lines.sorted().map { line -> Pair(filePath, line) }
            }.sortedWith(compareBy({ it.first }, { it.second }))

            if (allBookmarks.isEmpty()) {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    tonalElevation = 2.dp,
                ) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        Text("No bookmarks — tap ◆ in the gutter to add one", fontSize = 12.sp, color = TabTextInactive)
                    }
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                        .background(Color(0xFF252526)),
                ) {
                    items(allBookmarks) { (filePath, lineIdx) ->
                        val fileName = java.io.File(filePath).name
                        val lineContent = try {
                            java.io.File(filePath).readLines().getOrElse(lineIdx) { "" }.trim()
                        } catch (_: Exception) { "" }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val tab = tabs.firstOrNull { it.path == filePath }
                                    if (tab != null) activeId = tab.id
                                    showBookmarkPanel = false
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("◆", color = Color(0xFF61AFEF), fontSize = 12.sp)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("$fileName : ${lineIdx + 1}", fontSize = 11.sp, color = Color(0xFF61AFEF), fontWeight = FontWeight.Bold)
                                if (lineContent.isNotBlank()) {
                                    Text(lineContent, fontSize = 11.sp, color = TabTextInactive, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                    }
                }
            }
        }

        val active = tabs.firstOrNull { it.id == activeId } ?: tabs.firstOrNull()

        // P22-G / LSP-FIX: Split into two effects.
        // Effect A: keyed on (id, language) ONLY — starts the server and sends didOpen.
        // NOT keyed on content: prevents keystroke-triggered recomposition from cancelling
        // a long-running startServer() call mid-execution (install can take 10-120s).
        LaunchedEffect(active?.id, active?.language) {
            val snap = active ?: return@LaunchedEffect
            // BUG-7 FIX: Show informational note for unsupported languages instead of silent no-op
            if (!LspManager.isSupported(snap.language)) {
                // Language has no LSP server configured — keyword-only completion is used.
                // Common unsupported: JSON, XML, Markdown, Shell (no standard LSP for these in proot)
                AppOutputLog.log("[LSP] No LSP server configured for ${snap.language.displayName} — keyword completion only", "lsp")
                return@LaunchedEffect
            }
            if (projectRootPath == null) {
                AppOutputLog.log("[LSP] Effect-A: projectRootPath is null for ${snap.language.displayName} — file opened outside a project context", "lsp")
                return@LaunchedEffect
            }
            val uri = LspManager.fileUriFromHostPath(context, snap.path)
            android.util.Log.d("LspTrigger", "LSP Effect-A fired: path=${snap.path} lang=${snap.language} uri=$uri serverRunning=${LspManager.isServerRunning(snap.language)}")
            AppOutputLog.log("[LSP] Effect-A fired for ${snap.language.displayName}: ${snap.path.substringAfterLast('/')} — serverRunning=${LspManager.isServerRunning(snap.language)}", "lsp")
            if (uri == null) {
                android.util.Log.e("LspTrigger", "LSP Effect-A: uri is null for path=${snap.path} — file not in filesDir or rootfs bind-mount. LSP skipped.")
                AppOutputLog.log("[LSP] ERROR: Cannot map path to proot guest path — LSP skipped (${snap.path})", "lsp")
                lspStatusMessage = "LSP unavailable: file path not accessible inside Ubuntu rootfs (${snap.path})"
                return@LaunchedEffect
            }
            if (!LspManager.isServerRunning(snap.language)) {
                lspStatusMessage = "Starting ${snap.language.displayName} language server..."
                android.util.Log.d("LspTrigger", "LSP Effect-A: calling startServer for ${snap.language.displayName}")
                AppOutputLog.log("[LSP] Effect-A: starting ${snap.language.displayName} server for project $projectRootPath…", "lsp")
                val lspStarted = withContext(Dispatchers.IO) {
                    LspManager.startServer(context, snap.language, projectRootPath)
                }
                android.util.Log.d("LspTrigger", "LSP Effect-A: startServer returned $lspStarted for ${snap.language.displayName}")
                AppOutputLog.log("[LSP] Effect-A: startServer returned $lspStarted for ${snap.language.displayName}", "lsp")
                lspStatusMessage = if (lspStarted) null
                else "LSP unavailable: ${snap.language.displayName} server failed to start. Check npm/pip in terminal."
            } else {
                AppOutputLog.log("[LSP] Effect-A: ${snap.language.displayName} server already running — skipping startServer", "lsp")
            }
            if (LspManager.isServerRunning(snap.language)) {
                delay(300)
                if (lspOpenedFiles[snap.path] != true) {
                    android.util.Log.d("LspTrigger", "LSP Effect-A: sending didOpen for ${snap.path}")
                    AppOutputLog.log("[LSP] Effect-A: sending didOpen for ${snap.path.substringAfterLast('/')}", "lsp")
                    LspManager.didOpen(snap.language, uri, LspManager.languageId(snap.language), snap.content)
                    lspOpenedFiles[snap.path] = true
                    AppOutputLog.log("[LSP] Effect-A: didOpen complete for ${snap.path.substringAfterLast('/')}", "lsp")
                }
            }
        }
        // Effect B: keyed on (id, content) — sends didChange on every edit.
        // Server startup is NOT here so cancellation on keystroke is harmless.
        LaunchedEffect(active?.id, active?.content) {
            val snap = active ?: return@LaunchedEffect
            if (!LspManager.isSupported(snap.language)) return@LaunchedEffect
            if (!LspManager.isServerRunning(snap.language)) return@LaunchedEffect
            if (lspOpenedFiles[snap.path] != true) return@LaunchedEffect
            val uri = LspManager.fileUriFromHostPath(context, snap.path) ?: return@LaunchedEffect
            val version = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
            withContext(Dispatchers.IO) {
                LspManager.didChange(snap.language, uri, snap.content, version)
            }
        }
        // GAP-9 FIX: Diagnostics subscription belongs in its own stable effect, NOT in the
        // hover effect that fires on every cursor move. Re-subscribing on every cursor move
        // was harmless but wasteful and could cause missed diagnostics if the handler
        // was replaced mid-delivery. Keyed on (id, language) so it re-subscribes only
        // when the file or language changes — exactly when a new uri is in scope.
        LaunchedEffect(active?.id, active?.language) {
            val snap = active ?: return@LaunchedEffect
            if (!LspManager.isSupported(snap.language)) return@LaunchedEffect
            val uri = LspManager.fileUriFromHostPath(context, snap.path) ?: return@LaunchedEffect
            LspManager.setDiagnosticsHandler(snap.language) { diagUri, diags ->
                // P33-INTELLISENSE: Normalize both URIs before comparing — server may
                // return %20 for spaces while our URI has raw spaces (or vice versa).
                val normDiag = LspManager.normalizeFileUri(diagUri)
                val normUri  = LspManager.normalizeFileUri(uri)
                if (normDiag == normUri) {
                    lspSquiggles = lspDiagnosticsToLintErrors(diags, snap.content)
                }
            }
        }

        // P22-G: LSP hover on cursor position change (debounced)
        // P35 FIX: Guard against re-firing when cursor hasn't actually moved.
        // The LaunchedEffect re-fires on any TextFieldValue change (IME events, scroll,
        // auto-save reload) even when the cursor line/col haven't changed. The guard
        // tracks the last position queried and skips the LSP request if unchanged.
        LaunchedEffect(lspCursorLine, lspCursorCol, showLspHover) {
            if (showLspHover && active != null && LspManager.isServerRunning(active.language)) {
                // Skip if this position was already queried — prevents idle spam
                if (lspCursorLine == lastHoverLine && lspCursorCol == lastHoverCol) return@LaunchedEffect
                delay(300)
                lastHoverLine = lspCursorLine
                lastHoverCol = lspCursorCol
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val hover = withContext(Dispatchers.IO) {
                        LspManager.getHover(active.language, uri, lspCursorLine, lspCursorCol)
                    }
                    lspHoverContent = hover?.let { parseHoverContent(it) }
                }
            }
        }

        // P26-1: LSP Document Highlight — highlight all occurrences on cursor move (debounced)
        // P35 FIX: Same guard as hover — skip if position unchanged from last query.
        LaunchedEffect(lspCursorLine, lspCursorCol) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                if (lspCursorLine == lastHighlightLine && lspCursorCol == lastHighlightCol) return@LaunchedEffect
                delay(400)
                lastHighlightLine = lspCursorLine
                lastHighlightCol = lspCursorCol
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val highlights = withContext(Dispatchers.IO) {
                        LspManager.getDocumentHighlight(active.language, uri, lspCursorLine, lspCursorCol)
                    }
                    if (highlights != null && highlights.length() > 0) {
                        val ranges = mutableListOf<Pair<Int, Int>>()
                        for (i in 0 until highlights.length()) {
                            val hl = highlights.optJSONObject(i) ?: continue
                            val range = hl.optJSONObject("range") ?: continue
                            val startLine = range.optJSONObject("start")?.optInt("line", -1) ?: -1
                            val endLine = range.optJSONObject("end")?.optInt("line", -1) ?: -1
                            if (startLine >= 0) ranges.add(startLine to endLine)
                        }
                        lspHighlightLines = ranges
                    } else {
                        lspHighlightLines = emptyList()
                    }
                }
            } else {
                lspHighlightLines = emptyList()
            }
        }

        // P35: Reset LSP query guards when switching files — ensures first cursor
        // position in a new file always gets queried even if it matches the last
        // position from the previous file (e.g. both open at line 0, col 0).
        LaunchedEffect(active?.path) {
            lastHoverLine = -1
            lastHoverCol = -1
            lastHighlightLine = -1
            lastHighlightCol = -1
        }

        // P26-1: LSP Document Symbol — fetch outline structure on file open (debounced)
        LaunchedEffect(active?.path) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                delay(500)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val symbols = withContext(Dispatchers.IO) {
                        LspManager.getDocumentSymbol(active.language, uri)
                    }
                    lspDocumentSymbols = symbols
                    // P37-3fix: Share with OutlinePanel via cache to avoid duplicate request
                    DocumentSymbolCache.put(active.path, symbols)
                }
            } else {
                lspDocumentSymbols = null
            }
        }

        // P26-1: LSP Folding Range — fetch foldable regions on file open
        LaunchedEffect(active?.path) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                delay(600)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val ranges = withContext(Dispatchers.IO) {
                        LspManager.getFoldingRange(active.language, uri)
                    }
                    if (ranges != null && ranges.length() > 0) {
                        val foldRanges = mutableListOf<Pair<Int, Int>>()
                        for (i in 0 until ranges.length()) {
                            val r = ranges.optJSONObject(i) ?: continue
                            val startLine = r.optInt("startLine", -1)
                            val endLine = r.optInt("endLine", -1)
                            if (startLine >= 0 && endLine > startLine) foldRanges.add(startLine to endLine)
                        }
                        lspFoldingRanges = foldRanges
                    }
                }
            }
        }

        // P26-1: LSP Code Lens — fetch inline annotations (ref count, run/test)
        LaunchedEffect(active?.path) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                delay(700)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val lenses = withContext(Dispatchers.IO) {
                        LspManager.getCodeLens(active.language, uri)
                    }
                    lspCodeLenses = lenses
                }
            } else {
                lspCodeLenses = null
            }
        }

        // P26-1: LSP Inlay Hints — fetch inline type/parameter hints
        LaunchedEffect(active?.path, active?.content) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                delay(800)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val hints = withContext(Dispatchers.IO) {
                        LspManager.getInlayHints(active.language, uri)
                    }
                    lspInlayHints = hints
                }
            } else {
                lspInlayHints = null
            }
        }

        // P26-1: LSP Document Links — fetch clickable links in comments/strings
        LaunchedEffect(active?.path) {
            if (active != null && LspManager.isServerRunning(active.language)) {
                delay(500)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val links = withContext(Dispatchers.IO) {
                        LspManager.getDocumentLinks(active.language, uri)
                    }
                    lspDocumentLinks = links
                }
            } else {
                lspDocumentLinks = null
            }
        }

        // Sticky scroll — computed unconditionally (Compose rules of hooks)
        val stickyScope = remember(active?.content, scrollToLine) {
            if (scrollToLine <= 0 || active == null) null
            else {
                val lines = active.content.split("\n")
                val scopeKeywords = listOf("fun ", "class ", "object ", "interface ", "enum ", "@Composable", "if ", "when ", "for ", "while ", "struct ", "impl ", "fn ", "def ", "func ")
                (scrollToLine - 1 downTo 0)
                    .map { i -> lines.getOrNull(i)?.trim() }
                    .firstOrNull { line -> line != null && scopeKeywords.any { kw -> line!!.contains(kw) } }
            }
        }

        if (active != null) {
            val splitTab = splitId?.let { id -> tabs.firstOrNull { it.id == id && it.id != active.id } }
            if (splitTab != null) {
                Row(Modifier.fillMaxSize()) {
                    // P20-A: Fetch git blame data
                    if (showBlame && active != null) {
                        val blamePath = active.path
                        LaunchedEffect(showBlame, blamePath) {
                            if (showBlame) {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val repoDir2 = blamePath.substringBeforeLast("/")
                                        val fileName = blamePath.substringAfterLast("/")
                                        val guestPath = com.codespace.ide.terminal.ProotInstaller.hostToGuestPath(context, repoDir2)
                                        if (guestPath != null) {
                                            val raw = com.codespace.ide.terminal.ProotInstaller.execOnce(context, "git blame --line-porcelain '$fileName'", guestPath)
                                            val map = mutableMapOf<Int, com.codespace.ide.editor.BlameLine>()
                                            var idx = 0; var author = ""; var sha = ""
                                            raw.lines().forEach { ln ->
                                                if (ln.startsWith("author ") && !ln.startsWith("author-")) author = ln.removePrefix("author ").trim()
                                                else if (ln.length >= 40 && ln.matches(Regex("^[0-9a-f]{40}.*"))) sha = ln.substring(0, 8)
                                                else if (ln.startsWith("\t")) {
                                                    map[idx] = com.codespace.ide.editor.BlameLine(author.take(12), "", sha)
                                                    idx++
                                                }
                                            }
                                            map.toMap()
                                        } else null
                                    } catch (_: Exception) { null }
                                }
                                blameData = result
                            }
                        }
                    }
                    // P20-A: Fetch git blame data
                    if (showBlame && active != null) {
                        val blamePath = active.path
                        LaunchedEffect(showBlame, blamePath) {
                            if (showBlame) {
                                val result = withContext(Dispatchers.IO) {
                                    try {
                                        val repoDir2 = blamePath.substringBeforeLast("/")
                                        val fileName = blamePath.substringAfterLast("/")
                                        val guestPath = com.codespace.ide.terminal.ProotInstaller.hostToGuestPath(context, repoDir2)
                                        if (guestPath != null) {
                                            val raw = com.codespace.ide.terminal.ProotInstaller.execOnce(context, "git blame --line-porcelain '$fileName'", guestPath)
                                            val map = mutableMapOf<Int, com.codespace.ide.editor.BlameLine>()
                                            var idx = 0; var author = ""; var sha = ""
                                            raw.lines().forEach { ln ->
                                                if (ln.startsWith("author ") && !ln.startsWith("author-")) author = ln.removePrefix("author ").trim()
                                                else if (ln.length >= 40 && ln.matches(Regex("^[0-9a-f]{40}.*"))) sha = ln.substring(0, 8)
                                                else if (ln.startsWith("\t")) {
                                                    map[idx] = com.codespace.ide.editor.BlameLine(author.take(12), "", sha)
                                                    idx++
                                                }
                                            }
                                            map.toMap()
                                        } else null
                                    } catch (_: Exception) { null }
                                }
                                blameData = result
                            }
                        }
                    }
                    // P22-D: Detect merge conflicts in current file
                    val detectedConflicts = remember(active.content) {
                        if (MergeConflictParser.hasConflicts(active.content)) {
                            MergeConflictParser.parse(active.content)
                        } else null
                    }
                    if (conflictHunks != detectedConflicts) {
                        conflictHunks = detectedConflicts
                    }
                    // P24: LSP status banner — visible in editor when server fails
                    lspStatusMessage?.let { msg ->
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(androidx.compose.ui.graphics.Color(0xFF3A2A00))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Text(
                                text = msg,
                                color = androidx.compose.ui.graphics.Color(0xFFFFCC44),
                                fontSize = 11.sp,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(
                                onClick = { lspStatusMessage = null },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("✕", color = androidx.compose.ui.graphics.Color(0xFFFFCC44), fontSize = 11.sp)
                            }
                        }
                    }
                    CodeEditor(
                        content = active.content,
                        language = active.language,
                        fontSize = fontSize,
                        savedContent = active.savedContent,
                        onContentChange = { newText ->
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0) tabs[idx] = active.copy(content = newText, isDirty = true)
                            if (active.path.startsWith("/")) {
                                try { File(active.path).writeText(newText); FileCache.invalidate(active.path) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.weight(1f),
                        wordWrap = wordWrap,
                        showInlayHints = showInlayHints,
                        scrollToLine = scrollToLine,
                        findReplaceOpen = findReplaceOpen,
                        onFindReplaceClose = { findReplaceOpen = false },
                        goToLineOpen = goToLineOpen,
                        onGoToLineClose = { goToLineOpen = false },
                        breakpointLines = fileBreakpoints[active.path] ?: emptySet(),
                        onBreakpointToggle = { line ->
                            val cur = fileBreakpoints[active.path] ?: emptySet()
                            fileBreakpoints[active.path] = if (line in cur) cur - line else cur + line
                            udm?.toggleBreakpoint(active.path, line)
                        },
                        projectRoot = projectRootPath,
                    )
                    Box(Modifier.width(1.dp).fillMaxHeight().background(DividerColor))
                    CodeEditor(
                        content = splitTab.content,
                        language = splitTab.language,
                        fontSize = fontSize,
                        onContentChange = {},
                        modifier = Modifier.weight(1f),
                        wordWrap = wordWrap,
                        showInlayHints = showInlayHints,
                        findReplaceOpen = findReplaceOpen,
                        onFindReplaceClose = { findReplaceOpen = false },
                        goToLineOpen = goToLineOpen,
                        onGoToLineClose = { goToLineOpen = false },
                        projectRoot = projectRootPath,
                    )
                }
            } else {
                // ── Sticky Scroll header (computed above unconditionally) ───────────
                if (stickyScope != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E1E1E).copy(alpha = 0.97f))
                            .zIndex(8f)
                            .padding(horizontal = 64.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = stickyScope,
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                key(active.id) {
                    // P22-D: Detect merge conflicts in current file
                    val detectedConflicts = remember(active.content) {
                        if (MergeConflictParser.hasConflicts(active.content)) {
                            MergeConflictParser.parse(active.content)
                        } else null
                    }
                    if (conflictHunks != detectedConflicts) {
                        conflictHunks = detectedConflicts
                    }
                    CodeEditor(
                        content = active.content,
                        language = active.language,
                        fontSize = fontSize,
                        savedContent = active.savedContent,
                        onContentChange = { newText ->
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0) tabs[idx] = active.copy(content = newText, isDirty = true)
                            if (active.path.startsWith("/")) {
                                try { File(active.path).writeText(newText); FileCache.invalidate(active.path) } catch (_: Exception) {}
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        wordWrap = wordWrap,
                        showInlayHints = showInlayHints,
                        findReplaceOpen = findReplaceOpen,
                        onFindReplaceClose = { findReplaceOpen = false },
                        goToLineOpen = goToLineOpen,
                        onGoToLineClose = { goToLineOpen = false },
                        initialBookmarks = fileBookmarks[active.path] ?: emptySet(),
                        onBookmarksChange = { updated -> fileBookmarks[active.path] = updated },
                        projectRoot = projectRootPath,
                        onOpenFileAtLine = { filePath, line ->
                            val file = java.io.File(filePath)
                            if (tabs.none { it.path == filePath }) {
                                tabs.add(EditorTab(
                                    id = filePath,
                                    path = filePath,
                                    name = file.name,
                                    content = try { file.readText() } catch (_: Exception) { "" },
                                    language = Language.fromPath(filePath),
                                    isDirty = false,
                                    savedContent = try { file.readText() } catch (_: Exception) { "" },
                                ))
                            }
                            activeId = filePath
                        },
                        blameData = if (showBlame) blameData else null,
                        conflictData = conflictHunks,
                        onResolveConflict = { hunk, resolution ->
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0) {
                                val resolved = MergeConflictParser.resolveHunk(active.content, hunk, resolution)
                                tabs[idx] = active.copy(content = resolved, isDirty = true)
                                if (active.path.startsWith("/")) {
                                    try { File(active.path).writeText(resolved); FileCache.invalidate(active.path) } catch (_: Exception) {}
                                }
                                // Re-detect remaining conflicts
                                conflictHunks = if (MergeConflictParser.hasConflicts(resolved)) {
                                    MergeConflictParser.parse(resolved)
                                } else null
                            }
                        },
                        onCursorChange = { line, col ->
                            lspCursorLine = line
                            lspCursorCol = col
                            onCursorChange?.invoke(line, col)
                        },
                        lspCompletionProvider = if (LspManager.isServerRunning(active.language)) {
                            { line, col ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val items = LspManager.getCompletion(active.language, uri, line, col)
                                    // P26-1: Resolve first item for richer docs
                                    items?.let { arr ->
                                        if (arr.length() > 0) {
                                            val first = arr.optJSONObject(0)
                                            if (first != null) {
                                                val resolved = try { LspManager.resolveCompletion(active.language, first) } catch (_: Exception) { null }
                                                if (resolved != null) {
                                                    val detail = resolved.optString("detail", "")
                                                    val docs = resolved.opt("documentation")
                                                    val docText = when (docs) {
                                                        is org.json.JSONObject -> docs.optString("value", "")
                                                        is String -> docs
                                                        else -> ""
                                                    }
                                                    if (detail.isNotBlank() || docText.isNotBlank()) {
                                                        lspResolvedDetail = (if (detail.isNotBlank()) detail else "") +
                                                            (if (docText.isNotBlank()) "\n$docText" else "")
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    items?.let { parseLspCompletions(it) } ?: emptyList()
                                } else emptyList()
                            }
                        } else null,
                        lspImportProvider = if (LspManager.isServerRunning(active.language)) {
                            { line, col ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val actions = LspManager.getCodeActions(active.language, uri, line, col)
                                    actions?.let { parseImportEdits(it, uri) } ?: emptyList()
                                } else emptyList()
                            }
                        } else null,
                        // P37-4: LSP Code Actions (quick fixes in context menu)
                        lspCodeActionProvider = if (LspManager.isServerRunning(active.language)) {
                            { line ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val actions = try { LspManager.getCodeActions(active.language, uri, line, 0) } catch (_: Exception) { null }
                                    actions?.let { parseCodeActions(it) } ?: emptyList<LspCodeAction>()
                                } else emptyList()
                            }
                        } else null,
                        // P24-1: Pass LSP diagnostic squiggles to editor
                        lspDiagnosticErrors = lspSquiggles,
                        // P24-3: Find References via LSP
                        onFindReferences = if (LspManager.isServerRunning(active.language)) {
                            { word ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val refs = try {
                                        LspManager.getReferences(active.language, uri, lspCursorLine, lspCursorCol)
                                    } catch (_: Exception) { null }
                                    refs?.let { arr ->
                                        (0 until arr.length()).mapNotNull { i ->
                                            val loc = arr.optJSONObject(i) ?: return@mapNotNull null
                                            val refUri = loc.optString("uri", "")
                                            val refPathRaw = if (refUri.startsWith("file://")) refUri.removePrefix("file://") else refUri
                                            val refPath = try { java.net.URLDecoder.decode(refPathRaw, "UTF-8") } catch (_: Exception) { refPathRaw }
                                            val line = loc.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
                                            val snippet = try { java.io.File(refPath).readLines().getOrElse(line) { "" } } catch (_: Exception) { "" }
                                            Triple(refPath, line, snippet)
                                        }
                                    } ?: emptyList()
                                } else emptyList()
                            }
                        } else null,
                        // P24-3: Rename Symbol — triggers LSP workspace rename after regex rename
                        onRenameSymbol = if (LspManager.isServerRunning(active.language)) {
                            { word, newName ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            LspManager.rename(active.language, uri, lspCursorLine, lspCursorCol, newName)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        } else null,
                        // P25-LSP: LSP-backed signature help — knows ALL functions in codebase
                        lspSignatureHelpProvider = if (LspManager.isServerRunning(active.language)) {
                            { line, col ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val sigHelp = try {
                                        LspManager.getSignatureHelp(active.language, uri, line, col)
                                    } catch (_: Exception) { null }
                                    if (sigHelp != null) {
                                        val sigs = sigHelp.optJSONArray("signatures")
                                        val activeSig = sigHelp.optInt("activeSignature", 0)
                                        val activeParam = sigHelp.optInt("activeParameter", 0)
                                        if (sigs != null && sigs.length() > 0) {
                                            val sig = sigs.optJSONObject(activeSig.coerceAtMost(sigs.length() - 1))
                                            if (sig != null) {
                                                val label = sig.optString("label", "")
                                                val params = sig.optJSONArray("parameters")
                                                val paramList = if (params != null) {
                                                    (0 until params.length()).mapNotNull { i ->
                                                        val p = params.optJSONObject(i)
                                                        p?.optString("label", "")?.takeIf { it.isNotBlank() }
                                                    }
                                                } else {
                                                    // Fallback: try to parse params from the label
                                                    label.substringAfter("(", "").substringBefore(")", "").split(",").map { it.trim() }.filter { it.isNotBlank() }
                                                }
                                                SignatureInfo(
                                                    name = label.substringBefore("(").trim(),
                                                    params = paramList,
                                                    returnType = when (val doc = sig.opt("documentation")) {
                            is String -> doc.takeIf { it.isNotBlank() }
                            is org.json.JSONObject -> doc.optString("value", "").takeIf { it.isNotBlank() }
                            else -> null
                        },
                                                    activeParam = activeParam,
                                                )
                                            } else null
                                        } else null
                                    } else null
                                } else null
                            }
                        } else null,
                        // P25-LSP: Format document via LSP
                        // P26-1: LSP Document Highlight lines
                        lspHighlightLines = lspHighlightLines,
                        // P26-1: LSP Document Symbols (outline)
                        lspDocumentSymbols = lspDocumentSymbols,
                        // P26-1: LSP Folding Ranges
                        lspFoldingRanges = lspFoldingRanges,
                        // P26-1: LSP Code Lens
                        lspCodeLenses = lspCodeLenses,
                        // P26-1: LSP Inlay Hints
                        lspInlayHints = lspInlayHints,
                        // P26-1: LSP Document Links
                        lspDocumentLinks = lspDocumentLinks,
                        // P26-1: LSP Type Definition (context menu)
                        onLspTypeDefinition = if (LspManager.isServerRunning(active.language)) {
                            {
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                var succeeded = false
                                if (uri != null) {
                                    val typeDefs = try { LspManager.getTypeDefinition(active.language, uri, lspCursorLine, lspCursorCol) } catch (_: Exception) { null }
                                    if (typeDefs != null && typeDefs.length() > 0) {
                                        val loc = typeDefs.optJSONObject(0)
                                        if (loc != null) {
                                            val defUri = loc.optString("uri", "")
                                            val defLine = loc.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
                                            val defPathRaw = if (defUri.startsWith("file://")) defUri.removePrefix("file://") else defUri
                                            val defPath = try { java.net.URLDecoder.decode(defPathRaw, "UTF-8") } catch (_: Exception) { defPathRaw }
                                            val targetText = if (defPath == active.path) active.content else try { java.io.File(defPath).readText() } catch (_: Exception) { null }
                                            if (targetText != null) {
                                                val allLines = targetText.split("\n")
                                                val startLine = (defLine - 3).coerceAtLeast(0)
                                                val endLine = (defLine + 8).coerceAtMost(allLines.size - 1)
                                                val snippet = allLines.subList(startLine, endLine + 1)
                                                lspTypeDefResult = PeekDefResult(defPath, defLine, snippet, defLine - startLine)
                                                succeeded = true
                                            }
                                        }
                                    }
                                }
                                succeeded
                            }
                        } else null,
                        // P26-1: LSP Implementation (context menu)
                        onLspImplementation = if (LspManager.isServerRunning(active.language)) {
                            {
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                var succeeded = false
                                if (uri != null) {
                                    val impls = try { LspManager.getImplementation(active.language, uri, lspCursorLine, lspCursorCol) } catch (_: Exception) { null }
                                    val results = mutableListOf<Triple<String, Int, String>>()
                                    if (impls != null && impls.length() > 0) {
                                        for (i in 0 until impls.length()) {
                                            val loc = impls.optJSONObject(i) ?: continue
                                            val implUri = loc.optString("uri", "")
                                            val implLine = loc.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
                                            val implPathRaw = if (implUri.startsWith("file://")) implUri.removePrefix("file://") else implUri
                                            val implPath = try { java.net.URLDecoder.decode(implPathRaw, "UTF-8") } catch (_: Exception) { implPathRaw }
                                            val snippet = try { java.io.File(implPath).readLines().getOrElse(implLine) { "" } } catch (_: Exception) { "" }
                                            results.add(Triple(implPath, implLine, snippet))
                                        }
                                        succeeded = results.isNotEmpty()
                                    }
                                    lspImplResults = results
                                }
                                succeeded
                            }
                        } else null,
                        // P26-1: LSP Selection Range
                        onLspSelectionRange = if (LspManager.isServerRunning(active.language)) {
                            { line, col ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    try { LspManager.getSelectionRange(active.language, uri, line, col) } catch (_: Exception) { null }
                                } else null
                            }
                        } else null,
                        // P26-1: LSP Prepare Rename
                        onLspPrepareRename = if (LspManager.isServerRunning(active.language)) {
                            { line, col ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    try { LspManager.prepareRename(active.language, uri, line, col) } catch (_: Exception) { null }
                                } else null
                            }
                        } else null,
                    )
                }
                // P22-G: LSP hover popup
                if (showLspHover && lspHoverContent != null) {
                    val hoverScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(Color(0xFF2D2D2D))
                            .zIndex(10f)
                    ) {
                        Box(
                            modifier = Modifier
                                .heightIn(max = 200.dp)
                                .verticalScroll(hoverScrollState)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = lspHoverContent ?: "",
                                color = Color(0xFFCCCCCC),
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.vncode_watermark),
                        contentDescription = null,
                        alpha = 0.18f,
                        modifier = Modifier.fillMaxWidth(0.75f),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Open Explorer → tap a file to start",
                        fontSize = 13.sp,
                        color = Color(0xFFBBBBBB),
                        fontWeight = FontWeight.Light,
                    )
                }
            }
        }

        // P26-1: LSP Type Definition Peek overlay
        if (lspTypeDefResult != null) {
            val peek = lspTypeDefResult!!
            androidx.compose.material3.Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.5f),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF1E1E1E)
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ) {
                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0xFF252526))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Type Definition",
                            color = androidx.compose.ui.graphics.Color(0xFFC586C0),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        val fileName = peek.filePath.substringAfterLast('/')
                        Text(
                            "$" + "fileName:$" + "{peek.line + 1}",
                            color = androidx.compose.ui.graphics.Color(0xFF888888),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { lspTypeDefResult = null }) {
                            Text("X", color = androidx.compose.ui.graphics.Color(0xFF888888), fontSize = 16.sp)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF333333))
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        peek.lines.forEachIndexed { idx, line ->
                            val isDefLine = idx == peek.defLine
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isDefLine) androidx.compose.ui.graphics.Color(0xFF007ACC).copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    "" + (peek.line - peek.defLine + idx + 1),
                                    color = if (isDefLine) androidx.compose.ui.graphics.Color(0xFF007ACC) else androidx.compose.ui.graphics.Color(0xFF858585),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.width(36.dp),
                                )
                                Text(
                                    line.take(120),
                                    color = if (isDefLine) androidx.compose.ui.graphics.Color(0xFFD4D4D4) else androidx.compose.ui.graphics.Color(0xFFAAAAAA),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF333333))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = { lspTypeDefResult = null }) {
                            Text("Close", color = androidx.compose.ui.graphics.Color(0xFF888888), fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(
                            onClick = {
                                val fp = peek.filePath
                                if (tabs.none { it.path == fp }) {
                                    tabs.add(EditorTab(
                                        id = fp,
                                        path = fp,
                                        name = fp.substringAfterLast('/'),
                                        content = try { java.io.File(fp).readText() } catch (_: Exception) { "" },
                                        language = Language.fromPath(fp),
                                        isDirty = false,
                                        savedContent = try { java.io.File(fp).readText() } catch (_: Exception) { "" },
                                    ))
                                }
                                activeId = fp
                                lspTypeDefResult = null
                            }
                        ) {
                            Text("Go to Definition ->", color = androidx.compose.ui.graphics.Color(0xFF007ACC), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
        // P26-1: LSP Find Implementations results overlay
        if (lspImplResults.isNotEmpty()) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { lspImplResults = emptyList() },
                containerColor = androidx.compose.ui.graphics.Color(0xFF252526),
                title = {
                    Text(
                        "Implementations (${lspImplResults.size})",
                        color = androidx.compose.ui.graphics.Color(0xFF4EC9B0),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                },
                text = {
                    androidx.compose.foundation.lazy.LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(lspImplResults) { (path, line, snippet) ->
                            val fileName = path.substringAfterLast('/')
                            TextButton(
                                onClick = {
                                    val fp = path
                                    if (tabs.none { it.path == fp }) {
                                        tabs.add(EditorTab(
                                            id = fp,
                                            path = fp,
                                            name = fp.substringAfterLast('/'),
                                            content = try { java.io.File(fp).readText() } catch (_: Exception) { "" },
                                            language = Language.fromPath(fp),
                                            isDirty = false,
                                            savedContent = try { java.io.File(fp).readText() } catch (_: Exception) { "" },
                                        ))
                                    }
                                    activeId = fp
                                    lspImplResults = emptyList()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        "$fileName:${line + 1}",
                                        color = androidx.compose.ui.graphics.Color(0xFF569CD6),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                    Text(
                                        snippet.trim().take(100),
                                        color = androidx.compose.ui.graphics.Color(0xFFAAAAAA),
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { lspImplResults = emptyList() }) {
                        Text("Close", color = androidx.compose.ui.graphics.Color(0xFF888888), fontSize = 12.sp)
                    }
                },
            )
        }
    }
}

private val SAMPLE_TS = """
// Visual Node Code — TypeScript sample
interface User {
  id: string;
  name: string;
}

async function greet(user: User): Promise<string> {
  const message = `Hello, ${'$'}{user.name}!`;
  return message;
}

greet({ id: "1", name: "Ada" }).then(console.log);
""".trimIndent()

private val SAMPLE_PY = """
# Visual Node Code — Python sample
def fibonacci(n: int) -> list[int]:
    seq = [0, 1]
    while len(seq) < n:
        seq.append(seq[-1] + seq[-2])
    return seq[:n]

if __name__ == "__main__":
    print(fibonacci(10))
""".trimIndent()

/**
 * P25-LSP: Apply LSP TextEdits (JSONArray of {range: {start, end}, newText}) to content.
 * TextEdits are sorted from end to start so earlier edits don't shift positions of later ones.
 */
private fun applyTextEdits(content: String, edits: org.json.JSONArray): String {
    val editList = (0 until edits.length()).mapNotNull { i ->
        val edit = edits.optJSONObject(i) ?: return@mapNotNull null
        val range = edit.optJSONObject("range") ?: return@mapNotNull null
        val start = range.optJSONObject("start")
        val end = range.optJSONObject("end")
        if (start == null || end == null) return@mapNotNull null
        val newText = edit.optString("newText", "")
        TextEdit(
            startLine = start.optInt("line", 0),
            startChar = start.optInt("character", 0),
            endLine = end.optInt("line", 0),
            endChar = end.optInt("character", 0),
            newText = newText,
        )
    }.sortedByDescending { it.startLine * 100000 + it.startChar }

    var result = content
    val lines = result.split("\n").toMutableList()
    for (edit in editList) {
        if (edit.startLine >= lines.size) continue
        if (edit.startLine == edit.endLine) {
            // Single-line edit
            val line = lines[edit.startLine]
            val s = edit.startChar.coerceIn(0, line.length)
            val e = edit.endChar.coerceIn(0, line.length)
            lines[edit.startLine] = line.substring(0, s) + edit.newText + line.substring(e)
        } else {
            // Multi-line edit — replace from start to end
            val firstLine = lines[edit.startLine]
            val lastLine = if (edit.endLine < lines.size) lines[edit.endLine] else ""
            val before = firstLine.substring(0, edit.startChar.coerceIn(0, firstLine.length))
            val after = if (edit.endLine < lines.size) lastLine.substring(edit.endChar.coerceIn(0, lastLine.length)) else ""
            val replacement = before + edit.newText + after
            // Remove lines from startLine to endLine, insert replacement
            val newLines = lines.subList(0, edit.startLine) + replacement.split("\n") + lines.subList(minOf(edit.endLine + 1, lines.size), lines.size)
            lines.clear()
            lines.addAll(newLines)
        }
    }
    return lines.joinToString("\n")
}

private data class TextEdit(
    val startLine: Int,
    val startChar: Int,
    val endLine: Int,
    val endChar: Int,
    val newText: String,
)
