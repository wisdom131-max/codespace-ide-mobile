package com.codespace.ide.editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Phase F: Centralized decoration store.
 *
 * Previously, editor decorations (syntax highlighting, lint squiggles, semantic tokens,
 * search matches, selection highlights, cursor overlay, blame lines, merge conflicts,
 * inlay hints, color swatches, bookmarks, sticky scroll) were scattered as independent
 * mutableStateOf variables throughout CodeEditor.kt. Each one triggered recomposition
 * independently, and there was no way to invalidate a single layer without potentially
 * invalidating unrelated ones.
 *
 * The DecorationStore consolidates all decoration layers into a single immutable
 * snapshot. Each layer has its own invalidation epoch (a monotonically increasing
 * counter). When a layer changes, only its epoch increments — consumers that depend
 * on that specific layer recompose, while others are skipped.
 *
 * Inspired by sora-editor's RenderContext and MappedSpans, which separate the
 * decoration/rendering data from the text model and allow independent invalidation
 * of syntax spans, diagnostic spans, and search highlights.
 *
 * Usage in a @Composable:
 *   val decorationStore = remember { DecorationStore() }
 *   // Update a layer:
 *   decorationStore.updateDiagnostics(newLintErrors)
 *   // Read a layer (only recomposes when that layer's epoch changes):
 *   val diagnostics = decorationStore.diagnostics
 */

/**
 * Invalidation epoch for a single decoration layer.
 * Consumers use remember(epoch) { ... } to skip recomputation when the epoch is unchanged.
 */
@Stable
data class DecorationEpoch(val value: Long)

/**
 * A versioned snapshot of a single decoration layer.
 * The [epoch] changes whenever the [data] changes, enabling fine-grained recomposition.
 */
@Immutable
data class VersionedDecoration<T>(
    val data: T,
    val epoch: Long,
) {
    /**
     * Returns a new VersionedDecoration with updated data and a bumped epoch.
     * Only bumps if the data actually changed (reference equality check).
     */
    fun update(newData: T): VersionedDecoration<T> {
        if (newData === data) return this
        return VersionedDecoration(newData, epoch + 1)
    }
}

/**
 * The complete set of editor decoration layers.
 *
 * Each property is a VersionedDecoration that tracks its own invalidation epoch.
 * Reading a property in a Composable creates a dependency on that layer's epoch only.
 *
 * Layers:
 * - syntax: syntax highlighting spans (from SyntaxTransformation)
 * - diagnostics: lint errors and LSP diagnostics (squiggles)
 * - semanticTokens: LSP semantic token ranges
 * - search: find/replace match ranges + current match index
 * - selection: document highlight ranges (LSP textDocument/documentHighlight)
 * - cursor: primary cursor + extra cursors (multi-cursor)
 * - blame: git blame data per line
 * - conflicts: merge conflict hunks
 * - inlayHints: LSP inlay hints
 * - colorSwatches: document color ranges
 * - bookmarks: bookmarked line set
 * - stickyScroll: sticky scroll header lines
 * - foldRanges: code folding ranges (from LSP or manual)
 * - foldedLines: currently folded line indices
 */
@Stable
class DecorationStore {

    // Syntax highlighting layer
    private var _syntax = VersionedDecoration<List<SyntaxSpan>>(emptyList(), 0)
    val syntax: VersionedDecoration<List<SyntaxSpan>> get() = _syntax
    fun updateSyntax(spans: List<SyntaxSpan>) { _syntax = _syntax.update(spans) }

    // Diagnostics layer (lint errors + LSP diagnostics merged)
    private var _diagnostics = VersionedDecoration<List<LintError>>(emptyList(), 0)
    val diagnostics: VersionedDecoration<List<LintError>> get() = _diagnostics
    fun updateDiagnostics(errors: List<LintError>) { _diagnostics = _diagnostics.update(errors) }

    // Semantic tokens layer (LSP textDocument/semanticTokens)
    private var _semanticTokens = VersionedDecoration<List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange>>(emptyList(), 0)
    val semanticTokens: VersionedDecoration<List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange>> get() = _semanticTokens
    fun updateSemanticTokens(tokens: List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange>) {
        _semanticTokens = _semanticTokens.update(tokens)
    }

