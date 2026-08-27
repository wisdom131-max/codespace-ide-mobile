package com.codespace.ide.editor

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.CompletionItemKind
import com.codespace.ide.lsp.CompletionSource
import com.codespace.ide.lsp.ImportEdit
import com.codespace.ide.lsp.RankedCompletionItem
import com.codespace.ide.lsp.SnippetSession
import com.codespace.ide.lsp.applyImportEdits
import com.codespace.ide.lsp.applyLspTextEdits
import com.codespace.ide.lsp.createSnippetSession
import com.codespace.ide.lsp.CompletionHistoryStore
import com.codespace.ide.ui.EditorColors
import com.codespace.ide.editor.EditShiftHelper
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.ScrollState
import com.codespace.ide.editor.VisualLineMapper
import androidx.compose.foundation.gestures.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector


@Composable
internal fun CompletionPopupOverlay(
    showCompletionsState: MutableState<Boolean>,
    completionFilterState: MutableState<CompletionSource?>,
    selectedLabelState: MutableState<String?>,
    snippetSessionState: MutableState<SnippetSession?>,
    showSnippetChoicesState: MutableState<Boolean>,
    completionPopupExtraHeightDpState: MutableState<Float>,
    extraCursorsState: MutableState<List<Int>>,
    value: TextFieldValue,
    colors: EditorColors,
    context: Context,
    language: Language,
    filePath: String?,
    projectRoot: String?,
    positionMapper: PositionMapper,
    visualLineMapper: VisualLineMapper,
    textLayoutResult: TextLayoutResult?,
    vScroll: ScrollState,
    scrollDensity: Density,
    lineHeightDp: Dp,
    editorMetrics: EditorMetrics,
    availableHeightDp: Int,
    prefix: String,
    allCompletions: List<RankedCompletionItem>,
    coroutineScope: CoroutineScope,
    clipboardManager: ClipboardManager,
    onAiFixRequest: ((String) -> Unit)?,
    lspImportProvider: ((line: Int, col: Int) -> List<ImportEdit>)? = null,
    lspKind: Int = 0,
    programmaticTextChange: (String, TextRange, String) -> Unit,
) {
    var showCompletions by showCompletionsState
    var completionFilter by completionFilterState
    var selectedLabel by selectedLabelState
    var snippetSession by snippetSessionState
    var showSnippetChoices by showSnippetChoicesState
    var completionPopupExtraHeightDp by completionPopupExtraHeightDpState
    var extraCursors by extraCursorsState

    if (showCompletions && allCompletions.isNotEmpty()) {
    val cursorLine = positionMapper.offsetToLine(value.selection.end)
    val lineHeightPx = with(scrollDensity) { lineHeightDp.toPx() }
    val cursorCol = positionMapper.offsetToPosition(value.selection.end).column
    val cursorOff = value.selection.end
    val visualLineCP = visualLineMapper.docToVisualLine(cursorLine)
    val layoutCP = textLayoutResult
    val screenDensity = androidx.compose.ui.platform.LocalDensity.current
    val screenWidthPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val popupWidthPx = with(screenDensity) { 280.dp.toPx() }
    val safeCursorOffCP = cursorOff.coerceIn(0, layoutCP?.layoutInput?.text?.length ?: 0)
    var popupOffsetX = if (layoutCP != null) {
        (with(screenDensity) { EditorMetrics.GUTTER_WIDTH_DP.dp.toPx() } + layoutCP.getHorizontalPosition(safeCursorOffCP, true)).roundToInt()
    } else {
        val charWidthPx = editorMetrics.charWidthPx
        (with(screenDensity) { EditorMetrics.GUTTER_WIDTH_DP.dp.toPx() } + cursorCol * charWidthPx).roundToInt()
    }
    if (popupOffsetX + popupWidthPx > screenWidthPx) {
        popupOffsetX = (screenWidthPx - popupWidthPx).roundToInt().coerceAtLeast(0)
    }
    val screenHeightPx = with(screenDensity) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val popupMaxHeightPx = with(screenDensity) { 220.dp.toPx() }
    var popupOffsetY = if (layoutCP != null && visualLineCP < layoutCP.lineCount) {
        (layoutCP.getLineBottom(visualLineCP) - vScroll.value).roundToInt().coerceAtLeast(0)
    } else {
        ((cursorLine + 1) * lineHeightPx - vScroll.value).roundToInt().coerceAtLeast(0)
    }
    if (popupOffsetY + popupMaxHeightPx > screenHeightPx) {
        popupOffsetY = if (layoutCP != null && visualLineCP < layoutCP.lineCount) {
            (layoutCP.getLineTop(visualLineCP) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
        } else {
            ((cursorLine * lineHeightPx) - vScroll.value - popupMaxHeightPx).roundToInt().coerceAtLeast(0)
        }
    }
    
    // P41-J: Apply filter if active
    val filteredCompletions = if (completionFilter != null) {
        allCompletions.filter { it.source == completionFilter }
    } else {
        allCompletions
    }
    // P41-J: Available sources for filter chips
    val availableSources = allCompletions.map { it.source }.distinct()
    
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(popupOffsetX, popupOffsetY),
        properties = PopupProperties(focusable = false),
    ) {
        // NEW (2026-08-10): Resizable popup — base max height + user-dragged extra height,
        // clamped so it never exceeds available screen space above the keyboard.
        val basePopupMaxDp = if (availableHeightDp > 200) 220f else (availableHeightDp * 0.4f).coerceAtLeast(120f)
        val popupMaxDp = (basePopupMaxDp + completionPopupExtraHeightDp)
            .coerceIn(120f, availableHeightDp.toFloat().coerceAtLeast(120f))
        Column(
            modifier = Modifier
                .widthIn(min = 160.dp, max = 280.dp)
                .heightIn(max = popupMaxDp.dp)
                .background(colors.background, RoundedCornerShape(6.dp))
                .border(1.dp, colors.function.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .clickable { } // consume touches to prevent touch-through to editor
        ) {
            // P41-J: Filter chips row
            if (availableSources.size > 1) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // "All" chip
                    item {
                        FilterChip(
                            label = "All",
                            isActive = completionFilter == null,
                            color = Color(0xFF888888),
                            onClick = { completionFilter = null; selectedLabel = null }
                        )
                    }
                    // Source-specific chips
                    items(availableSources) { src ->
                        val (chipLabel, chipColor) = when (src) {
                            CompletionSource.LSP -> "LSP" to Color(0xFF4EC9B0)
                            CompletionSource.BUFFER -> "Buf" to Color(0xFF888888)
                            CompletionSource.SNIPPET -> "Snip" to Color(0xFFDCDCAA)
                            CompletionSource.WORKSPACE -> "Wksp" to Color(0xFF4DA6FF)
                            CompletionSource.AI -> "AI" to Color(0xFFC586C0)
                            CompletionSource.PATH -> "Path" to Color(0xFF9CDCFE)
                        }
                        FilterChip(
                            label = chipLabel,
                            isActive = completionFilter == src,
                            color = chipColor,
                            onClick = { completionFilter = if (completionFilter == src) null else src; selectedLabel = null }
                        )
                    }
                }
            }
            
            // P41-J: Sticky selection — find index of previously selected label
            val initialIndex = if (selectedLabel != null) {
                filteredCompletions.indexOfFirst { it.label == selectedLabel }.coerceAtLeast(0)
            } else 0
            
            // P41-J: Detail panel — update doc for highlighted item
            LaunchedEffect(initialIndex, filteredCompletions) {
                if (initialIndex < filteredCompletions.size) {
                    val highlighted = filteredCompletions[initialIndex]
                    detailDoc = highlighted.doc
                    detailLabel = highlighted.label
                } else {
                    detailDoc = null
                    detailLabel = null
                }
            }
            
            LazyColumn(
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(filteredCompletions) { idx, comp ->
                // Doc always visible below label — no per-item state (Compose rules)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (idx == initialIndex) Color(0xFF04395E) else Color.Transparent)
                        .clickable {
                            val cursor = value.selection.end
                            val text = value.text
                            val end = cursor.coerceAtMost(text.length)
                            var start = end
                            // Fix: don't cross spaces — "import o" should only replace "o", not "import o"
                            while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
                            
                            // P41-D: Check for LSP additionalTextEdits (auto-import) attached to this completion
                            val hasAdditionalEdits = !comp.additionalTextEditsJson.isNullOrBlank()
                            
                            if (hasAdditionalEdits) {
                                // P41-D: Apply additionalTextEdits (imports) FIRST, then insert completion text
                                // LSP spec: additionalTextEdits are applied before the main edit
                                coroutineScope.launch {
                                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val editsArray = org.json.JSONArray(comp.additionalTextEditsJson)
                                            // Apply additional edits to the full text first
                                            val textWithImports = applyLspTextEdits(text, editsArray)
                                            // Then insert completion text at cursor position
                                            // (adjust cursor position if edits were above it)
                                            val cursorOffset = textWithImports.length - text.length
                                            val adjustedStart = start + cursorOffset
                                            val adjustedEnd = end + cursorOffset
                                            // P41-I: If snippet, parse and replace insertText with cleaned version
                                            val (textToInsert, snippetParsed) = if (comp.insertTextFormat == 2) {
                                                val parsed = parseSnippet(comp.insertText, SnippetContext(
                                                    lineNumber = positionMapper.offsetToLine(start) + 1,
                                                    lineIndex = positionMapper.offsetToLine(start),
                                                    currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                                    selectedText = if (start != end) value.text.substring(start, end) else "",
                                                ))
                                                Pair(parsed.cleanedText, parsed)
                                            } else {
                                                Pair(comp.insertText, null)
                                            }
                                            val finalText = textWithImports.substring(0, adjustedStart) + textToInsert + textWithImports.substring(adjustedEnd.coerceAtMost(textWithImports.length))
                                            val finalCursor = if (snippetParsed != null) {
                                                val session = createSnippetSession(adjustedStart, snippetParsed)
                                                snippetSession = session
                                                showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                val firstStop = session.tabStops.firstOrNull()
                                                if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                    firstStop.startOffset
                                                } else {
                                                    firstStop?.startOffset ?: session.finalCursorOffset
                                                }
                                            } else {
                                                adjustedStart + textToInsert.length
                                            }
                                            Pair(finalText, finalCursor)
                                            Pair(finalText, finalCursor)
                                        } catch (_: Exception) {
                                            // Fallback: plain insert without auto-import
                                            val newText = text.substring(0, start) + comp.insertText + text.substring(end)
                                            Pair(newText, start + comp.insertText.length)
                                        }
                                    }
                                    // P41-I: If snippet, select first tab-stop default text
                                    val selRange = if (snippetSession != null) {
                                        val session = snippetSession!!
                                        val firstStop = session.tabStops.firstOrNull()
                                        if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                            androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                        } else {
                                            androidx.compose.ui.text.TextRange(result.second)
                                        }
                                    } else {
                                        androidx.compose.ui.text.TextRange(result.second)
                                    }
                                    extraCursors = EditShiftHelper.shiftExtraCursors(value.text, result.first, extraCursors)
                                    programmaticTextChange(result.first, selRange, "format_result")
                                }
                            } else {
                                // P41-I: Handle snippet insertTextFormat == 2
                                val (rawInsert, snipParsed) = if (comp.insertTextFormat == 2) {
                                    val parsed = parseSnippet(comp.insertText, SnippetContext(
                                        lineNumber = positionMapper.offsetToLine(start) + 1,
                                        lineIndex = positionMapper.offsetToLine(start),
                                        currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                        selectedText = if (start != end) value.text.substring(start, end) else "",
                                    ))
                                    Pair(parsed.cleanedText, parsed)
                                } else {
                                    Pair(comp.insertText, null)
                                }
                                var newText = text.substring(0, start) + rawInsert + text.substring(end)
                                var newCursor = start + rawInsert.length
                                // P22-J: Fall back to lspImportProvider for auto-import via code actions
                                if (lspImportProvider != null) {
                                    val cPos = positionMapper.offsetToPosition(cursor)
                                    val cLine = cPos.line
                                    val cCol = cPos.column
                                    coroutineScope.launch {
                                        val imports = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                            try { lspImportProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList() }
                                        }
                                        if (imports.isNotEmpty()) {
                                            val patched = applyImportEdits(newText, imports)
                                            val importDelta = patched.length - newText.length
                                            if (snipParsed != null) {
                                                // P41-I: Snippet mode after import
                                                val session = createSnippetSession(start + importDelta, snipParsed)
                                                snippetSession = session
                                                showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                val firstStop = session.tabStops.firstOrNull()
                                                val sel = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                    androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                } else {
                                                    androidx.compose.ui.text.TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                                }
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, patched, extraCursors)
                                                programmaticTextChange(patched, sel, "auto_import_patched")
                                            } else {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, patched, extraCursors)
                                                programmaticTextChange(patched, androidx.compose.ui.text.TextRange(newCursor + importDelta), "auto_import_delta")
                                            }
                                            onContentChange(patched)
                                        } else {
                                            if (snipParsed != null) {
                                                // P41-I: Snippet mode, no imports needed
                                                val session = createSnippetSession(start, snipParsed)
                                                snippetSession = session
                                                showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                                val firstStop = session.tabStops.firstOrNull()
                                                val sel = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                    androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                                } else {
                                                    androidx.compose.ui.text.TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                                }
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                programmaticTextChange(newText, sel, "ai_fix_applied")
                                            } else {
                                                extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                                programmaticTextChange(newText, androidx.compose.ui.text.TextRange(newCursor), "ai_fix_newcursor")
                                            }
                                            onContentChange(newText)
                                        }
                                    }
                                } else {
                                    // P41-I: If this is a snippet (insertTextFormat == 2), parse and enter snippet mode
                                    if (comp.insertTextFormat == 2) {
                                        val parsed = parseSnippet(comp.insertText, SnippetContext(
                                        fileName = "",
                                        lineNumber = positionMapper.offsetToLine(start) + 1,
                                        lineIndex = positionMapper.offsetToLine(start),
                                        currentLine = positionMapper.getLineText(value.text, positionMapper.offsetToLine(start)),
                                        selectedText = if (start != end) value.text.substring(start, end) else "",
                                    ))
                                        val snippetText = parsed.cleanedText
                                        newText = text.substring(0, start) + snippetText + text.substring(end)
                                        val session = createSnippetSession(start, parsed)
                                        snippetSession = session
                                        showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                        // Place cursor at first tab-stop, or final cursor if no stops
                                        val firstStop = session.tabStops.firstOrNull()
                                        val cursorPos = if (firstStop != null) {
                                            firstStop.startOffset
                                        } else {
                                            session.finalCursorOffset
                                        }
                                        // If first stop has default text, select it
                                        val selectionRange = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                            androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                        } else {
                                            androidx.compose.ui.text.TextRange(cursorPos)
                                        }
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, selectionRange, "ai_result")
                                    } else {
                                        extraCursors = EditShiftHelper.shiftExtraCursors(value.text, newText, extraCursors)
                                        programmaticTextChange(newText, androidx.compose.ui.text.TextRange(newCursor), "ai_result_cursor")
                                    }
                                }
                            }
                            // P41 Phase B: Record accepted completion for MRU/usage ranking
                            CompletionHistoryStore.recordAccepted(comp.label, language.name, context)
                            showCompletions = false
                            selectedLabel = null
                            completionFilter = null
                        }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // P41-H: Full LSP CompletionItemKind icon mapping (1-25)
                    val (icon, tint) = if (comp.lspKind > 0) {
                        lspCompletionIcon(comp.lspKind)
                    } else {
                        when (comp.kind) {
                            CompletionKind.KEYWORD -> Pair(Icons.Default.Code, Color(0xFF569CD6))
                            CompletionKind.TYPE -> Pair(Icons.Default.TextFields, Color(0xFF4EC9B0))
                            CompletionKind.SNIPPET -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))
                        }
                    }
                    Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                    Column(Modifier.weight(1f)) {
                        // P41 Phase C: Highlight fuzzy-matched characters in the label
                        val matchIndices = fuzzyMatchIndices(prefix, comp.label)
                        val labelAnnotated = if (matchIndices.isNotEmpty()) {
                            buildAnnotatedString {
                                for ((idx, ch) in comp.label.withIndex()) {
                                    if (idx in matchIndices) {
                                        append(AnnotatedString(
                                            ch.toString(),
                                            SpanStyle(
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4DA6FF),
                                            )
                                        ))
                                    } else {
                                        append(ch)
                                    }
                                }
                            }
                        } else {
                            AnnotatedString(comp.label)
                        }
                        // P41-J: Deprecation indicator — strike-through for deprecated items
                        if (comp.isDeprecated) {
                            Text(
                                labelAnnotated,
                                color = Color(0xFF888888),
                                fontSize = (fontSize - 1).sp,
                                fontFamily = FontFamily.Monospace,
                                textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough,
                            )
                        } else {
                            Text(labelAnnotated, color = Color(0xFFD4D4D4), fontSize = (fontSize - 1).sp, fontFamily = FontFamily.Monospace)
                        }
                        if (comp.doc != null) {
                            Text(comp.doc, color = Color(0xFF888888), fontSize = 9.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                    // P41-J: Source badge — small colored label
                    val (badgeText, badgeColor) = when (comp.source) {
                        CompletionSource.LSP -> "LSP" to Color(0xFF4EC9B0)
                        CompletionSource.BUFFER -> "Buf" to Color(0xFF888888)
                        CompletionSource.SNIPPET -> "Snip" to Color(0xFFDCDCAA)
                        CompletionSource.WORKSPACE -> "Wksp" to Color(0xFF4DA6FF)
                        CompletionSource.AI -> "AI" to Color(0xFFC586C0)
                        CompletionSource.PATH -> "Path" to Color(0xFF9CDCFE)
                    }
                    Text(badgeText, color = badgeColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                }
            }
            
            // P41-L: "?" Explain affordance for AI-sourced completions
            if (initialIndex < filteredCompletions.size) {
                val highlighted = filteredCompletions[initialIndex]
                if (highlighted.source == CompletionSource.AI && onAiFixRequest != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            text = "? Explain",
                            color = Color(0xFFC586C0),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clickable {
                                    val cursor = value.selection.end
                                    val text = value.text
                                    val lineStart = positionMapper.lineStart(positionMapper.offsetToLine(cursor))
                                    val lineEnd = text.indexOf('\n', cursor)
                                    val lineText = text.substring(lineStart, if (lineEnd < 0) text.length else lineEnd)
                                    val prompt = "Explain why you suggested \"" + highlighted.label + "\" here.\n" +
                                        "Current line: " + lineText + "\n" +
                                        "File type: " + language.name
                                    onAiFixRequest?.invoke(prompt)
                                    showCompletions = false
                                },
                        )
                    }
                }
            }
            // P41-J: Detail panel — modern: expand + copy + scroll (matches HoverPopup)
            var detailExpanded by remember { mutableStateOf(false) }
            val detailScrollState = rememberScrollState()
            if (detailDoc != null && detailDoc!!.isNotBlank()) {
                HorizontalDivider(color = Color(0xFF3C3C3C), thickness = 1.dp)
                Column(modifier = Modifier.fillMaxWidth().background(Color(0xFF252526))
                    .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(modifier = Modifier.size(20.dp).clickable { detailExpanded = !detailExpanded },
                            contentAlignment = Alignment.Center) {
                            Text(text = if (detailExpanded) "▾" else "▸", color = Color(0xFF888888), fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(2.dp))
                        Box(modifier = Modifier.size(20.dp).clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(detailDoc ?: ""))
                            }, contentAlignment = Alignment.Center) {
                            Text(text = "⏉", color = Color(0xFF888888), fontSize = 11.sp)
                        }
                    }
                    Box(modifier = Modifier.padding(horizontal = 4.dp)
                        .then(if (detailExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(detailScrollState) else Modifier.heightIn(max = 60.dp))) {
                        Column {
                            if (detailLabel != null) {
                                Text(text = detailLabel!!, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF569CD6), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(text = detailDoc!!, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFCCCCCC))
                        }
                    }
                }
            }
            // NEW (2026-08-10): Drag handle to resize the popup — drag down to grow, up to shrink.
            // Matches VS Code's resizable IntelliSense widget seen in vscode.dev testing.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .background(Color(0xFF2D2D2D))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            val dragDp = with(scrollDensity) { dragAmount.y.toDp().value }
                            completionPopupExtraHeightDp = (completionPopupExtraHeightDp + dragDp)
                                .coerceIn(0f, 400f)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(28.dp)
                        .height(3.dp)
                        .background(Color(0xFF5A5A5A), RoundedCornerShape(2.dp))
                )
            }
        }
    }

    }
}

