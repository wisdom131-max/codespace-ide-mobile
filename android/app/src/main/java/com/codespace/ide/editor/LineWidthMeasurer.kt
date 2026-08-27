package com.codespace.ide.editor

import android.graphics.Paint
import android.graphics.Typeface

/**
 * Paint-based per-line width measurer, inspired by Sora Editor's
 * SingleCharacterWidths + LineBreakLayout.widthMaintainer pattern.
 *
 * Instead of relying on Compose's TextLayoutResult (which is stale during
 * paste, missing lines for large files, and changes on recomposition),
 * this class uses Android's Paint.measureText() to measure each line's
 * pixel width independently of the Compose layout system.
 *
 * Widths are stored per-line in a mutable list and updated incrementally
 * on edit — only the affected lines are re-measured, not the entire
 * document (same as Sora's afterInsert/afterDelete callbacks).
 */
class LineWidthMeasurer(
    private val textSizePx: Float,
    private val typeface: Typeface = Typeface.MONOSPACE,
    private var tabWidth: Int = 4,
    private var gutterWidthPx: Float = 0f,
    private val paddingPx: Float = 32f,
) {
    private val paint = Paint().apply {
        textSize = this@LineWidthMeasurer.textSizePx
        typeface = this@LineWidthMeasurer.typeface
        isAntiAlias = true
    }

    private val lineWidths = mutableListOf<Float>()
    private var maxWidth: Float = 0f

    /**
     * Measure a single line's text width in pixels (excluding gutter).
     * Uses per-character measurement with caching, like Sora's SingleCharacterWidths.
     */
    fun measureLine(text: CharSequence): Float {
        if (text.isEmpty()) return 0f
        var width = 0f
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\t') {
                width += paint.measureText(" ") * tabWidth
            } else if (ch == '\n' || ch == '\r') {
                // skip newlines
            } else {
                width += paint.measureText(charArrayOf(ch), 0, 1)
            }
            i++
        }
        return width
    }

    /**
     * Measure all lines from the full document text.
     * Called on initial file load.
     */
    fun measureAll(text: String) {
        lineWidths.clear()
        maxWidth = 0f
        val lines = text.split('\n')
        for (line in lines) {
            val w = measureLine(line)
            lineWidths.add(w)
            if (w > maxWidth) maxWidth = w
        }
    }

    /**
     * Incrementally update on text change — only re-measure affected lines.
     * Returns the new max width (including gutter + padding).
     *
     * @param oldText the previous full text
     * @param newText the new full text
     */
    fun updateOnEdit(oldText: String, newText: String) {
        // Fast path: if only one line changed, find and update just that line
        val oldLines = oldText.split('\n')
        val newLines = newText.split('\n')

        if (oldLines.size == newLines.size) {
            // Same line count — find changed lines and update only those
            var firstDiff = -1
            var lastDiff = -1
            for (i in newLines.indices) {
                if (i >= oldLines.size || oldLines[i] != newLines[i]) {
                    if (firstDiff == -1) firstDiff = i
                    lastDiff = i
                }
            }
            if (firstDiff == -1) return // no change

            // Update only changed lines
            for (i in firstDiff..lastDiff) {
                if (i < lineWidths.size) {
                    val oldW = lineWidths[i]
                    val newW = measureLine(newLines[i])
                    lineWidths[i] = newW
                    // Fast max recalc: if the changed line was the max and got smaller,
                    // or a non-max line got bigger than max, recompute. Otherwise keep.
                    if (newW > maxWidth) {
                        maxWidth = newW
                    } else if (oldW >= maxWidth && newW < oldW) {
                        // The previous max line got smaller — need full rescan
                        recomputeMax()
                    }
                } else {
                    // New line beyond current array
                    val w = measureLine(newLines[i])
                    lineWidths.add(w)
                    if (w > maxWidth) maxWidth = w
                }
            }
        } else {
            // Line count changed (insertion/deletion of newlines) — re-measure all
            // This is O(n) but only on newline insert/delete, not on every keystroke
            measureAll(newText)
        }
    }

    private fun recomputeMax() {
        var m = 0f
        for (w in lineWidths) {
            if (w > m) m = w
        }
        maxWidth = m
    }

    /**
     * The scrollable content width = widest line + gutter + padding.
     */
    fun getScrollWidth(): Float {
        return maxWidth + gutterWidthPx + paddingPx
    }

    /**
     * The raw max line width (without gutter/padding).
     */
    fun getMaxLineWidth(): Float = maxWidth

    /**
     * Get the width of a specific line (0-indexed).
     */
    fun getLineWidth(lineIndex: Int): Float {
        return if (lineIndex in lineWidths.indices) lineWidths[lineIndex] else 0f
    }

    /**
     * Update paint settings (text size, typeface) and re-measure all.
     */
    fun updateSettings(textSizePx: Float, typeface: Typeface, tabWidth: Int, gutterWidthPx: Float) {
        paint.textSize = textSizePx
        paint.typeface = typeface
        // Re-measure all lines with new settings
        // We need the current text to re-measure — caller must call measureAll() after this
        this.tabWidth = tabWidth
        this.gutterWidthPx = gutterWidthPx
    }
}