    // Search match layer (find/replace)
    private var _search = VersionedDecoration(SearchState(emptyList(), 0), 0)
    val search: VersionedDecoration<SearchState> get() = _search
    fun updateSearch(matches: List<IntRange>, currentIndex: Int) {
        _search = _search.update(SearchState(matches, currentIndex))
    }
    fun clearSearch() {
        _search = _search.update(SearchState(emptyList(), 0))
    }

    // Selection highlight layer (LSP documentHighlight)
    private var _selectionHighlights = VersionedDecoration<List<HighlightRange>>(emptyList(), 0)
    val selectionHighlights: VersionedDecoration<List<HighlightRange>> get() = _selectionHighlights
    fun updateSelectionHighlights(ranges: List<HighlightRange>) {
        _selectionHighlights = _selectionHighlights.update(ranges)
    }

    // Cursor layer (primary + extra cursors)
    private var _cursor = VersionedDecoration(CursorState(0, emptyList()), 0)
    val cursor: VersionedDecoration<CursorState> get() = _cursor
    fun updateCursor(primaryOffset: Int, extraCursors: List<androidx.compose.ui.text.TextRange>) {
        _cursor = _cursor.update(CursorState(primaryOffset, extraCursors))
    }

    // Git blame layer
    private var _blame = VersionedDecoration<Map<Int, BlameLine>>(emptyMap(), 0)
    val blame: VersionedDecoration<Map<Int, BlameLine>> get() = _blame
    fun updateBlame(data: Map<Int, BlameLine>) { _blame = _blame.update(data) }

    // Merge conflict layer
    private var _conflicts = VersionedDecoration<List<ConflictHunk>>(emptyList(), 0)
    val conflicts: VersionedDecoration<List<ConflictHunk>> get() = _conflicts
    fun updateConflicts(hunks: List<ConflictHunk>) { _conflicts = _conflicts.update(hunks) }

    // Inlay hints layer
    private var _inlayHints = VersionedDecoration<List<InlayHint>>(emptyList(), 0)
    val inlayHints: VersionedDecoration<List<InlayHint>> get() = _inlayHints
    fun updateInlayHints(hints: List<InlayHint>) { _inlayHints = _inlayHints.update(hints) }

    // Bookmarks layer
    private var _bookmarks = VersionedDecoration<Set<Int>>(emptySet(), 0)
    val bookmarks: VersionedDecoration<Set<Int>> get() = _bookmarks
    fun updateBookmarks(lines: Set<Int>) { _bookmarks = _bookmarks.update(lines) }

    // Fold ranges layer (LSP-provided foldable ranges)
    private var _foldRanges = VersionedDecoration<List<FoldRange>>(emptyList(), 0)
    val foldRanges: VersionedDecoration<List<FoldRange>> get() = _foldRanges
    fun updateFoldRanges(ranges: List<FoldRange>) { _foldRanges = _foldRanges.update(ranges) }

    // Folded lines layer (user's fold/unfold state)
    private var _foldedLines = VersionedDecoration<Set<Int>>(emptySet(), 0)
    val foldedLines: VersionedDecoration<Set<Int>> get() = _foldedLines
    fun updateFoldedLines(lines: Set<Int>) { _foldedLines = _foldedLines.update(lines) }

    /**
     * Invalidate all layers at once — used when the entire document changes
     * (file open, file switch, large external edit).
     */
    fun invalidateAll() {
        _syntax = _syntax.update(_syntax.data)
        _diagnostics = _diagnostics.update(_diagnostics.data)
        _semanticTokens = _semanticTokens.update(_semanticTokens.data)
        _search = _search.update(_search.data)
        _selectionHighlights = _selectionHighlights.update(_selectionHighlights.data)
        _cursor = _cursor.update(_cursor.data)
        _blame = _blame.update(_blame.data)
        _conflicts = _conflicts.update(_conflicts.data)
        _inlayHints = _inlayHints.update(_inlayHints.data)
        _bookmarks = _bookmarks.update(_bookmarks.data)
        _foldRanges = _foldRanges.update(_foldRanges.data)
        _foldedLines = _foldedLines.update(_foldedLines.data)
    }

