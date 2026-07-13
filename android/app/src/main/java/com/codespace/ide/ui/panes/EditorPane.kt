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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.EditorTab
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.CodeEditor
import androidx.compose.ui.zIndex
import java.io.File
import com.codespace.ide.R
import com.codespace.ide.data.SessionStateStore
import androidx.compose.foundation.lazy.items

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
    File(path).readText()
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
) {
    val context = LocalContext.current
    val orientation = LocalConfiguration.current.orientation
    val tabs = remember { mutableStateListOf<EditorTab>() }
    var activeId by remember { mutableStateOf<String?>(null) }
    var splitId by remember { mutableStateOf<String?>(null) }
    // P2-9 Bookmarks: path → set of bookmarked line indices
    val fileBookmarks = remember { mutableStateMapOf<String, Set<Int>>() }
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
                try { File(active.path).writeText(newContent) } catch (_: Exception) {}
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
                IconButton(onClick = { goToLineOpen = !goToLineOpen }, modifier = Modifier.size(35.dp)) {
                    Icon(
                        androidx.compose.material.icons.Icons.Default.KeyboardArrowDown,
                        contentDescription = "Go to Line",
                        tint = if (goToLineOpen) androidx.compose.ui.graphics.Color(0xFF007ACC)
                               else androidx.compose.ui.graphics.Color(0xFF858585),
                        modifier = Modifier.size(18.dp),
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
                    CodeEditor(
                        content = active.content,
                        language = active.language,
                        fontSize = fontSize,
                        savedContent = active.savedContent,
                        onContentChange = { newText ->
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0) tabs[idx] = active.copy(content = newText, isDirty = true)
                            if (active.path.startsWith("/")) {
                                try { File(active.path).writeText(newText) } catch (_: Exception) {}
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
                    CodeEditor(
                        content = active.content,
                        language = active.language,
                        fontSize = fontSize,
                        savedContent = active.savedContent,
                        onContentChange = { newText ->
                            val idx = tabs.indexOfFirst { it.id == active.id }
                            if (idx >= 0) tabs[idx] = active.copy(content = newText, isDirty = true)
                            if (active.path.startsWith("/")) {
                                try { File(active.path).writeText(newText) } catch (_: Exception) {}
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
                    )
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
