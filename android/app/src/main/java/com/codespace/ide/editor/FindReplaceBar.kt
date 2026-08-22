package com.codespace.ide.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

@Composable
fun androidx.compose.foundation.layout.BoxScope.FindReplaceBar(
    findReplaceOpen: Boolean,
    findQuery: String,
    onFindQueryChange: (String) -> Unit,
    replaceQuery: String,
    onReplaceQueryChange: (String) -> Unit,
    useRegex: Boolean,
    onToggleRegex: () -> Unit,
    caseSensitive: Boolean,
    onToggleCaseSensitive: () -> Unit,
    wholeWord: Boolean,
    onToggleWholeWord: () -> Unit,
    preserveCase: Boolean,
    onTogglePreserveCase: () -> Unit,
    matches: List<IntRange>,
    matchIndex: Int,
    onMatchIndexChange: (Int) -> Unit,
    text: String,
    onTextChange: (newText: String, cursor: Int) -> Unit,
    onSelectRange: (start: Int, end: Int) -> Unit,
    onFindReplaceClose: () -> Unit,
) {
    if (findReplaceOpen) {
        val findFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(findReplaceOpen) {
            if (findReplaceOpen) {
                kotlinx.coroutines.delay(100)
                try { findFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .border(1.dp, Color(0xFF3C3C3C))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .zIndex(20f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val matchLabel = when {
                    findQuery.isEmpty() -> ""
                    matches.isEmpty() -> "No results"
                    else -> "${matchIndex + 1}/${matches.size}"
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = findQuery,
                    onValueChange = { onFindQueryChange(it.trimEnd()) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFAEAFAD)),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            if (findQuery.isEmpty()) Text(
                                "Find",
                                color = Color(0xFF666666),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 28.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                        .border(
                            1.dp,
                            if (findQuery.isNotEmpty() && matches.isEmpty()) Color(0xFFE51400)
                            else Color(0xFF3C3C3C),
                            RoundedCornerShape(3.dp),
                        )
                        .focusRequester(findFocusRequester),
                )
                Text(
                    matchLabel,
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                    modifier = Modifier.widthIn(min = 52.dp),
                )
                IconButton(
                    onClick = { onToggleRegex() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        ".*",
                        color = if (useRegex) Color(0xFF007ACC) else Color(0xFF888888),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(
                    onClick = { onToggleCaseSensitive() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        "Aa",
                        color = if (caseSensitive) Color(0xFF007ACC) else Color(0xFF888888),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                IconButton(
                    onClick = { onToggleWholeWord() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        "W",
                        color = if (wholeWord) Color(0xFF007ACC) else Color(0xFF888888),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                // R3-B/D1: Preserve Case toggle
                IconButton(
                    onClick = { onTogglePreserveCase() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Text(
                        "AB",
                        color = if (preserveCase) Color(0xFF007ACC) else Color(0xFF888888),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                IconButton(
                    onClick = {
                        if (matches.isNotEmpty()) {
                            val newIndex = (matchIndex - 1 + matches.size) % matches.size
                            onMatchIndexChange(newIndex)
                            val range = matches[newIndex]
                            onSelectRange(range.first, range.last + 1)
                        }
                    },
                    modifier = Modifier.size(28.dp),
                    enabled = matches.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowUp, null,
                        tint = if (matches.isNotEmpty()) Color(0xFFD4D4D4) else Color(0xFF555555),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = {
                        if (matches.isNotEmpty()) {
                            val newIndex = (matchIndex + 1) % matches.size
                            onMatchIndexChange(newIndex)
                            val range = matches[newIndex]
                            onSelectRange(range.first, range.last + 1)
                        }
                    },
                    modifier = Modifier.size(28.dp),
                    enabled = matches.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Default.KeyboardArrowDown, null,
                        tint = if (matches.isNotEmpty()) Color(0xFFD4D4D4) else Color(0xFF555555),
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = { onFindReplaceClose() },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Default.Close, null,
                        tint = Color(0xFF888888),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                androidx.compose.foundation.text.BasicTextField(
                    value = replaceQuery,
                    onValueChange = { onReplaceQueryChange(it) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color(0xFFCCCCCC),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    ),
                    keyboardOptions = KeyboardOptions(autoCorrect = false),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color(0xFFAEAFAD)),
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            if (replaceQuery.isEmpty()) Text(
                                "Replace",
                                color = Color(0xFF666666),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                            inner()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 28.dp)
                        .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
                        .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(3.dp)),
                )
                TextButton(
                    onClick = {
                        if (matches.isNotEmpty()) {
                            val range = matches[matchIndex]
                            val matchedText = text.substring(range.first, range.last + 1)
                            // R3-B/D2: Expand backreferences (convert \1 to $1 for Kotlin regex)
                            val expandedReplace = if (useRegex) {
                                replaceQuery.replace("\\(", "$(")  // \1 -> $1, \2 -> $2
                            } else {
                                replaceQuery
                            }
                            // R3-B/D1: Case-preserving replace
                            val finalReplace = if (preserveCase) {
                                preserveCaseReplace(matchedText, expandedReplace)
                            } else {
                                expandedReplace
                            }
                            val newText = text.substring(0, range.first) +
                                finalReplace + text.substring(range.last + 1)
                            val cursor = range.first + finalReplace.length
                            onTextChange(newText, cursor)
                        }
                    },
                    enabled = matches.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "Replace",
                        color = if (matches.isNotEmpty()) Color(0xFF007ACC) else Color(0xFF555555),
                        fontSize = 11.sp,
                    )
                }
                TextButton(
                    onClick = {
                        if (findQuery.isNotEmpty() && matches.isNotEmpty()) {
                            val newText = try {
                                val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                                val rawPat = if (useRegex) findQuery else Regex.escape(findQuery)
                                val finalPat = if (wholeWord && !useRegex) "\\b${rawPat}\\b" else rawPat
                                // R3-B/D2: Convert \1 to $1 for Kotlin regex backreferences
                                val expandedReplace = replaceQuery.replace("\\(", "$(")
                                if (preserveCase) {
                                    // R3-B/D1: Case-preserving replace all
                                    val regex = Regex(finalPat, opts)
                                    val sb = StringBuilder()
                                    var lastEnd = 0
                                    for (m in regex.findAll(text)) {
                                        sb.append(text, lastEnd, m.range.first)
                                        sb.append(preserveCaseReplace(m.value, expandedReplace))
                                        lastEnd = m.range.last + 1
                                    }
                                    sb.append(text, lastEnd, text.length)
                                    sb.toString()
                                } else {
                                    Regex(finalPat, opts).replace(text, expandedReplace)
                                }
                            } catch (e: Exception) { text }
                            onTextChange(newText, 0)
                        }
                    },
                    enabled = matches.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        "All",
                        color = if (matches.isNotEmpty()) Color(0xFF007ACC) else Color(0xFF555555),
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

/**
 * R3-B/D1: Case-preserving replace — matches the case pattern of the original text.
 * - All uppercase -> uppercase replacement
 * - First char uppercase -> capitalize first char of replacement
 * - All lowercase -> keep replacement as-is (already lowercase or mixed)
 */
fun preserveCaseReplace(matched: String, replacement: String): String {
    if (matched.isEmpty() || replacement.isEmpty()) return replacement
    return when {
        matched.all { it.isUpperCase() || !it.isLetter() } && matched.any { it.isUpperCase() } -> {
            replacement.uppercase()
        }
        matched.first().isUpperCase() -> {
            replacement.replaceFirstChar { if (it.isLowerCase()) it.uppercaseChar() else it }
        }
        else -> replacement
    }
}
