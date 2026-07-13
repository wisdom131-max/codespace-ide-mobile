package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.LocalEditorColors
import kotlinx.coroutines.launch

private data class Completion(val label: String, val kind: CompletionKind)
private enum class CompletionKind { KEYWORD, TYPE, SNIPPET }

private fun completionsFor(prefix: String, lang: Language): List<Completion> {
    if (prefix.length < 2) return emptyList()
    val spec = LanguageSpecs.forLanguage(lang)
    val p = prefix.lowercase()
    val kw = spec.keywords.filter { it.startsWith(p) }.sorted().map { Completion(it, CompletionKind.KEYWORD) }
    val ty = spec.types.filter { it.lowercase().startsWith(p) }.sorted().map { Completion(it, CompletionKind.TYPE) }
    val snips = buildList {
        val snippets = mapOf(
            "func" to "function", "ret" to "return", "imp" to "import",
            "cla" to "class", "con" to "console.log", "asy" to "async",
            "def" to "def ", "pri" to "print(", "for" to "for",
        )
        snippets.forEach { (trigger, expand) ->
            if (trigger.startsWith(p) || expand.startsWith(p))
                add(Completion(expand, CompletionKind.SNIPPET))
        }
    }
    return (kw + ty + snips).distinctBy { it.label }.take(8)
}

private fun currentWord(text: String, cursor: Int): String {
    val end = cursor.coerceAtMost(text.length)
    var start = end
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    return text.substring(start, end)
}