internal fun FilterChip(
    label: String,
    isActive: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .background(
                if (isActive) color.copy(alpha = 0.25f) else Color(0xFF333333),
                RoundedCornerShape(3.dp)
            )
            .border(
                1.dp,
                if (isActive) color else Color(0xFF444444),
                RoundedCornerShape(3.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            label,
            color = if (isActive) color else Color(0xFF888888),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// P41-H: Full LSP CompletionItemKind (1-25) icon + color mapping.
// Colors follow VS Code's theme: https://code.visualstudio.com/docs/languages/identifiers
private fun lspCompletionIcon(kind: Int): Pair<androidx.compose.ui.graphics.vector.ImageVector, androidx.compose.ui.graphics.Color> {


        1   -> Pair(Icons.Default.TextFields, Color(0xFFCCCCCC))    // Text — gray
        2   -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))    // Method — yellow
        3   -> Pair(Icons.Default.Functions, Color(0xFFDCDCAA))    // Function — yellow
        4   -> Pair(Icons.Default.Build, Color(0xFFB8D7A3))         // Constructor — light green
        5   -> Pair(Icons.Default.DataObject, Color(0xFF9CDCFE))   // Field — light blue
        6   -> Pair(Icons.Default.DataObject, Color(0xFF9CDCFE))   // Variable — light blue
        7   -> Pair(Icons.Default.Extension, Color(0xFF4EC9B0))    // Class — teal
        8   -> Pair(Icons.Default.Extension, Color(0xFFB8D7A3))    // Interface — light green
        9   -> Pair(Icons.Default.Public, Color(0xFFCE9178))       // Module — orange
        10  -> Pair(Icons.Default.Tune, Color(0xFF9CDCFE))         // Property — light blue
        11  -> Pair(Icons.Default.Public, Color(0xFFCE9178))       // Unit — orange
        12  -> Pair(Icons.Default.Star, Color(0xFF569CD6))        // Value — blue
        13  -> Pair(Icons.Default.List, Color(0xFF4EC9B0))        // Enum — teal
        14  -> Pair(Icons.Default.Code, Color(0xFF569CD6))       // Keyword — blue
        15  -> Pair(Icons.Default.AutoAwesome, Color(0xFFDCDCAA)) // Snippet — yellow
        16  -> Pair(Icons.Default.ColorLens, Color(0xFFCE9178))   // Color — orange
        17  -> Pair(Icons.Default.Description, Color(0xFF9CDCFE)) // File — light blue
        18  -> Pair(Icons.Default.Link, Color(0xFFCCCCCC))        // Reference — gray
        19  -> Pair(Icons.Default.Folder, Color(0xFFDCB67A))       // Folder — gold
        20  -> Pair(Icons.Default.Label, Color(0xFF4EC9B0))       // EnumMember — teal
        21  -> Pair(Icons.Default.Star, Color(0xFF4FC1FF))        // Constant — bright blue
        22  -> Pair(Icons.Default.Extension, Color(0xFF4EC9B0))  // Struct — teal
        23  -> Pair(Icons.Default.Event, Color(0xFFB8D7A3))       // Event — light green
        24  -> Pair(Icons.Default.Calculate, Color(0xFF569CD6))   // Operator — blue
        25  -> Pair(Icons.Default.TextFields, Color(0xFF4EC9B0))  // TypeParameter — teal
        else -> Pair(Icons.Default.Code, Color(0xFFCCCCCC))       // Unknown — gray
    }
}


