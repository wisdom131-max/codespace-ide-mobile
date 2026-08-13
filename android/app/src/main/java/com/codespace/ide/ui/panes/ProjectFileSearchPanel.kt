package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.History

// ── Palette colours (VS Code-like dark) ─────────────────────────────────────
private val SBg     = Color(0xFF1E1E1E)
private val SCard   = Color(0xFF252526)
private val SText   = Color(0xFFD4D4D4)
private val SDim    = Color(0xFF808080)
private val SAccent = Color(0xFF007ACC)
private val SDivider= Color(0xFF3C3C3C)

// ── Result types ─────────────────────────────────────────────────────────────
data class FileResult(val path: String, val relativePath: String)
data class TextResult(
    val file: FileResult,
    val lineNumber: Int,   // 1-based
    val lineText: String,
    val matchStart: Int,
    val matchEnd: Int,
)

/**
 * P15-E — Project-wide search panel.
 *
 * Mode 1 (Ctrl+P equivalent) — file name search:
 *   Type any part of a filename → instant fuzzy results across the project root.
 *   Tap a result → [onOpenFile] called with the absolute path.
 *
 * Mode 2 (Ctrl+Shift+F equivalent) — full-text search:
 *   Toggle with the "Text" chip → searches file contents for the query.
 *   Results show the matching line with line number. Tap → [onOpenFileAtLine].
 *
 * @param projectRoot  Absolute path to the project root directory.
 * @param onDismiss    Close the panel.
 * @param onOpenFile   Open a file by absolute path.
 * @param onOpenFileAtLine  Open a file and scroll to a specific 1-based line.
 */
