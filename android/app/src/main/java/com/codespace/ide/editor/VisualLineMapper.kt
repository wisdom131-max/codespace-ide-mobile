package com.codespace.ide.editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * Phase G: Visual line mapper — replaces the displayLines list.
 *
 * Previously, CodeEditor.kt built a `displayLines: List<Pair<Int, String>>` on every
 * text or fold change. This list reified every visible line's content — wasteful for
 * large files and unable to handle word-wrap (which splits a single document line
 * into multiple visual lines).
 *
 * The VisualLineMapper is a lightweight abstraction that:
 * 1. Maps document lines (0-based) to visual lines (0-based), accounting for:
 *    - Code folding: folded ranges collapse to a single placeholder visual line.
 *    - Word wrap: long document lines split into multiple visual lines.
 * 2. Maps visual lines back to document lines.
 * 3. Computes the total visual line count.
 * 4. Provides line content for rendering (deferred — no pre-built list).
 * 5. Computes scroll offsets in visual-line space.
 *
 * Inspired by sora-editor's ContentLineLayout and Layout definesRow / isRowVisible
 * system, which maintains a bidirectional document-line ↔ visual-line mapping that
 * handles both folding and word-wrap.
 *
 * Usage:
 *   val mapper = VisualLineMapper(text, foldedLineIndices, wrapWidthPx, charWidthPx, tabSize)
 *   val totalVisualLines = mapper.visualLineCount
 *   val docLine = mapper.visualToDocLine(visualLine)
 *   val visualLine = mapper.docToVisualLine(docLine)
 *   val lineContent = mapper.getVisualLineText(text, visualLine)
 */

/**
 * Describes a single visual line.
 * A visual line is either:
 * - A normal document line (possibly a sub-line of a wrapped document line)
 * - A fold placeholder ("···")
 */
@Immutable
sealed class VisualLine {
    /** A normal document line at [docLine], possibly wrapped at [wrapSegment]. */
    data class DocLine(
        val docLine: Int,
        /** 0-based segment index if this line is wrapped. 0 = first segment. */
        val wrapSegment: Int = 0,
        /** Total number of wrap segments for this document line (1 if not wrapped). */
        val totalSegments: Int = 1,
    ) : VisualLine()

    /** A fold placeholder line — shown as "···" for a folded range starting at [foldStart]. */
    data class FoldPlaceholder(
        val foldStart: Int,
        val foldEnd: Int,
    ) : VisualLine()
}

/**
 * The visual line mapper.
 *
 * Construct this whenever the document text, fold state, or wrap width changes.
 * The mapper builds an internal index of visual lines on construction (O(n) where
 * n = document line count), then provides O(log n) or O(1) lookups.
 *
 * For files under ~5000 lines (the typical case for a mobile IDE), construction
 * is fast enough to do on every text change. For larger files, the mapper can
 * be constructed lazily for only the visible range.
 */
