package com.codespace.ide.ui.panes

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.codespace.ide.editor.FileCache
import com.codespace.ide.editor.MergeConflictParser
import com.codespace.ide.editor.ConflictHunk
import com.codespace.ide.editor.ConflictResolution
import com.codespace.ide.editor.DocumentFormatter
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.lsp.parseHoverContent
import com.codespace.ide.lsp.parseLspCompletions
import com.codespace.ide.lsp.parseImportEdits
import com.codespace.ide.lsp.parseCodeActions
import androidx.compose.ui.zIndex
import java.io.File
import com.codespace.ide.R
import com.codespace.ide.data.SessionStateStore
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.GlobalScope
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
    var lspCursorLine by remember { mutableStateOf(0) }
    var lspCursorCol by remember { mutableStateOf(0) }
    var showLspHover by remember { mutableStateOf(false) }
    var lspHoverContent by remember { mutableStateOf<String?>(null) }
    var splitId by remember { mutableStateOf<String?>(null) }
    // P2-9 Bookmarks: path → set of bookmarked line indices
    val fileBookmarks = remember { mutableStateMapOf<String, Set<Int>>() }
    // P8-1 Breakpoints: path → set of breakpoint line indices (0-based)
    val fileBreakpoints = remember { mutableStateMapOf<String, Set<Int>>() }
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
                        val name = File(tab.path).name
                        File(dir, "$name.autosave").writeText(tab.content)
                    } catch (_: Exception) {}
                }
                // Remove autosave files for tabs that are now clean (saved or closed)
                val activeNames = tabs.map { File(it.path).name + ".autosave" }.toSet()
                dir.listFiles()?.forEach { f -> if (f.name !in activeNames) f.delete() }
            }
        }
    }

    // ── Autosave restore: offer recovery dialog on first open if stale saves exist ──
    var showAutosaveRestoreDialog by remember { mutableStateOf(false) }
    var autosaveFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(projectId) {
        val dir = autosaveDir ?: return@LaunchedEffect
        delay(1_500L) // let normal session restore settle first
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
                                "• ${f.name.removeSuffix(".autosave")}",
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
                                        tabs.remove(tab)
                                        if (activeId == tab.id) {
                                            activeId = tabs.getOrNull(idx - 1)?.id ?: tabs.firstOrNull()?.id
                                        }
                                        if (splitId == tab.id) splitId = null
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
                // P22-E: Format Document button
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
                                }
                                formatting = false
                            }
                        }
                    },
                    modifier = Modifier.size(35.dp)
                ) {
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
                                Text("${'$'}fileName : ${'$'}{lineIdx + 1}", fontSize = 11.sp, color = Color(0xFF61AFEF), fontWeight = FontWeight.Bold)
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

        // P22-G: LSP server lifecycle + document sync
        LaunchedEffect(active?.id, active?.content, active?.language) {
            if (active != null && LspManager.isSupported(active.language) && projectRootPath != null) {
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    if (!LspManager.isServerRunning(active.language)) {
                        withContext(Dispatchers.IO) {
                            LspManager.startServer(context, active.language, projectRootPath)
                        }
                    }
                    if (LspManager.isServerRunning(active.language)) {
                        delay(500)
                        if (lspOpenedFiles[active.path] != true) {
                            LspManager.didOpen(active.language, uri, LspManager.languageId(active.language), active.content)
                            lspOpenedFiles[active.path] = true
                        } else {
                            val version = (System.currentTimeMillis() and 0x7FFFFFFFL).toInt()
                            LspManager.didChange(active.language, uri, active.content, version)
                        }
                    }
                }
            }
        }
        // P22-G: LSP hover on cursor position change (debounced)
        LaunchedEffect(lspCursorLine, lspCursorCol, showLspHover) {
            if (showLspHover && active != null && LspManager.isServerRunning(active.language)) {
                delay(300)
                val uri = LspManager.fileUriFromHostPath(context, active.path)
                if (uri != null) {
                    val hover = withContext(Dispatchers.IO) {
                        LspManager.getHover(active.language, uri, lspCursorLine, lspCursorCol)
                    }
                    lspHoverContent = hover?.let { parseHoverContent(it) }
                }
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

        // P24: Breadcrumbs — file path hierarchy above editor
        if (active != null) {
            val pathParts = active.path.split("/").filter { it.isNotBlank() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E).copy(alpha = 0.6f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pathParts.forEachIndexed { idx, part ->
                    if (idx > 0) {
                        Text(" > ", fontSize = 10.sp, color = Color(0xFF6B6B6B), fontFamily = FontFamily.Monospace)
                    }
                    val isLast = idx == pathParts.lastIndex
                    Text(
                        text = part,
                        fontSize = 10.sp,
                        color = if (isLast) Color(0xFFE0E0E0) else Color(0xFF888888),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // P24: Auto-save indicator
                if (showSavedIndicator) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Saved",
                        fontSize = 9.sp,
                        color = Color(0xFF4EC9B0),
                        fontFamily = FontFamily.Monospace,
                    )
                }
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
                                            val raw = com.codespace.ide.terminal.ProotInstaller.execOnce(context, "git blame --line-porcelain '$fileName' 2>/dev/null", guestPath)
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
                                            val raw = com.codespace.ide.terminal.ProotInstaller.execOnce(context, "git blame --line-porcelain '$fileName' 2>/dev/null", guestPath)
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
                            // P24: Show "Saved" indicator briefly + clear dirty flag
                            showSavedIndicator = true
                            savedIndicatorJob?.cancel()
                            savedIndicatorJob = kotlinx.coroutines.GlobalScope.launch {
                                kotlinx.coroutines.delay(2000)
                                showSavedIndicator = false
                                val idx2 = tabs.indexOfFirst { it.id == active.id }
                                if (idx2 >= 0) tabs[idx2] = tabs[idx2].copy(isDirty = false)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        wordWrap = wordWrap,
                        showInlayHints = showInlayHints,
                        scrollToLine = scrollToLine,
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
                        // P24: Quick fixes — LSP code actions for the current line
                        lspCodeActionProvider = if (LspManager.isServerRunning(active.language)) {
                            { line ->
                                val uri = LspManager.fileUriFromHostPath(context, active.path)
                                if (uri != null) {
                                    val col = 0
                                    val actions = LspManager.getCodeActions(active.language, uri, line, col)
                                    actions?.let { parseCodeActions(it) } ?: emptyList()
                                } else emptyList()
                            }
                        } else null,
                    )
                }
                // P22-G: LSP hover popup
                if (showLspHover && lspHoverContent != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .background(Color(0xFF2D2D2D))
                            .zIndex(10f)
                    ) {
                        Text(
                            text = lspHoverContent ?: "",
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(12.dp),
                            maxLines = 10,
                            overflow = TextOverflow.Ellipsis,
                        )
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
