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
    /** P41-W: LSP semantic token ranges overlaid on top of regex highlighting */
    private val semanticTokens: List<com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange> = emptyList(),
    // R1-1: Pre-computed highlight from background thread. Tracks the exact text
    // it was computed from to prevent applying stale highlights to newer text.
    private val precomputedHighlight: AnnotatedString? = null,
    private val precomputedForText: String? = null,
    // Incremental highlighter for synchronous path (avoids O(n) full re-highlight)
    private val incrementalHighlighter: IncrementalHighlighter? = null,
) : VisualTransformation {

    // P50-PERF: Cache the last transformed result so we don't rebuild the AnnotatedString
    // on every recomposition. The filter() method is called frequently by Compose, and
    // rebuilding syntax highlighting for a 5000-line file on every frame would cause jank.
    private var cachedText: String? = null
    private var cachedResult: TransformedText? = null


    // ── Per-line span offset helpers ─────────────────────────────────────
    // Change 1: When lint/semantic spans carry per-line coordinates (line >= 0),
    // compute absolute offsets clamped to that line's actual length — not the
    // global text length. This structurally prevents spans from crossing line
    // boundaries or referencing stale positions after an edit on another line.

    private fun computeLineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray()
    }

    private fun computeLineLengths(text: String, lineStarts: IntArray): IntArray {
        return IntArray(lineStarts.size) { idx ->
            val start = lineStarts[idx]
            val end = if (idx + 1 < lineStarts.size) lineStarts[idx + 1] - 1 else text.length
            (end - start).coerceAtLeast(0)
        }
    }

    /** Compute safe absolute offsets for a LintError using per-line fields when available. */
    private fun safeLintOffsets(
        err: LintError, lineStarts: IntArray, lineLengths: IntArray, textLen: Int,
    ): Pair<Int, Int> {
        if (err.line < 0 || err.startCol < 0) {
            val s = err.start.coerceIn(0, textLen)
            val e = err.end.coerceIn(s, textLen)
            return s to e
        }
        val ls = if (err.line < lineStarts.size) lineStarts[err.line] else 0
        val ll = if (err.line < lineLengths.size) lineLengths[err.line] else 0
        val s = (ls + err.startCol.coerceIn(0, ll)).coerceIn(0, textLen)
        val e = if (err.endCol >= 0) {
            (ls + err.endCol.coerceIn(0, ll)).coerceIn(0, textLen)
        } else {
            (s + 1).coerceIn(0, textLen)
        }
        return s to e
    }

    /** Compute safe absolute offsets for a SemanticRange using per-line fields when available. */
    private fun safeSemanticOffsets(
        tok: com.codespace.ide.lsp.SemanticTokensApplier.SemanticRange,
        lineStarts: IntArray, lineLengths: IntArray, textLen: Int,
    ): Pair<Int, Int> {
        if (tok.line < 0 || tok.startCol < 0) {
            val s = tok.startOffset.coerceIn(0, textLen)
            val e = tok.endOffset.coerceIn(s, textLen)
            return s to e
        }
        val ls = if (tok.line < lineStarts.size) lineStarts[tok.line] else 0
        val ll = if (tok.line < lineLengths.size) lineLengths[tok.line] else 0
        val s = (ls + tok.startCol.coerceIn(0, ll)).coerceIn(0, textLen)
        val e = (ls + tok.endCol.coerceIn(0, ll)).coerceIn(0, textLen)
        return s to e
    }


    // SAFETY NET: Two crash modes are guarded here:
    //
    // 1. Length mismatch (OffsetMapping.Identity): If any highlighter produces an
    //    AnnotatedString whose length differs from the original text, Identity mapping
    //    crashes CoreTextField with "OffsetMapping.originalToTransformed returned
    //    invalid mapping". Fix: fall back to plain unstyled text (still editable).
    //
    // 2. Span range overflow (Accessibility/TalkBack): addStyle() can create spans
    //    that exceed the AnnotatedString's text length (e.g. folding offset mapping
    //    bugs, stale lint/semantic tokens). Compose's accessibility layer crashes
    //    with "setSpan (N ... M) ends beyond length L" when converting to
    //    SpannableString. Fix: rebuild the AnnotatedString with only valid spans.
    private fun sanitizeResult(
        result: TransformedText,
        original: AnnotatedString,
        isIdentityMapping: Boolean,
    ): TransformedText {
        val text = result.text
        val len = text.length

        // Guard 1: length mismatch for Identity mapping
        if (isIdentityMapping && len != original.text.length) {
            return TransformedText(original, OffsetMapping.Identity)
        }

        // Guard 2: strip spans that exceed text length
        val hasBadSpans = text.spanStyles.any { range ->
            range.start >= len || range.end > len || range.start >= range.end
        }
        if (!hasBadSpans) return result  // fast path — all spans valid

        val sanitized = buildAnnotatedString {
            append(text.text)
            for (range in text.spanStyles) {
                val s = range.start.coerceIn(0, len)
                val e = range.end.coerceIn(s, len)
                if (s < e) {
                    addStyle(range.item, s, e)
                }
            }
        }
        return TransformedText(sanitized, result.offsetMapping)
    }

    // Convenience for non-Identity mappings (folding path) — only sanitize spans
    private fun sanitizeSpans(result: TransformedText): TransformedText {
        val text = result.text
        val len = text.length
        val hasBadSpans = text.spanStyles.any { range ->
            range.start >= len || range.end > len || range.start >= range.end
        }
        if (!hasBadSpans) return result
        val sanitized = buildAnnotatedString {
            append(text.text)
            for (range in text.spanStyles) {
                val s = range.start.coerceIn(0, len)
                val e = range.end.coerceIn(s, len)
                if (s < e) {
                    addStyle(range.item, s, e)
                }
            }
        }
        return TransformedText(sanitized, result.offsetMapping)
    }

    override fun filter(text: AnnotatedString): TransformedText {
        // Return cached result if text hasn't changed
        if (cachedText == text.text && cachedResult != null) {
            return cachedResult!!
        }
        // ── Step 1: build folding map ─────────────────────────────────────
        if (foldedLineIndices.isEmpty()) {
            // R1-1: Use pre-computed highlight if it was computed for the EXACT current text.
            // This prevents wrong-color flicker when user types during the 100ms debounce.
            if (precomputedHighlight != null && precomputedForText == text.text) {
                val result = applyLintAndSemantic(precomputedHighlight, text.text, OffsetMapping.Identity)
                val safeResult = sanitizeResult(result, text, isIdentityMapping = true)
                cachedText = text.text
                cachedResult = safeResult
                return safeResult
            }
            // Fallback: synchronous highlighting (small files or precomputed not ready yet)
            // Use incremental highlighter if available to avoid O(n) full re-highlight
            val result = if (incrementalHighlighter != null) {
                val incrHighlight = incrementalHighlighter.highlight(text.text, language, colors)
                applyLintAndSemantic(incrHighlight, text.text, OffsetMapping.Identity)
            } else {
                applyHighlightAndLint(text, OffsetMapping.Identity)
            }
            val safeResult = sanitizeResult(result, text, isIdentityMapping = true)
            cachedText = text.text
            cachedResult = safeResult
            return safeResult
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

        // P41-W: Overlay semantic tokens (re-mapped to display offsets)
        // Change 1: Use per-line offsets when available for structural desync prevention
        val foldLineStarts = computeLineStarts(raw)
        val foldLineLengths = computeLineLengths(raw, foldLineStarts)
        val withSemantic = if (semanticTokens.isNotEmpty()) {
            buildAnnotatedString {
                append(highlighted)
                for (tok in semanticTokens) {
                    val (origS, origE) = safeSemanticOffsets(tok, foldLineStarts, foldLineLengths, raw.length)
                    val tStart = origToTransFinal.getOrElse(origS.coerceIn(0, raw.length)) { 0 }
                    val tEnd = origToTransFinal.getOrElse(origE.coerceIn(0, raw.length)) { 0 }
                    if (tStart < tEnd) {
                        addStyle(SpanStyle(color = tok.color), tStart, tEnd)
                    }
                }
            }
        } else highlighted

        // ── Step 3: overlay lint squiggles (re-mapped to display offsets) ──
        if (lintErrors.isEmpty()) return sanitizeSpans(TransformedText(withSemantic, offsetMapping))

        val withLint = buildAnnotatedString {
            append(withSemantic)
            for (err in lintErrors) {
                val (origS, origE) = safeLintOffsets(err, foldLineStarts, foldLineLengths, raw.length)
                val tStart = origToTransFinal.getOrElse(origS.coerceIn(0, raw.length)) { 0 }
                val tEnd = origToTransFinal.getOrElse(origE.coerceIn(0, raw.length)) { 0 }
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
        return sanitizeSpans(TransformedText(withLint, offsetMapping))
    }

    // R1-1: Apply lint squiggles + semantic tokens to a pre-computed AnnotatedString.
    // Used when precomputedHighlight is available and matches the current text.
    private fun applyLintAndSemantic(
        highlight: AnnotatedString,
        textStr: String,
        offsetMapping: OffsetMapping,
    ): TransformedText {
        // Change 1: Use per-line offsets when available for structural desync prevention
        val lineStarts = computeLineStarts(textStr)
        val lineLengths = computeLineLengths(textStr, lineStarts)

        val withSemantic = if (semanticTokens.isNotEmpty()) {
            buildAnnotatedString {
                append(highlight)
                for (tok in semanticTokens) {
                    val (s, e) = safeSemanticOffsets(tok, lineStarts, lineLengths, textStr.length)
                    if (s < e) {
                        addStyle(SpanStyle(color = tok.color), s, e)
                    }
                }
            }
        } else highlight

        if (lintErrors.isEmpty()) return sanitizeSpans(TransformedText(withSemantic, offsetMapping))

        val withLint = buildAnnotatedString {
            append(withSemantic)
            for (err in lintErrors) {
                val (start, end) = safeLintOffsets(err, lineStarts, lineLengths, textStr.length)
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
        return sanitizeSpans(TransformedText(withLint, offsetMapping))
    }

    // ── No-fold path (original logic) ─────────────────────────────────────

    private fun applyHighlightAndLint(
        text: AnnotatedString,
        offsetMapping: OffsetMapping,
    ): TransformedText {
        val highlighted = SyntaxHighlighter.highlight(text.text, language, colors)

        // P41-W: Overlay semantic tokens on top of regex highlighting
        // Change 1: Use per-line offsets when available for structural desync prevention
        val lineStarts = computeLineStarts(text.text)
        val lineLengths = computeLineLengths(text.text, lineStarts)

        val withSemantic = if (semanticTokens.isNotEmpty()) {
            buildAnnotatedString {
                append(highlighted)
                for (tok in semanticTokens) {
                    val (s, e) = safeSemanticOffsets(tok, lineStarts, lineLengths, text.text.length)
                    if (s < e) {
                        addStyle(SpanStyle(color = tok.color), s, e)
                    }
                }
            }
        } else highlighted

        if (lintErrors.isEmpty()) return sanitizeSpans(TransformedText(withSemantic, offsetMapping))

        val withLint = buildAnnotatedString {
            append(withSemantic)
            for (err in lintErrors) {
                val (start, end) = safeLintOffsets(err, lineStarts, lineLengths, text.text.length)
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
        return sanitizeSpans(TransformedText(withLint, offsetMapping))
    }
}
