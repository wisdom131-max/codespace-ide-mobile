package com.codespace.ide.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codespace.ide.diagnostics.DiagnosticManager

/**
 * Phase P — Advanced Problems Panel.
 *
 * Reads from the central DiagnosticManager (single source of truth).
 * Supports: severity filtering, source filtering, search, grouping by file,
 * stale indicators, source health display, and click-to-navigate.
 */

/** CW5/PR09 (P4a-2): preset applied when an explorer problem badge opens the
 *  panel. WAS: a bare filename dropped into the SUBSTRING search — "Main.kt"
 *  also matched MainViewModel.kt and every message containing "main". NOW:
 *  the preset is a file path consumed as an EXACT-FILE filter (path equality
 *  or basename-end match), never as message text. Accepts full paths (from
 *  the explorer badge) and degrades a bare filename to basename-end matching.
 *  Empty string = no preset. */
internal var ProblemsPreset by mutableStateOf("")

/** PR07 (P4a-1): the bottom-tab PROBLEMS menu commands, made REAL — the old
 *  "Filter"/"Show Errors Only" entries fired notifications and changed nothing
 *  (placebo family, PR07). The menu writes a command + monotonic token here;
 *  the panel consumes it in composition and applies the SAME filter state the
 *  panel's own chips drive. Show-all restores every severity.
 *  FOCUS_SEARCH opens the panel's search box (requestFocus). */
enum class ProblemsMenuCommand { FOCUS_SEARCH, ERRORS_ONLY, SHOW_ALL }
internal var ProblemsMenuSignal by mutableStateOf<Pair<ProblemsMenuCommand, Long>?>(null)

