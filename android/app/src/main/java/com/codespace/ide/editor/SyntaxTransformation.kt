package com.codespace.ide.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.EditorColors

/**
 * Applies syntax highlighting + lint error squiggles + code folding as a
 * VisualTransformation.
 *
 * When [foldedLineIndices] is non-empty the transformation:
 *   1. Removes every folded line from the displayed text.
 *   2. Replaces each contiguous block of folded lines with a single "···" token.
 *   3. Provides an [OffsetMapping] so that cursor movement stays correct.
 */
class SyntaxTransformation(
    private val language: Language,
    private val colors: EditorColors,
    private val lintErrors: List<LintError> = emptyList(),
    private val foldedLineIndices: Set<Int> = emptySet(),
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        // ── Step 1: build folding map ─────────────────────────────────────
        if (foldedLineIndices.isEmpty()) {
            return applyHighlightAndLint(text, OffsetMapping.Identity)
        }

        val raw = text.text
        val lines = raw.split("\n")

        // Build: originalOffset → transformedOffset (and reverse)
        // Also build the collapsed display string.
        val displaySb   = StringBuilder()
        // Maps from original char index → display char index
        val origToTrans = IntArray(raw.length + 1) { it }  // default: identity
        // Maps from display char index → original char index (built as we go)
        val transToOrig = mutableListOf<Int>()

        var origIdx = 0   // walking through original char positions
        var dispIdx = 0   // walking through display char positions

        var _lineNum = 0
        var inFoldBlock = false

        for ((idx, line) in lines.withIndex()) {
            val lineStart = origIdx
            val lineEnd   = origIdx + line.length  // exclusive of the \n

            if (foldedLineIndices.contains(idx)) {
                // Folded line — collapse into ··· (only emit once per block)
                if (!inFoldBlock) {
                    val placeholder = "\u00B7\u00B7\u00B7"  // ···
                    displaySb.append(placeholder)
                    repeat(placeholder.length) { transToOrig.add(lineStart) }
                    dispIdx += placeholder.length
                    inFoldBlock = true
                }
                // All original chars in this folded line map to the placeholder start
                for (c in lineStart until lineEnd) {
                    origToTrans[c] = (dispIdx - "\u00B7\u00B7\u00B7".length)
                        .coerceAtLeast(0)
                }
                origIdx = lineEnd
                // Handle the \n after this line
                if (idx < lines.lastIndex) {
                    origToTrans[origIdx] = (dispIdx - "\u00B7\u00B7\u00B7".length)
                        .coerceAtLeast(0)
                    origIdx++
                }
            } else {
                inFoldBlock = false
                // Visible line — copy verbatim
                displaySb.append(line)
                for (c in lineStart until lineEnd) {
                    origToTrans[c] = dispIdx + (c - lineStart)
                    transToOrig.add(c)
                }
                dispIdx += line.length
                origIdx = lineEnd
                // Append \n separator (except after last line)
                if (idx < lines.lastIndex) {
                    displaySb.append('\n')
                    origToTrans[origIdx] = dispIdx
                    transToOrig.add(origIdx)
                    origIdx++
                    dispIdx++
                }
            }
        }
        // Sentinel
        origToTrans[raw.length] = dispIdx
        transToOrig.add(raw.length)

        val displayStr = displaySb.toString()
        val origToTransFinal = origToTrans.copyOf()
        val transToOrigFinal = transToOrig.toIntArray()

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                origToTransFinal.getOrElse(offset.coerceAtLeast(0)) { dispIdx }

            override fun transformedToOriginal(offset: Int): Int =
                transToOrigFinal.getOrElse(offset.coerceAtLeast(0)) { raw.length }
        }

        // ── Step 2: syntax-highlight the *display* string ─────────────────
        val highlighted = SyntaxHighlighter.highlight(displayStr, language, colors)

        // ── Step 3: overlay lint squiggles (re-mapped to display offsets) ──
        if (lintErrors.isEmpty()) return TransformedText(highlighted, offsetMapping)

        val withLint = buildAnnotatedString {
            append(highlighted)
            for (err in lintErrors) {
                val tStart = origToTransFinal.getOrElse(
                    err.start.coerceIn(0, raw.length)) { 0 }
                val tEnd   = origToTransFinal.getOrElse(
                    err.end.coerceIn(0, raw.length)) { 0 }
                if (tStart < tEnd) {
                    addStyle(
                        SpanStyle(
                            color = Color(0xFFFF4444),
                            textDecoration = TextDecoration.Underline,
                            background = Color(0x22FF0000),
                        ),
                        tStart, tEnd,
                    )
                }
            }
        }
        return TransformedText(withLint, offsetMapping)
    }

    // ── No-fold path (original logic) ─────────────────────────────────────

    private fun applyHighlightAndLint(
        text: AnnotatedString,
        offsetMapping: OffsetMapping,
    ): TransformedText {
        val highlighted = SyntaxHighlighter.highlight(text.text, language, colors)
        if (lintErrors.isEmpty()) return TransformedText(highlighted, offsetMapping)

        val withLint = buildAnnotatedString {
            append(highlighted)
            for (err in lintErrors) {
                val start = err.start.coerceIn(0, text.text.length)
                val end   = err.end.coerceIn(start, text.text.length)
                if (start < end) {
                    addStyle(
                        SpanStyle(
                            color = Color(0xFFFF4444),
                            textDecoration = TextDecoration.Underline,
                            background = Color(0x22FF0000),
                        ),
                        start, end,
                    )
                }
            }
        }
        return TransformedText(withLint, offsetMapping)
    }
}
