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

private data class Completion(val label: String, val kind: CompletionKind)
private enum class CompletionKind { KEYWORD, TYPE, SNIPPET }

/** Build completion candidates from current word + language spec */
private fun completionsFor(prefix: String, lang: Language): List<Completion> {
    if (prefix.length < 2) return emptyList()
    val spec = LanguageSpecs.forLanguage(lang)
    val p = prefix.lowercase()
    val kw = spec.keywords.filter { it.startsWith(p) }.sorted().map { Completion(it, CompletionKind.KEYWORD) }
    val ty = spec.types.filter { it.lowercase().startsWith(p) }.sorted().map { Completion(it, CompletionKind.TYPE) }
    // Common snippets
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

/** Extract the last partial word being typed */
private fun currentWord(text: String, cursor: Int): String {
    val end = cursor.coerceAtMost(text.length)
    var start = end
    while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
    return text.substring(start, end)
}

/**
 * Multi-feature code editor pane.
 *
 * - Monospace, syntax-highlighted via [SyntaxHighlighter] visual transformation.
 * - Line-number gutter.
 * - Horizontal + vertical scrolling for long lines / big files.
 * - IntelliSense autocomplete dropdown (keyword + type + snippet suggestions).
 * - Emits [onContentChange] for autosave + dirty tracking.
 */
@Composable
fun CodeEditor(
    content: String,
    language: Language,
    fontSize: Int = 13,
    onContentChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    savedContent: String = "",   // original saved text — used for diff gutter indicators
) {
    val colors = LocalEditorColors.current
    var value by remember { mutableStateOf(TextFieldValue(content)) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()

    val lineCount = remember(value.text) { value.text.count { it == '\n' } + 1 }

    // IntelliSense state
    val prefix = remember(value) { currentWord(value.text, value.selection.end) }
    val completions = remember(prefix, language) { completionsFor(prefix, language) }
    var showCompletions by remember { mutableStateOf(false) }
    LaunchedEffect(prefix) { showCompletions = prefix.length >= 2 && completions.isNotEmpty() }

    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(end = 62.dp)   // leave room for minimap
                .verticalScroll(vScroll)
        ) {
            // Gutter — with diff indicators
            val savedLines = remember(savedContent) { savedContent.split("
") }
            val currentLines = remember(value.text) { value.text.split("
") }
            Column(modifier = Modifier.padding(horizontal = 4.dp).width(50.dp)) {
                for (lineNum in 1..lineCount) {
                    val idx = lineNum - 1
                    val isDirty = savedContent.isNotEmpty() && (
                        idx >= savedLines.size || (idx < currentLines.size && idx < savedLines.size && currentLines[idx] != savedLines[idx])
                    )
                    val isAdded = savedContent.isNotEmpty() && idx >= savedLines.size
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Diff indicator — 4dp wide
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(fontSize.dp)
                                .background(
                                    when {
                                        isAdded -> Color(0xFF4EC9B0) // teal = added
                                        isDirty -> Color(0xFF569CD6)  // blue = modified
                                        else    -> Color.Transparent
                                    }
                                )
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = lineNum.toString(),
                            color = colors.gutter,
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
            // Editor surface
            Box(modifier = Modifier.horizontalScroll(hScroll)) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onContentChange(it.text)
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

        // ── Minimap — right-side code thumbnail ──────────────────────────────
        val textLines = remember(value.text) { value.text.split("
") }
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(60.dp)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A))
                .zIndex(5f),
        ) {
            textLines.forEachIndexed { idx, line ->
                // Each minimap "line" is a thin colored rect representing code density
                val density = (line.trimStart().length.coerceAtMost(80)).toFloat() / 80f
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Indentation blank
                    val indent = line.length - line.trimStart().length
                    Spacer(Modifier.width((indent * 0.3f).dp))
                    // Code line body
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

        // ── IntelliSense dropdown ─────────────────────────────────────────────
        if (showCompletions && completions.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 52.dp, top = ((value.text.take(value.selection.end).count { it == '\n' } + 1) * fontSize * 1.25f).dp)
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
                                // Replace current word with completion
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
                        // Kind icon
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