@Composable
fun AdvancedProblemsPanel(
    onJumpToSource: (filePath: String, line: Int, column: Int) -> Unit,
    panelBg: Color = Color(0xFF1E1E1E),
    dividerColor: Color = Color(0xFF2D2D30),
    tabTextInactive: Color = Color(0xFF858585),
    tabTextActive: Color = Color(0xFFCCCCCC),
) {
    // PR14 (P4a-1): the list-derived reads below use derivedStateOf, NOT
    // remember(list) — a SnapshotStateList keyed into remember is ALWAYS the
    // same object (AbstractList.equals identity fast-path), so it never
    // re-keys and the panel would freeze at its first composition. derived
    // tracks the CONTENT reads and re-evaluates on every mutation (the
    // NotificationDrawerOverlay precedent).
    val counts by remember { derivedStateOf { DiagnosticManager.countBySeverity() } }

    var showErrors by remember { mutableStateOf(true) }
    var showWarnings by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(true) }
    var showHints by remember { mutableStateOf(false) }
    var showStale by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var sourceFilter by remember { mutableStateOf<DiagnosticManager.DiagnosticSource?>(null) }
    var expandedFile by remember { mutableStateOf<String?>(null) }

    // CW5/PR09: consume the explorer-badge preset one-shot as an EXACT-FILE
    // filter (not search text — the user's search box is never touched).
    var fileFilter by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ProblemsPreset) {
        if (ProblemsPreset.isNotEmpty()) {
            fileFilter = ProblemsPreset
            showErrors = true
            showWarnings = true
            showInfo = true
            showHints = true
            ProblemsPreset = ""
        }
    }

    // PR07 (P4a-1): consume bottom-tab menu commands — REAL filter changes,
    // the same state the severity chips drive (not a notification placebo).
    val searchFocus = androidx.compose.runtime.remember { FocusRequester() }
    LaunchedEffect(ProblemsMenuSignal) {
        val sig = ProblemsMenuSignal
        when (sig?.first) {
            ProblemsMenuCommand.ERRORS_ONLY -> { showErrors = true; showWarnings = false; showInfo = false; showHints = false }
            ProblemsMenuCommand.SHOW_ALL -> { showErrors = true; showWarnings = true; showInfo = true; showHints = true }
            ProblemsMenuCommand.FOCUS_SEARCH -> {
                // Not-yet-attached FocusRequester throws IllegalStateException
                // (panel just composed) — swallow; the user can tap the field.
                try { searchFocus.requestFocus() } catch (_: IllegalStateException) {}
            }
            null -> {}
        }
        // One-shot (CW5 preset precedent): consume so a later panel reopen
        // never re-applies a stale menu command over the user's own filters.
        if (sig != null) ProblemsMenuSignal = null
    }

    // Apply filters
    // PR14: derived keys on the CONTENT (see above); severity/search/source
    // keys keep the derived lambda recreated when THOSE change.
    val filteredDiagnostics by remember(
        showErrors, showWarnings, showInfo, showHints, showStale, searchQuery, sourceFilter, fileFilter
    ) { derivedStateOf { DiagnosticManager.diagnostics.filter { d ->
            // Stale filter
            if (!showStale && d.isStale) return@filter false

            // PR09: exact-file preset filter — path equality or basename-end
            // match; message text is NEVER matched (that was the old bug).
            if (fileFilter != null) {
                val ff = fileFilter!!
                val pathMatch = d.filePath == ff || d.filePath.endsWith("/" + ff.substringAfterLast('/'))
                if (!pathMatch) return@filter false
            }

            // Severity filter
            val severityOk = when (d.severity) {
                DiagnosticManager.Severity.ERROR -> showErrors
                DiagnosticManager.Severity.WARNING -> showWarnings
                DiagnosticManager.Severity.INFO -> showInfo
                DiagnosticManager.Severity.HINT -> showHints
            }
            if (!severityOk) return@filter false

            // Source filter
            if (sourceFilter != null && d.source != sourceFilter) return@filter false

            // Search filter
            if (searchQuery.isNotBlank()) {
                val q = searchQuery.lowercase()
                val matches = d.message.lowercase().contains(q) ||
                        d.filePath.lowercase().contains(q) ||
                        d.code?.lowercase()?.contains(q) == true ||
                        d.sourceName?.lowercase()?.contains(q) == true ||
                        d.sourceId.lowercase().contains(q)
                if (!matches) return@filter false
            }

            true
        } } }

    // Group by file — remember keyed on the derived VALUE (a fresh list each
    // evaluation; structural equals re-keys correctly, unlike the raw list object).
    val groupedByFile = remember(filteredDiagnostics) {
        filteredDiagnostics.groupBy { it.filePath }
            .toSortedMap()
    }

    // Sort within each file: line -> column -> severity
    val sortedGroups = remember(groupedByFile) {
        groupedByFile.mapValues { (_, diags) ->
            diags.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }))
        }
    }

    Column(Modifier.fillMaxSize().background(panelBg)) {
        // ── Header ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "PROBLEMS" + if (counts.total > 0) " (${counts.total})" else "",
                fontSize = 11.sp, color = tabTextInactive,
                modifier = Modifier.weight(1f),
            )
            // Severity count chips
            if (counts.errors > 0) {
                SeverityChip("${counts.errors}", Color(0xFFF44747), "errors")
            }
            if (counts.warnings > 0) {
                Spacer(Modifier.width(6.dp))
                SeverityChip("${counts.warnings}", Color(0xFFCCA700), "warnings")
            }
            if (counts.info > 0) {
                Spacer(Modifier.width(6.dp))
                SeverityChip("${counts.info}", Color(0xFF75BEFF), "info")
            }
        }
        HorizontalDivider(color = dividerColor)

        // ── Filter bar ────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Error toggle
            FilterToggle("E", showErrors, Color(0xFFF44747)) { showErrors = !showErrors }
            FilterToggle("W", showWarnings, Color(0xFFCCA700)) { showWarnings = !showWarnings }
            FilterToggle("I", showInfo, Color(0xFF75BEFF)) { showInfo = !showInfo }

            // Source filter dropdown (compact)
            if (sourceFilter != null) {
                IconButton(onClick = { sourceFilter = null }, modifier = Modifier.size(24.dp)) {
                    Text("×", color = tabTextInactive, fontSize = 14.sp)
                }
                Text(sourceFilter!!.name, color = tabTextInactive, fontSize = 9.sp, maxLines = 1)
            }
        }

        // ── Search bar ────────────────────────────────────────────────────
        // PR07: FOCUS_SEARCH from the bottom-tab menu lands here.
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search problems...", fontSize = 11.sp, color = tabTextInactive) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = tabTextInactive, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .focusRequester(searchFocus),
            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 11.sp, color = tabTextActive),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = dividerColor,
                unfocusedBorderColor = dividerColor,
            ),
        )
        // PR09: dismissible exact-file filter chip (from the explorer badge preset).
        if (fileFilter != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "File: " + fileFilter!!.substringAfterLast('/'),
                    fontSize = 10.sp, color = tabTextActive,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(tabTextActive.copy(alpha = 0.08f))
                        .clickable { fileFilter = null }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text("  (tap to clear)", fontSize = 9.sp, color = tabTextInactive)
            }
        }
        HorizontalDivider(color = dividerColor)

        // ── Problem list ──────────────────────────────────────────────────
        if (sortedGroups.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
                Text(
                    if (counts.total == 0) "✓  No problems detected." else "No problems match current filters.",
                    fontSize = 13.sp, color = Color(0xFF717171),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                sortedGroups.forEach { (filePath, diags) ->
                    // PR08 (P4a-2): task-FAILED and "What went wrong" rows arrive
                    // with file="" — the header previously rendered an EMPTY file
                    // name for them. Honest label: they are build-output rows, not
                    // file rows; their rows stay but never enter the jump chain.
                    val fileName = if (filePath.isEmpty()) "(build output)" else filePath.substringAfterLast("/")
                    val fileErrors = diags.count { it.severity == DiagnosticManager.Severity.ERROR && !it.isStale }
                    val fileWarnings = diags.count { it.severity == DiagnosticManager.Severity.WARNING && !it.isStale }

                    item(key = "header_$filePath") {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { expandedFile = if (expandedFile == filePath) null else filePath }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.ExpandMore,
                                null,
                                tint = tabTextInactive,
                                modifier = Modifier.size(14.dp).let {
                                    if (expandedFile == filePath) it else it
                                },
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                fileName,
                                fontSize = 11.sp, color = tabTextActive, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            if (fileErrors > 0) {
                                Text(" $fileErrors", fontSize = 10.sp, color = Color(0xFFF44747))
                            }
                            if (fileWarnings > 0) {
                                Text(" $fileWarnings", fontSize = 10.sp, color = Color(0xFFCCA700))
                            }
                            // Stale indicator
                            val staleCount = diags.count { it.isStale }
                            if (staleCount > 0) {
                                Text(" ⟳$staleCount", fontSize = 10.sp, color = Color(0xFF666666))
                            }
                        }
                    }

                    if (expandedFile == filePath || sortedGroups.size == 1) {
                        items(diags, key = { it.id }) { diag ->
                            DiagnosticRow(
                                diagnostic = diag,
                                // PR08: pathless rows (Task FAILED / What went wrong)
                                // render but never jump — the old behavior sent ""
                                // into the jump chain, which matched no tab and
                                // opened a broken empty-path tab (BUG-A family).
                                onClick = {
                                    if (diag.filePath.isNotEmpty()) {
                                        onJumpToSource(diag.filePath, diag.range.startLine, diag.range.startColumn)
                                    }
                                },
                                // PR05 (P4a-1): related locations finally render —
                                // each taps through to its own file:line.
                                onJumpRelated = { rel ->
                                    onJumpToSource(rel.filePath, rel.line, rel.column)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticRow(
    diagnostic: DiagnosticManager.Diagnostic,
    onClick: () -> Unit,
    // PR05 (P4a-1): relatedInformation was parsed into every LSP row but never
    // rendered — the actionable context for duplicate-class/unused-import
    // chains existed in the model and was invisible.
    onJumpRelated: (DiagnosticManager.RelatedInfo) -> Unit,
) {
    val (icon, color) = when (diagnostic.severity) {
        DiagnosticManager.Severity.ERROR -> Icons.Default.Cancel to Color(0xFFE51400)
        DiagnosticManager.Severity.WARNING -> Icons.Default.Warning to Color(0xFFCCA700)
        DiagnosticManager.Severity.INFO -> Icons.Default.Info to Color(0xFF007ACC)
        DiagnosticManager.Severity.HINT -> Icons.Default.Lightbulb to Color(0xFF6A9955)
    }

    Column(
        Modifier
            .fillMaxWidth()
            // PR08: a pathless row is not clickable — there is nothing to jump to.
            .let { m -> if (diagnostic.filePath.isNotEmpty()) m.clickable { onClick() } else m }
            .padding(start = 28.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (diagnostic.isStale) color.copy(alpha = 0.4f) else color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                diagnostic.message,
                fontSize = 11.sp,
                color = if (diagnostic.isStale) Color(0xFF666666) else Color(0xFFCCCCCC),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Source badge
            val sourceLabel = diagnostic.sourceName ?: diagnostic.sourceId
            if (sourceLabel.isNotEmpty()) {
                Text(
                    " $sourceLabel",
                    fontSize = 9.sp,
                    color = Color(0xFF6A6A6A),
                    maxLines = 1,
                )
            }
            // Line:col
            Text(
                " ${diagnostic.range.startLine}:${diagnostic.range.startColumn}",
                fontSize = 9.sp,
                color = Color(0xFF6A6A6A),
            )
            // Code
            diagnostic.code?.let {
                Text(" [$it]", fontSize = 9.sp, color = Color(0xFF6A6A6A), maxLines = 1)
            }
        }
        // PR05 (P4a-1): related locations (VS Code markers-view parity) — the
        // "related information" LSP servers send for duplicate-import chains,
        // includes-with-usage, etc. Tappable lines, dimmed like the parent's
        // meta; a related entry without a usable location renders as plain text.
        diagnostic.relatedInformation.takeIf { it.isNotEmpty() }?.let { related ->
            related.forEach { rel ->
                val location = if (rel.filePath.isNotEmpty()) " " + rel.filePath.substringAfterLast('/') + ":" + rel.line else ""
                val dim = if (diagnostic.isStale) Color(0xFF4A4A4A) else Color(0xFF8A8A8A)
                Text(
                    "↳ " + rel.message + location,
                    fontSize = 10.sp,
                    color = dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 18.dp, top = 1.dp)
                        .let { m ->
                            if (rel.filePath.isNotEmpty()) m.clickable { onJumpRelated(rel) } else m
                        },
                )
            }
        }
    }
}

@Composable
private fun SeverityChip(text: String, color: Color, label: String) {
    Box(
        Modifier.background(color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FilterToggle(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(22.dp)
            .background(
                if (selected) color.copy(alpha = 0.2f) else Color(0xFF2D2D2D),
                shape = RoundedCornerShape(4.dp),
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            color = if (selected) color else Color(0xFF555555),
            fontWeight = FontWeight.Bold,
        )
    }
}
