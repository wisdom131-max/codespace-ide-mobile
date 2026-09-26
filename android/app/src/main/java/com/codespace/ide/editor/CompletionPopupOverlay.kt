package com.codespace.ide.editor

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
import androidx.compose.material3.CircularProgressIndicator
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
import com.codespace.ide.lsp.parseSnippet
import com.codespace.ide.lsp.SnippetContext
import com.codespace.ide.lsp.fuzzyMatchIndices
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector


@Composable
internal fun CompletionPopupOverlay(
    showCompletionsState: MutableState<Boolean>,
    completionFilterState: MutableState<CompletionSource?>,
    selectedLabelState: MutableState<String?>,
    snippetSessionState: MutableState<SnippetSession?>,
    showSnippetChoicesState: MutableState<Boolean>,
    completionPopupExtraHeightDpState: MutableState<Float>,
    extraCursorsState: MutableState<List<androidx.compose.ui.text.TextRange>>,
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
    allCompletions: List<Completion>,
    coroutineScope: CoroutineScope,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    // IC08 (P4c): onAiFixRequest param REMOVED with the dead AI-source paths; the
    // lightbulb's real AI-fix flow uses LightbulbMenuOverlay, which keeps its own.
    lspImportProvider: ((line: Int, col: Int) -> List<ImportEdit>)? = null,
    detailDocState: MutableState<String?>,
    detailLabelState: MutableState<String?>,
    onContentChange: (String) -> Unit,
    fontSize: Int,
    programmaticTextChange: (String, TextRange, String) -> Unit,
    lspLoading: Boolean = false,
) {
    var detailDoc by detailDocState
    var detailLabel by detailLabelState
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
    val availableHeightPx = with(screenDensity) { availableHeightDp.dp.toPx() }
    val popupMaxHeightPx = with(screenDensity) { 220.dp.toPx() }
    var popupOffsetY = if (layoutCP != null && visualLineCP < layoutCP.lineCount) {
        (layoutCP.getLineBottom(visualLineCP) - vScroll.value).roundToInt().coerceAtLeast(0)
    } else {
        ((cursorLine + 1) * lineHeightPx - vScroll.value).roundToInt().coerceAtLeast(0)
    }
    // KEYBOARD-AWARE: Check against available height (screen minus keyboard),
    // not full screen height. Without this, the popup renders behind the keyboard
    // when the cursor is near the bottom of the visible area.
    if (popupOffsetY + popupMaxHeightPx > availableHeightPx) {
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
            
            // INLINE-LOADING: When a new LSP request is in-flight while the popup
            // is still showing previous results, render a compact loading row at
            // the TOP of the completion list instead of a separate overlapping
            // Popup window. Eliminates the z-order race where the standalone
            // loading popup would render on top of the completion popup.
            if (lspLoading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.function.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.function,
                    )
                    Text(
                        text = "Loading...",
                        fontSize = 10.sp,
                        color = colors.text.copy(alpha = 0.6f),
                    )
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
                            // IC01/IC02/IC03 (P4c): tap routes through the ONE shared accept
                            // computation (CompletionAccept) — same word scan (never crosses '.'),
                            // same server textEdit/import/snippet handling as Tab, Enter and
                            // commit characters.
                            coroutineScope.launch {
                                val outcome = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    CompletionAccept.compute(comp, value.text, value.selection.end, positionMapper)
                                }
                                if (outcome.error != null) {
                                    com.codespace.ide.diagnostics.AppOutputLog.log(
                                        "[Completion] tap accept: " + outcome.error, "lsp")
                                }
                                var finalText = outcome.newText
                                var finalSel = if (outcome.selectionStart >= 0) {
                                    androidx.compose.ui.text.TextRange(outcome.selectionStart, outcome.selectionEnd)
                                } else {
                                    androidx.compose.ui.text.TextRange(outcome.newCursor)
                                }
                                var appliedSnippet = false
                                // P22-J fallback: when the server did NOT attach auto-import
                                // edits, ask code actions for import edits and patch them in.
                                if (!outcome.usedAdditionalEdits && lspImportProvider != null) {
                                    val cLine = positionMapper.offsetToLine(value.selection.end)
                                    val cCol = positionMapper.offsetToPosition(value.selection.end).column
                                    val imports = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try { lspImportProvider.invoke(cLine, cCol) } catch (_: Exception) { emptyList() }
                                    }
                                    if (imports.isNotEmpty()) {
                                        val patched = com.codespace.ide.lsp.applyImportEdits(finalText, imports)
                                        val importDelta = patched.length - finalText.length
                                        if (outcome.snippetParsed != null) {
                                            val session = createSnippetSession(outcome.insertStart + importDelta, outcome.snippetParsed)
                                            snippetSession = session
                                            showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true
                                            val firstStop = session.tabStops.firstOrNull()
                                            finalSel = if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                                                androidx.compose.ui.text.TextRange(firstStop.startOffset, firstStop.endOffset)
                                            } else {
                                                androidx.compose.ui.text.TextRange(firstStop?.startOffset ?: session.finalCursorOffset)
                                            }
                                            appliedSnippet = true
                                        } else {
                                            finalSel = androidx.compose.ui.text.TextRange(outcome.newCursor + importDelta)
                                        }
                                        finalText = patched
                                    }
                                }
                                if (!appliedSnippet && outcome.snippetSession != null) {
                                    snippetSession = outcome.snippetSession
                                    showSnippetChoices = outcome.showSnippetChoices
                                }
                                programmaticTextChange(finalText, finalSel, "completion_tap")
                                // P22-J parity: content-sync after import-provider involvement
                                if (lspImportProvider != null) onContentChange(finalText)
                                // P41 Phase B: Record accepted completion for MRU/usage ranking
                                CompletionHistoryStore.recordAccepted(comp.label, language.name, context)
                                // IC03 (P4c): execute the completion's LSP command after accept
                                if (comp.command != null) {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        CompletionAccept.executeCommand(language, comp.command)
                                    }
                                }
                                showCompletions = false
                                selectedLabel = null
                                completionFilter = null
                            }
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
                        CompletionSource.PATH -> "Path" to Color(0xFF9CDCFE)
                    }
                    Text(badgeText, color = badgeColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                }
            }
            
            // IC08 (P4c): the AI-source "? Explain" affordance was REMOVED — no producer
            // ever created AI-source completion items, so this block could never render.
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

@Composable
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
    return when (kind) {
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