@Composable
fun CodeEditor(
    content: String,
    language: Language,
    fontSize: Int = 13,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    savedContent: String = "",
    wordWrap: Boolean = false,
    scrollToLine: Int = 0,
) {
    val colors = LocalEditorColors.current
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // 2. Code folding state
    var foldedRanges by remember { mutableStateOf(setOf<Int>()) } // start line index (0-based)

    // Parse lines and folding
    val rawLines = remember(value.text) { value.text.split("\n") }
    
    // Determine which line indices are foldable
    val foldableLines = remember(rawLines) {
        val set = mutableSetOf<Int>()
        for (i in rawLines.indices) {
            val line = rawLines[i].trimEnd()
            if (line.endsWith("{") || line.endsWith("(") || line.endsWith("[") || line.endsWith(":")) {
                set.add(i)
            } else if (i < rawLines.lastIndex) {
                val currentIndent = rawLines[i].length - rawLines[i].trimStart().length
                val nextIndent = rawLines[i + 1].length - rawLines[i + 1].trimStart().length
                if (nextIndent > currentIndent && rawLines[i + 1].trim().isNotEmpty()) {
                    set.add(i)
                }
            }
        }
        set
    }

    // Determine the range of folded lines
    val foldedLineIndices = remember(foldedRanges, rawLines) {
        val set = mutableSetOf<Int>()
        for (startIdx in foldedRanges) {
            if (startIdx >= rawLines.size) continue
            val startIndent = rawLines[startIdx].length - rawLines[startIdx].trimStart().length
            var j = startIdx + 1
            while (j < rawLines.size) {
                val lineTrimmed = rawLines[j].trim()
                if (lineTrimmed.isEmpty()) {
                    set.add(j)
                    j++
                    continue
                }
                val indent = rawLines[j].length - rawLines[j].trimStart().length
                if (indent > startIndent) {
                    set.add(j)
                    j++
                } else {
                    break
                }
            }
        }
        set
    }

    // Line list to display in the gutter & editor
    val displayLines = remember(rawLines, foldedLineIndices) {
        val list = mutableListOf<Pair<Int, String>>() // Pair of (original 0-based line index, content)
        var i = 0
        while (i < rawLines.size) {
            if (foldedLineIndices.contains(i)) {
                // If this line is folded, skip it. If the previous wasn't folded or was the fold start, we can add a visual placeholder.
                // We add exactly one placeholder for a contiguous block of folded lines.
                val prevFolded = i > 0 && foldedLineIndices.contains(i - 1)
                if (!prevFolded) {
                    list.add(Pair(-1, "···"))
                }
                i++
            } else {
                list.add(Pair(i, rawLines[i]))
                i++
            }
        }
        list
    }

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    val prefix = remember(value) { currentWord(value.text, value.selection.end) }
    val completions = remember(prefix, language) { completionsFor(prefix, language) }
    var showCompletions by remember { mutableStateOf(false) }
    LaunchedEffect(prefix) { showCompletions = prefix.length >= 2 && completions.isNotEmpty() }

    // Bracket matching
    val bracketMatch = remember(value) {
        val pos = value.selection.end
        if (pos == 0 || pos > value.text.length) null
        else {
            val before = if (pos > 0) value.text[pos - 1] else null
            val at = if (pos < value.text.length) value.text[pos] else null
            val bracket = before ?: at
            val bracketPos = if (before != null && (bracket == '(' || bracket == ')' || bracket == '[' || bracket == ']' || bracket == '{' || bracket == '}')) pos - 1
                          else if (at != null && (bracket == '(' || bracket == ')' || bracket == '[' || bracket == ']' || bracket == '{' || bracket == '}')) pos
                          else -1
            if (bracketPos >= 0) {
                val match = when (bracket) {
                    '(' -> ')'; ')' -> '('; '[' -> ']'; ']' -> '['; '{' -> '}'; '}' -> '{'
                    else -> null
                }
                if (match != null) {
                    val dir = if (bracket == '(' || bracket == '[' || bracket == '{') 1 else -1
                    var depth = 0
                    var i = bracketPos
                    var found = -1
                    while (i >= 0 && i < value.text.length) {
                        val c = value.text[i]
                        if (c == bracket) depth++
                        else if (c == match) {
                            depth--
                            if (depth == 0) { found = i; break }
                        }
                        i += dir
                    }
                    if (found >= 0) Pair(bracketPos, found) else null
                } else null
            } else null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(end = 62.dp)
                .verticalScroll(vScroll)
        ) {
            // Gutter
            val savedLines = remember(savedContent) { savedContent.split("\n") }
            val currentLines = remember(value.text) { value.text.split("\n") }
            Column(modifier = Modifier.padding(horizontal = 4.dp).width(62.dp)) {
                displayLines.forEach { (lineNum, _) ->
                    if (lineNum == -1) {
                        // Visual placeholder row in gutter
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(fontSize.dp)
                        ) {
                            Spacer(Modifier.width(20.dp))
                            Text(
                                text = " ",
                                color = colors.gutter,
                                fontSize = fontSize.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    } else {
                        val isDirty = savedContent.isNotEmpty() && (
                            lineNum >= savedLines.size || (lineNum < currentLines.size && lineNum < savedLines.size && currentLines[lineNum] != savedLines[lineNum])
                        )
                        val isAdded = savedContent.isNotEmpty() && lineNum >= savedLines.size
                        val isFoldable = foldableLines.contains(lineNum)
                        val isFolded = foldedRanges.contains(lineNum)

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.height(fontSize.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(fontSize.dp)
                                    .background(
                                        when {
                                            isAdded -> Color(0xFF4EC9B0)
                                            isDirty -> Color(0xFF569CD6)
                                            else    -> Color.Transparent
                                        }
                                    )
                            )
                            Spacer(Modifier.width(1.dp))
                            // Gutter fold chevron icon (▼ when expanded, ▶ when folded)
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .fillMaxHeight()
                                    .clickable(enabled = isFoldable) {
                                        foldedRanges = if (isFolded) {
                                            foldedRanges - lineNum
                                        } else {
                                            foldedRanges + lineNum
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isFoldable) {
                                    Text(
                                        text = if (isFolded) "▶" else "▼",
                                        color = colors.gutter,
                                        fontSize = (fontSize - 3).sp,
                                    )
                                }
                            }
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = (lineNum + 1).toString(),
                                color = colors.gutter,
                                fontSize = fontSize.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
            // Editor surface
            Box(modifier = if (wordWrap) Modifier else Modifier.horizontalScroll(hScroll)) {
                BasicTextField(
                    value = value,
                    onValueChange = { newValue ->
                        var updatedValue = newValue
                        // 1. Auto-close brackets & quotes
                        if (newValue.text.length == value.text.length + 1) {
                            val cursor = newValue.selection.end
                            if (cursor > 0 && cursor <= newValue.text.length) {
                                val insertedChar = newValue.text[cursor - 1]
                                val closer = when (insertedChar) {
                                    '(' -> ')'
                                    '[' -> ']'
                                    '{' -> '}'
                                    '"' -> '"'
                                    '\'' -> '\''
                                    else -> null
                                }
                                if (closer != null) {
                                    val leftText = newValue.text.substring(0, cursor)
                                    val rightText = newValue.text.substring(cursor)
                                    updatedValue = TextFieldValue(
                                        text = leftText + closer + rightText,
                                        selection = androidx.compose.ui.text.TextRange(cursor)
                                    )
                                }
                            }
                        }
                        
                        value = updatedValue
                        onContentChange(updatedValue.text)
                    },
                    textStyle = LocalTextStyle.current.merge(
                        TextStyle(
                            color = colors.text,
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    ),
                    visualTransformation = SyntaxTransformation(language, colors),
                    modifier = Modifier.padding(end = 24.dp),
                )
            }
        }

        // Minimap
        val textLines = remember(value.text) { value.text.split("\n") }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(60.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A))
                .zIndex(5f),
        ) {
            textLines.forEachIndexed { idx, line ->
                val density = (line.trimStart().length.coerceAtMost(80)).toFloat() / 80f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 2.dp)
                        .clickable {
                            // 3. Minimap click-to-navigate
                            coroutineScope.launch {
                                val lineHeightPx = fontSize * 1.5f * 2.0f // Simple scale factor for density
                                vScroll.animateScrollTo((idx * lineHeightPx).toInt())
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val indent = line.length - line.trimStart().length
                    Spacer(Modifier.width((indent * 0.3f).dp))
                    Box(
                        Modifier
                            .weight(density.coerceAtLeast(0.05f))
                            .fillMaxHeight()
                            .background(Color(0xFF3C3C3C))
                    )
                    Spacer(Modifier.weight((1f - density).coerceAtLeast(0.05f)))
                }
            }
        }

        // Indentation guides
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 62.dp)
                .zIndex(1f),
        ) {
            val maxIndent = remember(value.text) {
                value.text.split("\n").maxOfOrNull { line ->
                    (line.length - line.trimStart().length) / 2
                } ?: 0
            }
            for (indent in 1..minOf(maxIndent, 10)) {
                Box(Modifier.width(2.dp).fillMaxHeight().padding(end = 10.dp))
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.gutter.copy(alpha = 0.15f)))
                Spacer(Modifier.width(11.dp))
            }
        }

        // IntelliSense dropdown
        if (showCompletions && completions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 64.dp, top = ((value.text.take(value.selection.end).count { it == '\n' } + 1) * fontSize * 1.25f).dp)
                    .widthIn(min = 160.dp, max = 260.dp)
                    .heightIn(max = 200.dp)
                    .zIndex(10f)
                    .background(Color(0xFF252526), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(4.dp)),
            ) {
                items(completions) { comp ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val cursor = value.selection.end
                                val text = value.text
                                val end = cursor.coerceAtMost(text.length)
                                var start = end
                                while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
                                val newText = text.substring(0, start) + comp.label + text.substring(end)
                                val newCursor = start + comp.label.length
                                value = TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(newCursor),
                                )
                                onContentChange(newText)
                                showCompletions = false
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val (icon, tint) = when (comp.kind) {
                            CompletionKind.KEYWORD  -> Pair(Icons.Default.Code,       Color(0xFF569CD6))
                            CompletionKind.TYPE     -> Pair(Icons.Default.TextFields,  Color(0xFF4EC9B0))
                            CompletionKind.SNIPPET  -> Pair(Icons.Default.Functions,   Color(0xFFDCDCAA))
                        }
                        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                        Text(comp.label, color = Color(0xFFD4D4D4), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.weight(1f))
                        Text(
                            comp.kind.name.lowercase(),
                            color = Color(0xFF808080), fontSize = 9.sp,
                        )
                    }
                }
            }
        }
    }
}