@Composable
fun ProjectFileSearchPanel(
    projectRoot: String,
    onDismiss: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenFileAtLine: (path: String, line: Int) -> Unit = { path, _ -> onOpenFile(path) },
    initialTextMode: Boolean = false,
    initialQuery: String = "",
) {
    var query by remember { mutableStateOf(initialQuery) }
    var textMode by remember { mutableStateOf(initialTextMode) }   // false = filename, true = full-text
    var fileResults by remember { mutableStateOf<List<FileResult>>(emptyList()) }
    var textResults by remember { mutableStateOf<List<TextResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // P18-A — Replace in files
    var replaceMode   by remember { mutableStateOf(false) }
    var replaceQuery  by remember { mutableStateOf("") }
    var replacing     by remember { mutableStateOf(false) }
    var replaceCount  by remember { mutableStateOf(0) }
    val snackState    = remember { SnackbarHostState() }
    val scope         = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // P45-G3: Include/Exclude file patterns
    var showFilters   by remember { mutableStateOf(false) }
    var includePattern by remember { mutableStateOf("") }
    var excludePattern by remember { mutableStateOf("") }
    var useCaseSensitive by remember { mutableStateOf(false) }

    // P45-G3: Recent search history
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("search_history", Context.MODE_PRIVATE) }
    var recentSearches by remember { mutableStateOf(prefs.getString("recent_text", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()) }
    var recentFileSearches by remember { mutableStateOf(prefs.getString("recent_files", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()) }

    fun saveRecentSearch(q: String) {
        if (q.isBlank()) return
        val key = if (textMode) "recent_text" else "recent_files"
        val current = prefs.getString(key, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        val updated = (listOf(q) + current.filter { it != q }).take(10)
        prefs.edit().putString(key, updated.joinToString("\n")).apply()
        if (textMode) recentSearches = updated else recentFileSearches = updated
    }

    // ── File index — collected once (or when root changes) ─────────────────
    fun matchesGlob(pattern: String, path: String): Boolean {
        if (pattern.isBlank()) return true
        return pattern.split(",").any { p ->
            val trimmed = p.trim()
            if (trimmed.isEmpty()) false
            else {
                val regex = trimmed.replace(".", "\\.").replace("*", ".*").replace("?", ".")
                Regex(regex, RegexOption.IGNORE_CASE).matches(path)
            }
        }
    }

    val allFiles = remember(projectRoot, includePattern, excludePattern) {
        mutableListOf<FileResult>().also { list ->
            val root = File(projectRoot)
            if (root.exists()) {
                root.walkTopDown()
                    .filter { it.isFile && !it.path.contains("/.git/") && !it.path.contains("/build/") && !it.path.contains("/node_modules/") && !it.path.contains("/.gradle/") }
                    .filter { f ->
                        val rel = f.relativeTo(root).path
                        val incOk = matchesGlob(includePattern, rel)
                        val excOk = excludePattern.isBlank() || !matchesGlob(excludePattern, rel)
                        incOk && excOk
                    }
                    .take(5000)
                    .forEach { f ->
                        list.add(FileResult(f.absolutePath, f.relativeTo(root).path))
                    }
            }
        }.toList()
    }

    // ── Debounced search ────────────────────────────────────────────────────
    LaunchedEffect(query, textMode, useCaseSensitive, includePattern, excludePattern) {
        if (query.isBlank()) {
            fileResults = emptyList(); textResults = emptyList(); return@LaunchedEffect
        }
        delay(200L)
        searching = true
        if (!textMode) {
            val q = if (useCaseSensitive) query else query.lowercase()
            fileResults = withContext(Dispatchers.Default) {
                allFiles
                    .map { f ->
                        val target = if (useCaseSensitive) f.relativePath else f.relativePath.lowercase()
                        Pair(f, fuzzyScore(target, q))
                    }
                    .filter { it.second >= 0 }
                    .sortedByDescending { it.second }
                    .take(50)
                    .map { it.first }
            }
        } else {
            val q = if (useCaseSensitive) query else query.lowercase()
            textResults = withContext(Dispatchers.IO) {
                val results = mutableListOf<TextResult>()
                for (f in allFiles) {
                    if (results.size >= 200) break
                    try {
                        val lines = File(f.path).readLines(Charsets.UTF_8)
                        lines.forEachIndexed { idx, line ->
                            val target = if (useCaseSensitive) line else line.lowercase()
                            val col = target.indexOf(q)
                            if (col >= 0) {
                                results.add(TextResult(f, idx + 1, line.trim(), col, col + q.length))
                                if (results.size >= 200) return@forEachIndexed
                            }
                        }
                    } catch (_: Exception) { /* binary/unreadable */ }
                }
                results
            }
        }
        searching = false
        // TEST-48-FIX: Save search query to recent history when it produces results,
        // not just when a result is clicked. This ensures history persists even if
        // the user closes the search without clicking a result.
        if (query.isNotBlank() && (fileResults.isNotEmpty() || textResults.isNotEmpty())) {
            saveRecentSearch(query)
        }
    }

    // ── Full-screen scrim ───────────────────────────────────────────────────
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.80f)
                .align(Alignment.TopCenter)
                .padding(top = 48.dp)
                .background(SCard, RoundedCornerShape(8.dp))
                .clickable(enabled = false, onClick = {}),
        ) {
            // ── Header ────────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2D2D2D), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = SAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            if (textMode) "Search in files…" else "Go to file…",
                            color = SDim, fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SText,
                        unfocusedTextColor = SText,
                        focusedBorderColor = SAccent,
                        unfocusedBorderColor = SDivider,
                        cursorColor = SAccent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = SText,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                // Mode toggle chips
                Box(
                    Modifier
                        .background(
                            if (textMode) SAccent else Color(0xFF3C3C3C),
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { textMode = !textMode; if (!textMode) replaceMode = false }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Text", color = if (textMode) Color.White else SDim, fontSize = 11.sp) }
                Spacer(Modifier.width(4.dp))
                Box(
                    Modifier
                        .background(
                            if (replaceMode) Color(0xFFE8A838) else Color(0xFF3C3C3C),
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { if (!textMode) textMode = true; replaceMode = !replaceMode }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Replace", color = if (replaceMode) Color(0xFF1E1E1E) else SDim, fontSize = 11.sp) }
                Spacer(Modifier.width(4.dp))
                // P45-G3: Filter toggle button
                IconButton(onClick = { showFilters = !showFilters }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.FilterAlt, null,
                        tint = if (showFilters || includePattern.isNotBlank() || excludePattern.isNotBlank()) SAccent else SDim,
                        modifier = Modifier.size(16.dp),
                    )
                }
                // P45-G3: Case sensitive toggle
                Box(
                    Modifier
                        .background(
                            if (useCaseSensitive) SAccent else Color(0xFF3C3C3C),
                            RoundedCornerShape(4.dp),
                        )
                        .clickable { useCaseSensitive = !useCaseSensitive }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("Aa", color = if (useCaseSensitive) Color.White else SDim, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = SDim, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = SDivider)

            // P45-G3: Include/Exclude filter panel
            if (showFilters) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OutlinedTextField(
                        value = includePattern,
                        onValueChange = { includePattern = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Include: *.kt, *.java", color = SDim, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SText, unfocusedTextColor = SText,
                            focusedBorderColor = SAccent, unfocusedBorderColor = SDivider,
                            cursorColor = SAccent,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SText),
                    )
                    OutlinedTextField(
                        value = excludePattern,
                        onValueChange = { excludePattern = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Exclude: *.min.js, test/*", color = SDim, fontSize = 11.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SText, unfocusedTextColor = SText,
                            focusedBorderColor = SAccent, unfocusedBorderColor = SDivider,
                            cursorColor = SAccent,
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = SText),
                    )
                }
                HorizontalDivider(color = SDivider)
            }

            // ── Replace field (P18-A) ──────────────────────────────────────
            if (replaceMode && textMode) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D2D))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.FindReplace, null, tint = Color(0xFFE8A838), modifier = Modifier.size(16.dp))
                    OutlinedTextField(
                        value = replaceQuery,
                        onValueChange = { replaceQuery = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Replace with…", color = SDim, fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SText, unfocusedTextColor = SText,
                            focusedBorderColor = Color(0xFFE8A838), unfocusedBorderColor = SDivider,
                            cursorColor = Color(0xFFE8A838),
                            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        ),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = SText),
                    )
                    val canReplace = textResults.isNotEmpty() && !replacing
                    TextButton(
                        onClick = {
                            scope.launch {
                                replacing = true
                                replaceCount = 0
                                val grouped = textResults.groupBy { it.file.path }
                                withContext(Dispatchers.IO) {
                                    grouped.forEach { (path, results) ->
                                        val file = File(path)
                                        if (!file.exists() || !file.canWrite()) return@forEach
                                        var fileContent = file.readText()
                                        val pattern = try { Regex(Regex.escape(query)) } catch (_: Exception) { return@forEach }
                                        fileContent = pattern.replace(fileContent, replaceQuery)
                                        file.writeText(fileContent)
                                        replaceCount += results.size
                                    }
                                }
                                replacing = false
                                snackState.showSnackbar("Replaced $replaceCount occurrence(s) in ${grouped.size} file(s)")
                            }
                        },
                        enabled = canReplace,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (replacing) "Replacing…" else "Replace All (${textResults.size})",
                            color = if (canReplace) Color(0xFFE8A838) else SDim,
                            fontSize = 11.sp,
                        )
                    }
                }
                HorizontalDivider(color = SDivider)
            }

            // ── Results ────────────────────────────────────────────────────
            Box(Modifier.fillMaxSize()) {
                when {
                    query.isBlank() -> {
                        val recents = if (textMode) recentSearches else recentFileSearches
                        if (recents.isNotEmpty()) {
                            Column(Modifier.fillMaxSize().padding(12.dp)) {
                                Text(
                                    "Recent Searches",
                                    color = SDim, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                recents.forEach { recent ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                query = recent
                                                saveRecentSearch(recent)
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Default.History, null, tint = SDim, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(recent, color = SText, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    HorizontalDivider(color = SDivider, thickness = 0.5.dp)
                                }
                            }
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    if (textMode) "Type to search file contents" else "Type to search by filename",
                                    color = SDim, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                    searching -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = SAccent, modifier = Modifier.size(24.dp))
                        }
                    }
                    !textMode -> {
                        if (fileResults.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No files match \"$query\"", color = SDim, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(fileResults) { f ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { saveRecentSearch(query); onOpenFile(f.path); onDismiss() }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        val parts = f.relativePath.split("/")
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                parts.last(),
                                                color = SText, fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            )
                                            if (parts.size > 1) {
                                                Text(
                                                    parts.dropLast(1).joinToString("/"),
                                                    color = SDim, fontSize = 10.sp,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = SDivider, thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                    else -> {
                        if (textResults.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No matches for \"$query\"", color = SDim, fontSize = 12.sp)
                            }
                        } else {
                            // P45-G3: Group results by file
                            val grouped = remember(textResults) {
                                textResults.groupBy { it.file }.toList()
                            }
                            var expandedFiles by remember { mutableStateOf(setOf<String>()) }
                            LazyColumn(Modifier.fillMaxSize()) {
                                grouped.forEach { (file, results) ->
                                    val isExpanded = expandedFiles.contains(file.path)
                                    item(key = "header_" + file.path) {
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    expandedFiles = if (isExpanded)
                                                        expandedFiles - file.path
                                                    else
                                                        expandedFiles + file.path
                                                }
                                                .background(if (isExpanded) Color(0xFF2D2D2D) else Color.Transparent)
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                if (isExpanded) "▼" else "▶",
                                                color = SDim, fontSize = 10.sp,
                                                modifier = Modifier.width(14.dp),
                                            )
                                            Text(
                                                file.relativePath.split("/").last(),
                                                color = SText, fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                file.relativePath.split("/").dropLast(1).joinToString("/"),
                                                color = SDim, fontSize = 10.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                results.size.toString(),
                                                color = SAccent, fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.background(
                                                    Color(0xFF007ACC).copy(alpha = 0.15f),
                                                    RoundedCornerShape(8.dp),
                                                ).padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                        HorizontalDivider(color = SDivider, thickness = 0.5.dp)
                                    }
                                    if (isExpanded) {
                                        items(results) { r ->
                                            Column(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        saveRecentSearch(query)
                                                        onOpenFileAtLine(r.file.path, r.lineNumber)
                                                        onDismiss()
                                                    }
                                                    .padding(start = 32.dp, top = 6.dp, end = 16.dp, bottom = 6.dp),
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        ":" + r.lineNumber,
                                                        color = SDim, fontSize = 11.sp,
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                    Text(
                                                        r.lineText.take(120),
                                                        color = Color(0xFF9CDCFE),
                                                        fontSize = 11.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                item {
                                    if (textResults.size >= 200) {
                                        Text(
                                            "Showing first 200 matches — narrow your query for more",
                                            color = SDim, fontSize = 10.sp,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // N11 FIX (2026-08-10): requestFocus() on Unit runs before layout completes, so it silently
    // fails. Adding a frame delay gives Compose time to attach the FocusRequester to the
    // laid-out TextField. Also explicitly show the software keyboard after focusing.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
        keyboardController?.show()
    }

    // Snackbar for replace feedback
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(hostState = snackState)
    }
}

// ── Fuzzy score helper ────────────────────────────────────────────────────────
// Returns -1 if no match; higher = better match.
// Consecutive runs of matching characters score higher than scattered ones.
private fun fuzzyScore(haystack: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    var score = 0
    var hi = 0
    var ni = 0
    var consecutive = 0
    while (hi < haystack.length && ni < needle.length) {
        if (haystack[hi] == needle[ni]) {
            consecutive++
            score += 10 + consecutive * 5  // reward runs
            ni++
        } else {
            consecutive = 0
        }
        hi++
    }
    return if (ni == needle.length) score else -1
}