@Stable
class VisualLineMapper(
    private val text: String,
    private val foldedLineIndices: Set<Int>,
    /**
     * If > 0, word-wrap is enabled and lines wider than this (in pixels) are split.
     * If <= 0, no word-wrap — each document line is exactly one visual line (unless folded).
     */
    private val wrapWidthPx: Float = 0f,
    /** Approximate character width in pixels — used for wrap calculations. */
    private val charWidthPx: Float = 0f,
    /** Tab size in character columns — tabs expand to this many columns. */
    private val tabSize: Int = 4,
) {
    // Precompute newline positions for line lookup
    private val lineStarts: IntArray
    private val lineCount: Int

    // Visual line index: maps visual line index → VisualLine descriptor
    private val visualLines: List<VisualLine>

    // Reverse index: maps document line → first visual line index (for forward lookup)
    private val docToVisualIndex: IntArray

    init {
        // Build line starts array
        val starts = mutableListOf(0)
        val len = text.length
        for (i in 0 until len) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        lineStarts = starts.toIntArray()
        lineCount = lineStarts.size

        // Build visual lines list
        val vLines = mutableListOf<VisualLine>()
        // Build reverse index (doc line → first visual line)
        val d2v = IntArray(lineCount) { -1 }

        var docLine = 0
        while (docLine < lineCount) {
            if (docLine in foldedLineIndices) {
                // Check if this is the start of a folded block
                val prevFolded = docLine > 0 && (docLine - 1) in foldedLineIndices
                if (!prevFolded) {
                    // Find the end of the folded block
                    var foldEnd = docLine
                    while (foldEnd < lineCount && foldEnd in foldedLineIndices) {
                        foldEnd++
                    }
                    d2v[docLine] = vLines.size
                    vLines.add(VisualLine.FoldPlaceholder(docLine, foldEnd - 1))
                }
                // Skip folded lines (they don't get individual visual lines)
                docLine++
            } else {
                d2v[docLine] = vLines.size
                if (wrapWidthPx > 0 && charWidthPx > 0) {
                    // Word-wrap: split this line into segments
                    val lineText = getLineText(docLine)
                    val visualWidth = computeVisualWidth(lineText)
                    val segments = if (visualWidth <= wrapWidthPx) 1
                    else maxOf(1, ((visualWidth / wrapWidthPx).toInt() + 1))
                    for (seg in 0 until segments) {
                        vLines.add(VisualLine.DocLine(docLine, seg, segments))
                    }
                } else {
                    // No wrap: one visual line per document line
                    vLines.add(VisualLine.DocLine(docLine, 0, 1))
                }
                docLine++
            }
        }

        visualLines = vLines
        docToVisualIndex = d2v
    }

    /** Total number of visual lines (including fold placeholders and wrapped segments). */
    val visualLineCount: Int get() = visualLines.size

    /** Convert a visual line index to its descriptor. */
    fun getVisualLine(visualLine: Int): VisualLine {
        return visualLines[visualLine.coerceIn(0, visualLines.size - 1)]
    }

    /**
     * Convert a visual line index to the corresponding document line (0-based).
     * For fold placeholders, returns the fold start line.
     * For wrapped segments, returns the document line (same for all segments).
     */
    fun visualToDocLine(visualLine: Int): Int {
        val vl = getVisualLine(visualLine)
        return when (vl) {
            is VisualLine.DocLine -> vl.docLine
            is VisualLine.FoldPlaceholder -> vl.foldStart
        }
    }

    /**
     * Convert a document line (0-based) to its first visual line index.
     * If the document line is folded, returns the visual line of the fold placeholder.
     * Returns -1 if the document line doesn't have a visual line (shouldn't happen).
     */
    fun docToVisualLine(docLine: Int): Int {
        val safeLine = docLine.coerceIn(0, lineCount - 1)
        if (safeLine in foldedLineIndices) {
            // Find the fold placeholder for this folded block
            val foldStart = findFoldStart(safeLine)
            return docToVisualIndex.getOrElse(foldStart) { -1 }
        }
        return docToVisualIndex.getOrElse(safeLine) { -1 }
    }

    /**
     * Get the text content to display for a visual line.
     * For normal lines: returns the document line text (or the wrapped segment).
     * For fold placeholders: returns "···".
     */
    fun getVisualLineText(text: String, visualLine: Int): String {
        val vl = getVisualLine(visualLine)
        return when (vl) {
            is VisualLine.FoldPlaceholder -> "\u00b7\u00b7\u00b7"  // "···"
            is VisualLine.DocLine -> {
                if (vl.totalSegments <= 1) {
                    getLineText(vl.docLine)
                } else {
                    // Wrapped line: return the segment text
                    val fullLine = getLineText(vl.docLine)
                    val charsPerSegment = (wrapWidthPx / charWidthPx).toInt().coerceAtLeast(1)
                    val start = vl.wrapSegment * charsPerSegment
                    val end = minOf(start + charsPerSegment, fullLine.length)
                    if (start < fullLine.length) fullLine.substring(start, end) else ""
                }
            }
        }
    }

    /**
     * Get the document line number to show in the gutter for a visual line.
     * For fold placeholders: returns -1 (gutter shows "···" or nothing).
     * For normal lines: returns the document line number (1-based for display).
     */
    fun getGutterLineNumber(visualLine: Int): Int {
        val vl = getVisualLine(visualLine)
        return when (vl) {
            is VisualLine.FoldPlaceholder -> -1
            is VisualLine.DocLine -> vl.docLine
        }
    }

    /**
     * Check if a visual line is a fold placeholder.
     */
    fun isFoldPlaceholder(visualLine: Int): Boolean {
        return getVisualLine(visualLine) is VisualLine.FoldPlaceholder
    }

    /**
     * Check if a document line is the start of a foldable (but not yet folded) region.
     */
    fun isFoldStart(docLine: Int): Boolean {
        return docLine in foldedLineIndices && (docLine == 0 || (docLine - 1) !in foldedLineIndices)
    }

    /**
     * Get the fold range for a document line that is a fold start.
     * Returns null if not a fold start.
     */
    fun getFoldRange(docLine: Int): IntRange? {
        if (!isFoldStart(docLine)) return null
        var end = docLine
        while (end < lineCount && end in foldedLineIndices) {
            end++
        }
        return docLine until end
    }

    /**
     * Get the visual line index for a scroll offset (in pixels).
     * Useful for computing which lines are visible given a scroll position.
     */
    fun scrollPxToVisualLine(scrollPx: Int, lineHeightPx: Float): Int {
        if (lineHeightPx <= 0f) return 0
        return (scrollPx / lineHeightPx).toInt().coerceIn(0, visualLines.size - 1)
    }

    /**
     * Get the pixel offset for a visual line (top of the line).
     */
    fun visualLineToScrollPx(visualLine: Int, lineHeightPx: Float): Float {
        return visualLine.coerceIn(0, visualLines.size - 1) * lineHeightPx
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    private fun getLineText(line: Int): String {
        val start = lineStarts[line]
        val end = if (line + 1 < lineCount) {
            // Line ends at the next newline (exclusive)
            val nextStart = lineStarts[line + 1] - 1
            if (nextStart < start) start else nextStart
        } else {
            text.length
        }
        return text.substring(start, end)
    }

    private fun computeVisualWidth(lineText: String): Float {
        var width = 0f
        for (ch in lineText) {
            width += if (ch == '\t') charWidthPx * tabSize else charWidthPx
        }
        return width
    }

    private fun findFoldStart(line: Int): Int {
        var l = line
        while (l > 0 && l in foldedLineIndices) {
            // Check if previous line is also folded
            if ((l - 1) in foldedLineIndices) {
                l--
            } else {
                break
            }
        }
        return l
    }
}