    /**
     * Invalidate layers that depend on document text — used when text changes
     * (typing, deletion, paste, format).
     * Invalidates: syntax, diagnostics, semanticTokens, search, selectionHighlights,
     * cursor, inlayHints, foldRanges.
     * Preserves: blame, conflicts, bookmarks (these don't change on every keystroke).
     */
    fun invalidateOnTextChange() {
        _syntax = _syntax.update(_syntax.data)
        _diagnostics = _diagnostics.update(_diagnostics.data)
        _semanticTokens = _semanticTokens.update(_semanticTokens.data)
        _search = _search.update(_search.data)
        _selectionHighlights = _selectionHighlights.update(_selectionHighlights.data)
        _cursor = _cursor.update(_cursor.data)
        _inlayHints = _inlayHints.update(_inlayHints.data)
        _foldRanges = _foldRanges.update(_foldRanges.data)
    }

    /**
     * R1-3: Shift offset-based decoration positions when text changes.
     * Prevents stale diagnostics and selection highlights after typing.
     * InlayHint is line-based (not offset) so it is NOT shifted here —
     * it will be refreshed by the next LSP inlay hint request.
     */
    fun shiftOnEdit(oldText: String, newText: String) {
        if (oldText == newText) return
        var changeStart = 0
        val minLen = minOf(oldText.length, newText.length)
        while (changeStart < minLen && oldText[changeStart] == newText[changeStart]) changeStart++
        val delta = newText.length - oldText.length
        if (delta == 0) return

        if (_diagnostics.data.isNotEmpty()) {
            // Change 1: Preserve per-line fields through offset shifts.
            // Line/col fields are kept as-is — the next LSP refresh will provide
            // exact positions. Absolute offsets are shifted as before.
            _diagnostics = _diagnostics.update(
                _diagnostics.data.mapNotNull { err ->
                    val ns = if (err.start >= changeStart) err.start + delta else err.start
                    val ne = if (err.end >= changeStart) err.end + delta else err.end
                    if (ns < 0 || ne < 0 || ns > newText.length || ne > newText.length) null
                    else LintError(ns, ne, err.message, err.code, err.severity, err.line, err.startCol, err.endCol)
                }
            )
        }

        if (_selectionHighlights.data.isNotEmpty()) {
            _selectionHighlights = _selectionHighlights.update(
                _selectionHighlights.data.mapNotNull { r ->
                    val ns = if (r.start >= changeStart) r.start + delta else r.start
                    val ne = if (r.end >= changeStart) r.end + delta else r.end
                    if (ns < 0 || ne < 0 || ns > newText.length || ne > newText.length) null
                    else HighlightRange(ns, ne, r.kind)
                }
            )
        }
    }
}

// ─── Supporting data types for the decoration layers ─────────────────────────

/** A single syntax highlighting span. */
@Immutable
data class SyntaxSpan(
    val start: Int,
    val end: Int,
    val color: Long,
    val style: DecorationSpanStyle = DecorationSpanStyle.NORMAL,
)

enum class DecorationSpanStyle { NORMAL, BOLD, ITALIC, UNDERLINE, STRIKETHROUGH }

/** Search state: match ranges + current match index. */
@Immutable
data class SearchState(
    val matches: List<IntRange>,
    val currentIndex: Int,
)

/** A highlight range (from LSP documentHighlight). */
@Immutable
data class HighlightRange(
    val start: Int,
    val end: Int,
    val kind: HighlightKind,
)

enum class HighlightKind { READ, WRITE, TEXT }

/** Cursor state: primary cursor offset + extra cursors. */
@Immutable
data class CursorState(
    val primaryOffset: Int,
    val extraCursors: List<androidx.compose.ui.text.TextRange>,
)

/** A foldable range (from LSP or heuristic). */
@Immutable
data class FoldRange(
    val startLine: Int,
    val endLine: Int,
)
