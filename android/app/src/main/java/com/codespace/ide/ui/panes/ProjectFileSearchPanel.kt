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
) {
    var query by remember { mutableStateOf("") }
    var textMode by remember { mutableStateOf(false) }   // false = filename, true = full-text
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

    // ── File index — collected once (or when root changes) ─────────────────
    val allFiles = remember(projectRoot) {
        mutableListOf<FileResult>().also { list ->
            val root = File(projectRoot)
            if (root.exists()) {
                root.walkTopDown()
                    .filter { it.isFile && !it.path.contains("/.git/") && !it.path.contains("/build/") && !it.path.contains("/node_modules/") && !it.path.contains("/.gradle/") }
                    .take(5000)
                    .forEach { f ->
                        list.add(FileResult(f.absolutePath, f.relativeTo(root).path))
                    }
            }
        }.toList()
    }

    // ── Debounced search ────────────────────────────────────────────────────
    LaunchedEffect(query, textMode) {
        if (query.isBlank()) {
            fileResults = emptyList(); textResults = emptyList(); return@LaunchedEffect
        }
        delay(200L)
        searching = true
        if (!textMode) {
            // Filename fuzzy match — score by consecutive-char match
            val q = query.lowercase()
            fileResults = withContext(Dispatchers.Default) {
                allFiles
                    .map { f -> Pair(f, fuzzyScore(f.relativePath.lowercase(), q)) }
                    .filter { it.second >= 0 }
                    .sortedByDescending { it.second }
                    .take(50)
                    .map { it.first }
            }
        } else {
            // Full-text search
            val q = query.lowercase()
            textResults = withContext(Dispatchers.IO) {
                val results = mutableListOf<TextResult>()
                for (f in allFiles) {
                    if (results.size >= 200) break
                    try {
                        val lines = File(f.path).readLines(Charsets.UTF_8)
                        lines.forEachIndexed { idx, line ->
                            val col = line.lowercase().indexOf(q)
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
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, tint = SDim, modifier = Modifier.size(16.dp))
                }
            }

            HorizontalDivider(color = SDivider)

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
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (textMode) "Type to search file contents" else "Type to search by filename",
                                color = SDim, fontSize = 12.sp,
                            )
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
                                            .clickable { onOpenFile(f.path); onDismiss() }
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
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(textResults) { r ->
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onOpenFileAtLine(r.file.path, r.lineNumber)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                    ) {
                                        // File name + line number
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                r.file.relativePath.split("/").last(),
                                                color = SText, fontSize = 12.sp,
                                                fontWeight = FontWeight.Medium,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                ":${r.lineNumber}",
                                                color = SDim, fontSize = 11.sp,
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                r.file.relativePath.split("/").dropLast(1).joinToString("/"),
                                                color = SDim, fontSize = 10.sp,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                        // Matching line preview
                                        Text(
                                            r.lineText.take(120),
                                            color = Color(0xFF9CDCFE),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    HorizontalDivider(color = SDivider, thickness = 0.5.dp)
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

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
