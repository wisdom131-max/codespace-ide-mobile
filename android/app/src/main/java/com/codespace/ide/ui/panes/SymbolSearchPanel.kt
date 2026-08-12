package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.editor.FileIndexer
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * P9-1: Symbol Search overlay — VS Code "Go to Symbol in Workspace" (Ctrl+T).
 * Shows a floating search box at the top of the editor; typing filters
 * the workspace symbol index and tapping a result jumps to that file+line.
 *
 * P37-4: Now queries LSP workspace/symbol alongside FileIndexer regex.
 * Shows LSP/Fallback badge like other LSP features.
 */
@Composable
fun SymbolSearchPanel(
    onNavigate: (filePath: String, line: Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    activeFilePath: String? = null,
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // STABILITY-FIX: requestFocus() can throw "ActiveParent with no focused
        // child" — known Compose Foundation focus-system race when another
        // field/dialog released focus in the same frame this one requests it.
        try { focusRequester.requestFocus() } catch (_: IllegalArgumentException) {}
        keyboardController?.show()
    }

    val indexerState = remember { mutableStateOf(FileIndexer.getState()) }
    LaunchedEffect(Unit) {
        while (true) {
            indexerState.value = FileIndexer.getState()
            delay(500)
        }
    }

    // Regex results from FileIndexer (always available)
    val regexResults = remember(query) { FileIndexer.search(query) }

    // LSP results — query workspace symbols if an LSP server is running
    var lspResults by remember { mutableStateOf<List<LspSym>>(emptyList()) }
    var usedLsp by remember { mutableStateOf(false) }

    val activeLang = remember(activeFilePath) {
        activeFilePath?.let { Language.fromPath(it) }
    }
    val lspRunning = remember(activeLang) {
        activeLang != null && LspManager.isServerRunning(activeLang)
    }
    val lspInitialized = remember(activeLang) {
        activeLang != null && LspManager.isServerInitialized(activeLang)
    }

    LaunchedEffect(query, lspInitialized) {
        if (query.isBlank() || !lspInitialized || activeLang == null) {
            lspResults = emptyList()
            usedLsp = false
            return@LaunchedEffect
        }
        delay(300) // debounce
        try {
            val symbols = withContext(Dispatchers.IO) {
                LspManager.getWorkspaceSymbol(activeLang, query)
            }
            if (symbols != null && symbols.length() > 0) {
                val parsed = mutableListOf<LspSym>()
                for (i in 0 until symbols.length()) {
                    val sym = symbols.optJSONObject(i) ?: continue
                    val name = if (sym.has("name") && !sym.isNull("name")) sym.getString("name") else continue
                    val loc = sym.optJSONObject("location")
                    if (loc == null) continue
                    val symUri = if (loc.has("uri") && !loc.isNull("uri")) loc.getString("uri") else ""
                    val symLine = loc.optJSONObject("range")?.optJSONObject("start")?.optInt("line", 0) ?: 0
                    val symKind = sym.optInt("kind", 0)
                    val symPathRaw = if (symUri.startsWith("file://")) symUri.removePrefix("file://") else symUri
                    val symPath = try { java.net.URLDecoder.decode(symPathRaw, "UTF-8") } catch (_: Exception) { symPathRaw }
                    val symFile = symPath.substringAfterLast("/")
                    parsed.add(LspSym(name, symPath, symFile, symLine, symKind))
                }
                lspResults = parsed
                usedLsp = true
            } else {
                lspResults = emptyList()
                usedLsp = false
            }
        } catch (_: Exception) {
            lspResults = emptyList()
            usedLsp = false
        }
    }

    // Merge: LSP results first (if available), then regex results (deduplicated)
    val mergedResults = remember(lspResults, regexResults, usedLsp) {
        if (usedLsp && lspResults.isNotEmpty()) {
            val lspKeys = lspResults.map { it.filePath to it.line }.toSet()
            val regexExtra = regexResults.filter { (it.filePath to it.line) !in lspKeys }
            val merged = mutableListOf<MergedSym>()
            lspResults.forEach { merged.add(MergedSym(it.name, it.filePath, it.fileName, it.line + 1, lspKindLabel(it.kind), true)) }
            regexExtra.forEach { merged.add(MergedSym(it.name, it.filePath, it.fileName, it.line, it.kind, false)) }
            merged
        } else {
            regexResults.map { MergedSym(it.name, it.filePath, it.fileName, it.line, it.kind, false) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF252526), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(6.dp))
            .padding(8.dp),
    ) {
        // Search input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Default.Search, null, tint = Color(0xFF808080), modifier = Modifier.size(16.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search symbols in workspace...", fontSize = 12.sp, color = Color(0xFF808080)) },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFD4D4D4),
                ),
                trailingIcon = {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color(0xFF808080), modifier = Modifier.size(14.dp))
                    }
                },
            )
        }

        // LSP/Fallback badge + indexing status — only show after a search has run
        if (query.isNotBlank()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (usedLsp) {
                Box(
                    Modifier.background(Color(0xFF4EC9B0), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                ) { Text("LSP", color = Color(0xFF1E1E1E), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            } else if (lspRunning && !lspInitialized) {
                Box(
                    Modifier.background(Color(0xFFFAB387), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                ) { Text("Starting", color = Color(0xFF1E1E1E), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            } else {
                Box(
                    Modifier.background(Color(0xFFCC7832), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp, vertical = 1.dp)
                ) { Text("Fallback", color = Color(0xFF1E1E1E), fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
            if (indexerState.value.isIndexing) {
                Text(
                    "Indexing ${indexerState.value.indexedFiles}/${indexerState.value.totalFiles} files...",
                    color = Color(0xFF808080),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            } else if (indexerState.value.isComplete) {
                Text(
                    "${indexerState.value.totalSymbols} symbols indexed",
                    color = Color(0xFF6A9955),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        }

        // Results
        if (query.isNotBlank() && mergedResults.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
            ) {
                items(mergedResults) { sym ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigate(sym.filePath, sym.line); onDismiss() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val (label, tint) = when (sym.kind) {
                            "class"     -> "C" to Color(0xFF4EC9B0)
                            "function"  -> "f" to Color(0xFFDCDCAA)
                            "variable"  -> "v" to Color(0xFF9CDCFE)
                            "interface"  -> "I" to Color(0xFFB8D7A3)
                            "enum"      -> "E" to Color(0xFFB5CEA8)
                            else        -> "?" to Color(0xFF808080)
                        }
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .background(tint.copy(alpha = 0.2f), RoundedCornerShape(3.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, color = tint, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                        }
                        Text(
                            sym.name,
                            color = Color(0xFFD4D4D4),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            sym.fileName,
                            color = Color(0xFF808080),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                        Text(
                            "L${sym.line}",
                            color = Color(0xFF6A9955),
                            fontSize = 10.sp,
                        )
                    }
                }
            }
        } else if (query.isNotBlank() && mergedResults.isEmpty() && !indexerState.value.isIndexing) {
            Text(
                "No symbols found for '$query'",
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color(0xFF808080),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

// ── Data classes ──────────────────────────────────────────────────────

private data class LspSym(
    val name: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val kind: Int,
)

private data class MergedSym(
    val name: String,
    val filePath: String,
    val fileName: String,
    val line: Int,
    val kind: String,
    val fromLsp: Boolean,
)

private fun lspKindLabel(kind: Int): String = when (kind) {
    1, 2, 3, 4, 5 -> "class"
    6, 7, 8, 9 -> "class"
    10, 11 -> "interface"
    12 -> "function"
    13 -> "variable"
    14 -> "class"
    15, 16, 17 -> "variable"
    18 -> "variable"
    19 -> "class"
    20 -> "variable"
    21 -> "enum"
    22, 23, 24, 25 -> "function"
    else -> "?"
}
