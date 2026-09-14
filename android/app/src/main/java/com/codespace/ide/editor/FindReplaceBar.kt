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

/**
 * FindReplaceBar shared input field (PORTRAIT-FIX 2026-09-14 extraction —
 * the same field renders in both the wide and the compact layout branches).
 */
@Composable
private fun FrTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    borderColor: Color,
    modifier: Modifier,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onChange,
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
                if (value.isEmpty()) Text(
                    label,
                    color = Color(0xFF666666),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                )
                inner()
            }
        },
        modifier = modifier
            .heightIn(min = 28.dp)
            .background(Color(0xFF1E1E1E), RoundedCornerShape(3.dp))
            .border(1.dp, borderColor, RoundedCornerShape(3.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
    )
}

/** FindReplaceBar shared option toggle (.* / Aa / W / AB). */
@Composable
private fun FrToggle(label: String, active: Boolean, onClick: () -> Unit, bold: Boolean = false) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(28.dp),
    ) {
        Text(
            label,
            color = if (active) Color(0xFF007ACC) else Color(0xFF888888),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * FindReplaceBar prev/next/close trio (shared by both layout branches).
 */
@Composable
private fun FrNavButtons(
    matches: List<IntRange>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    IconButton(
        onClick = onPrev,
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
        onClick = onNext,
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
        onClick = onClose,
        modifier = Modifier.size(28.dp),
    ) {
        Icon(
            Icons.Default.Close, null,
            tint = Color(0xFF888888),
            modifier = Modifier.size(16.dp),
        )
    }
}

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
        // PORTRAIT-FIX (2026-09-14): the old single-row layout fixed ~320dp of
        // chrome (match label + 8 buttons) next to a flexible field — portrait
        // (~360-400dp) crushed the field into a sliver. Now a width check picks
        // a stacked two-row layout when narrow; landscape is IDENTICAL to the
        // previous single-row layout.
        BoxWithConstraints(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0xFF252526))
                .border(1.dp, Color(0xFF3C3C3C))
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .zIndex(20f),
        ) {
            val compact = maxWidth < 480.dp
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val findBorder = if (findQuery.isNotEmpty() && matches.isEmpty()) Color(0xFFE51400) else Color(0xFF3C3C3C)
                val matchLabel = when {
                    findQuery.isEmpty() -> ""
                    matches.isEmpty() -> "No results"
                    else -> "${matchIndex + 1}/${matches.size}"
                }
                fun goPrev() {
                    if (matches.isNotEmpty()) {
                        val newIndex = (matchIndex - 1 + matches.size) % matches.size
                        onMatchIndexChange(newIndex)
                        val range = matches[newIndex]
                        onSelectRange(range.first, range.last + 1)
                    }
                }
                fun goNext() {
                    if (matches.isNotEmpty()) {
                        val newIndex = (matchIndex + 1) % matches.size
                        onMatchIndexChange(newIndex)
                        val range = matches[newIndex]
                        onSelectRange(range.first, range.last + 1)
                    }
                }
                if (compact) {
                    // NARROW: field + counter + navigation on row 1, options on row 2.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FrTextField(
                            "Find", findQuery, { onFindQueryChange(it.trimEnd()) },
                            findBorder, Modifier.weight(1f), findFocusRequester,
                        )
                        Text(
                            matchLabel,
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            modifier = Modifier.widthIn(min = 52.dp),
                        )
                        FrNavButtons(matches, { goPrev() }, { goNext() }, { onFindReplaceClose() })
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FrToggle(".*", useRegex, onToggleRegex)
                        FrToggle("Aa", caseSensitive, onToggleCaseSensitive)
                        FrToggle("W", wholeWord, onToggleWholeWord, bold = true)
                        FrToggle("AB", preserveCase, onTogglePreserveCase, bold = true)
                    }
                } else {
                    // WIDE: the exact previous single-row layout.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        FrTextField(
                            "Find", findQuery, { onFindQueryChange(it.trimEnd()) },
                            findBorder, Modifier.weight(1f), findFocusRequester,
                        )
                        Text(
                            matchLabel,
                            color = Color(0xFF888888),
                            fontSize = 10.sp,
                            modifier = Modifier.widthIn(min = 52.dp),
                        )
                        FrToggle(".*", useRegex, onToggleRegex)
                        FrToggle("Aa", caseSensitive, onToggleCaseSensitive)
                        FrToggle("W", wholeWord, onToggleWholeWord, bold = true)
                        FrToggle("AB", preserveCase, onTogglePreserveCase, bold = true)
                        FrNavButtons(matches, { goPrev() }, { goNext() }, { onFindReplaceClose() })
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FrTextField(
                        "Replace", replaceQuery, onReplaceQueryChange,
                        Color(0xFF3C3C3C), Modifier.weight(1f),
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
