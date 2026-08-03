package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspManager
import com.codespace.ide.lsp.DocumentSymbolCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * P37-2: Outline panel — renders LSP document symbols as a tree.
 * Shows classes, functions, methods, etc. from the active file's LSP server.
 * Tap-to-navigate jumps the editor to that symbol's line.
 * Falls back to a basic regex-based symbol scan when no LSP server is running.
 */
@Composable
fun OutlinePanel(
    filePath: String,
    onNavigate: (line: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var symbols by remember { mutableStateOf<List<OutlineSymbol>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var usedLsp by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }

    val context = androidx.compose.ui.platform.LocalContext.current

    // Fetch document symbols whenever the file path changes
    LaunchedEffect(filePath) {
        if (filePath.isBlank()) {
            symbols = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val lang = Language.fromPath(filePath)
        if (LspManager.isServerRunning(lang) && filePath.startsWith("/")) {
            // P37-3fix: Check shared cache first (EditorPane may have already fetched)
            val cached = DocumentSymbolCache.get(filePath)
            if (cached != null && cached.length() > 0) {
                symbols = parseDocumentSymbols(cached)
                usedLsp = true
                loading = false
                return@LaunchedEffect
            }
            // Cache miss — fetch independently
            val uri = LspManager.fileUriFromHostPath(context, filePath)
            if (uri != null) {
                val result = withContext(Dispatchers.IO) {
                    try {
                        delay(300) // small debounce
                        LspManager.getDocumentSymbol(lang, uri)
                    } catch (_: Exception) { null }
                }
                if (result != null && result.length() > 0) {
                    symbols = parseDocumentSymbols(result)
                    usedLsp = true
                    // Write to cache for any future OutlinePanel opens
                    DocumentSymbolCache.put(filePath, result)
                    loading = false
                    return@LaunchedEffect
                }
            }
        }
        // Fallback: regex-based symbol extraction
        val fallback = withContext(Dispatchers.IO) {
            try {
                extractSymbolsFromText(java.io.File(filePath).readText(), lang)
            } catch (_: Exception) { emptyList() }
        }
        symbols = fallback
        usedLsp = false
        loading = false
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFFF3F3F3))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("OUTLINE", fontSize = 11.sp, color = Color(0xFF6B6B6B), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            // LSP/Fallback badge
            Box(
                Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    .background(if (usedLsp) Color(0xFF4EC9B0) else Color(0xFFCC7832), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    if (usedLsp) "LSP" else "Fallback",
                    color = Color(0xFF1E1E1E),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = Color(0xFF007ACC))
            }
        } else if (filePath.isBlank()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("Open a file to see its outline.", fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
        } else if (symbols.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text("No symbols found in ${filePath.substringAfterLast('/')}.", fontSize = 12.sp, color = Color(0xFF9E9E9E))
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(symbols) { sym ->
                    SymbolRow(sym, expanded, onExpand = { key ->
                        expanded = if (key in expanded) expanded - key else expanded + key
                    }, onNavigate = { onNavigate(sym.line) })
                }
            }
        }
    }
}

data class OutlineSymbol(
    val name: String,
    val kind: String,
    val line: Int,        // 0-based
    val children: List<OutlineSymbol>,
)

// LSP SymbolKind constants -> human-readable labels
private fun symbolKindName(kind: Int): String = when (kind) {
    1 -> "Module"; 2 -> "Class"; 3 -> "Interface"; 4 -> "Enum"
    5 -> "Function"; 6 -> "Variable"; 7 -> "Field"; 8 -> "Property"
    9 -> "Method"; 10 -> "Constructor"; 11 -> "EnumMember"
    12 -> "Struct"; 13 -> "Event"; 14 -> "Operator"; 15 -> "TypeParameter"
    16 -> "Constant"; 17 -> "String"; 18 -> "Number"; 19 -> "Boolean"
    20 -> "Array"; 21 -> "Object"; 22 -> "Key"; 23 -> "Null"
    24 -> "Namespace"; 25 -> "Package"; 26 -> "TypeAlias"
    else -> "Symbol"
}

private fun parseDocumentSymbols(arr: JSONArray): List<OutlineSymbol> {
    val result = mutableListOf<OutlineSymbol>()
    for (i in 0 until arr.length()) {
        val entry = arr.optJSONObject(i) ?: continue
        val sym = parseSymbolEntry(entry)
        if (sym != null) result.add(sym)
    }
    return result
}

private fun parseSymbolEntry(entry: JSONObject): OutlineSymbol? {
    return try {
        val name = entry.optString("name", "")
        val kind = symbolKindName(entry.optInt("kind", 0))
        val range = entry.optJSONObject("range") ?: return null
        val start = range.optJSONObject("start") ?: return null
        val line = start.optInt("line", 0)

        val childrenArr = entry.optJSONArray("children")
        val children = if (childrenArr != null && childrenArr.length() > 0) {
            (0 until childrenArr.length()).mapNotNull { j ->
                childrenArr.optJSONObject(j)?.let { parseSymbolEntry(it) }
            }
        } else emptyList()

        OutlineSymbol(name = name, kind = kind, line = line, children = children)
    } catch (_: Exception) { null }
}

// Fallback: regex-based symbol extraction for common languages
private fun extractSymbolsFromText(text: String, lang: Language): List<OutlineSymbol> {
    val symbols = mutableListOf<OutlineSymbol>()
    val patterns = when (lang) {
        Language.KOTLIN, Language.JAVA -> listOf(
            Pair("""^\s*(?:public|private|protected|internal|static|final|open|abstract|override|companion|data|sealed|enum)\s+.*?\s+class\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Class"),
            Pair("""^\s*(?:public|private|protected|internal|static|final|open|abstract|override|suspend|inline)\s+.*?\s+fun\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Function"),
            Pair("""^\s*(?:public|private|protected|internal|static|final)\s+.*?\s+object\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Object"),
            Pair("""^\s*interface\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Interface"),
        )
        Language.PYTHON -> listOf(
            Pair("""^\s*class\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Class"),
            Pair("""^\s*def\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Function"),
        )
        Language.JAVASCRIPT, Language.TYPESCRIPT -> listOf(
            Pair("""^\s*(?:export\s+)?(?:default\s+)?class\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Class"),
            Pair("""^\s*(?:export\s+)?(?:async\s+)?function\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Function"),
            Pair("""^\s*(?:export\s+)?interface\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Interface"),
        )
        Language.C, Language.CPP -> listOf(
            Pair("""^\s*(?:class|struct)\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Class"),
            Pair("""^\s*(?:[\w:*&]+\s+)+(\w+)\s*\(""".toRegex(RegexOption.MULTILINE), "Function"),
        )
        else -> listOf(
            Pair("""^\s*function\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Function"),
            Pair("""^\s*class\s+(\w+)""".toRegex(RegexOption.MULTILINE), "Class"),
        )
    }
    for ((pattern, kind) in patterns) {
        pattern.findAll(text).forEach { match ->
            val name = match.groupValues.getOrNull(1) ?: return@forEach
            val line = text.take(match.range.first).count { it == '\n' }
            symbols.add(OutlineSymbol(name = name, kind = kind, line = line, children = emptyList()))
        }
    }
    return symbols.sortedBy { it.line }
}

@Composable
private fun SymbolRow(
    sym: OutlineSymbol,
    expanded: Set<String>,
    onExpand: (String) -> Unit,
    onNavigate: () -> Unit,
) {
    val key = "${sym.kind}:${sym.name}:${sym.line}"
    val hasChildren = sym.children.isNotEmpty()
    val isExpanded = key in expanded

    Column {
        Row(
            Modifier.fillMaxWidth()
                .clickable { if (hasChildren) onExpand(key); onNavigate() }
                .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasChildren) {
                Icon(
                    if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                    null,
                    tint = Color(0xFF9E9E9E),
                    modifier = Modifier.size(14.dp),
                )
            } else {
                Spacer(Modifier.width(14.dp))
            }
            // Kind icon — a small colored letter
            val kindColor = when (sym.kind) {
                "Class", "Object", "Interface", "Struct" -> Color(0xFF4EC9B0)
                "Function", "Method", "Constructor" -> Color(0xFFDCDCAA)
                "Variable", "Field", "Property" -> Color(0xFF9CDCFE)
                "Constant" -> Color(0xFF4FC1FF)
                "Enum", "EnumMember" -> Color(0xFFC586C0)
                "Namespace", "Module", "Package" -> Color(0xFFD4D4D4)
                else -> Color(0xFF808080)
            }
            Box(
                Modifier.size(16.dp)
                    .background(kindColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(sym.kind.first().toString(), fontSize = 8.sp, color = kindColor, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                sym.name,
                fontSize = 12.sp,
                color = Color(0xFF424242),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${sym.line + 1}",
                fontSize = 10.sp,
                color = Color(0xFF9E9E9E),
            )
        }

        if (hasChildren && isExpanded) {
            sym.children.forEach { child ->
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { onNavigate() }
                        .padding(start = 28.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val childColor = when (child.kind) {
                        "Class", "Object", "Interface", "Struct" -> Color(0xFF4EC9B0)
                        "Function", "Method", "Constructor" -> Color(0xFFDCDCAA)
                        "Variable", "Field", "Property" -> Color(0xFF9CDCFE)
                        "Constant" -> Color(0xFF4FC1FF)
                        else -> Color(0xFF808080)
                    }
                    Box(
                        Modifier.size(14.dp)
                            .background(childColor.copy(alpha = 0.15f), RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(child.kind.first().toString(), fontSize = 7.sp, color = childColor, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        child.name,
                        fontSize = 11.sp,
                        color = Color(0xFF6B6B6B),
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${child.line + 1}",
                        fontSize = 9.sp,
                        color = Color(0xFF9E9E9E),
                    )
                }
            }
        }
    }
}
