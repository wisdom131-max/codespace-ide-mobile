package com.codespace.ide.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.EditorColors
import com.codespace.ide.editor.textmate.TextMateEngineHolder

private val tmHighlighter = IncrementalTmHighlighter()

/**
 * Incremental syntax highlighter that caches per-line highlighting results
 * and only re-highlights lines that actually changed.
 *
 * For single-character edits (the most common case), this reduces highlighting
 * from O(n) full-file scan to O(1) — just the current line.
 *
 * For multi-line edits, only the changed lines and any lines affected by
 * bracket-depth changes are re-highlighted.
 */
class IncrementalHighlighter {

    /** Cached per-line highlight segments: line index -> list of (start, end, color) */
    private data class LineCache(
        val content: String,
        val segments: List<Triple<Int, Int, Color>>,
        val bracketDepthAtEnd: Int,
        val inBlockCommentAtEnd: Boolean
    )

    private var lineCaches: MutableList<LineCache> = mutableListOf()
    private var cachedLanguage: Language? = null
    private var cachedColors: EditorColors? = null

    /**
     * Highlight text incrementally. Returns the full AnnotatedString.
     * Only re-highlights lines that changed since the last call.
     */
    fun highlight(
        text: String,
        language: Language,
        colors: EditorColors
    ): AnnotatedString {
        // If TextMate highlighting is enabled and a grammar is available,
        // use the incremental TextMate highlighter (per-line caching, O(1) for single edits)
        if (TextMateEngineHolder.isActive()) {
            val tmResult = tmHighlighter.highlight(text, language, colors)
            if (tmResult != null) return tmResult
        }

        // Fallback: incremental regex highlighter
        // If language or colors changed, full re-highlight
        if (language != cachedLanguage || colors != cachedColors) {
            lineCaches.clear()
            cachedLanguage = language
            cachedColors = colors
        }

        val lines = text.split('\n')
        val spec = LanguageSpecs.forLanguage(language)

        // Find which lines changed
        val changedLines = findChangedLines(lines)

        // Re-highlight only changed lines
        var currentBracketDepth = 0
        var currentInBlockComment = false
        for (i in lines.indices) {
            if (i in changedLines || i >= lineCaches.size) {
                val lineText = lines[i]
                val (segments, depthAndComment) = highlightLine(lineText, currentBracketDepth, currentInBlockComment, spec, colors)
                val cache = LineCache(lineText, segments, depthAndComment.first, depthAndComment.second)
                if (i < lineCaches.size) {
                    lineCaches[i] = cache
                } else {
                    while (lineCaches.size < i) lineCaches.add(LineCache("", emptyList(), 0, false))
                    lineCaches.add(cache)
                }
            }
            currentBracketDepth = lineCaches[i].bracketDepthAtEnd
            currentInBlockComment = lineCaches[i].inBlockCommentAtEnd
        }

        // Trim excess caches if text got shorter
        while (lineCaches.size > lines.size) lineCaches.removeAt(lineCaches.lastIndex)

        // Build the full AnnotatedString from cached segments
        // MUST use withStyle+append (not addStyle) so spans are always within
        // the actual appended text. Using addStyle with absolute offsets creates
        // AnnotatedStrings with spans beyond text length, crashing the
        // accessibility (TalkBack) layer: IndexOutOfBoundsException setSpan.
        return buildAnnotatedString {
            for (i in lines.indices) {
                val cache = if (i < lineCaches.size) lineCaches[i] else null
                if (cache != null) {
                    val lineText = lines[i]
                    var lastEnd = 0
                    for ((start, end, color) in cache.segments) {
                        if (start > lastEnd) {
                            append(lineText.substring(lastEnd, start))
                        }
                        withStyle(SpanStyle(color = color)) {
                            append(lineText.substring(start, end))
                        }
                        lastEnd = end
                    }
                    if (lastEnd < lineText.length) {
                        append(lineText.substring(lastEnd))
                    }
                } else {
                    append(lines[i])
                }
                if (i < lines.size - 1) append('\n')
            }
        }
    }

    /** 
     * Find which lines changed compared to cached content.
     * Returns the set of line indices that need re-highlighting.
     * Optimization: only re-highlight from the first changed line forward
     * until bracket depth at line end matches the cached depth.
     * For single-char edits in the middle of a line, this is O(1) — just that line.
     */
    private fun findChangedLines(lines: List<String>): Set<Int> {
        val changed = mutableSetOf<Int>()
        var firstChanged = -1
        for (i in lines.indices) {
            val cached = lineCaches.getOrNull(i)
            if (cached == null || cached.content != lines[i]) {
                changed.add(i)
                if (firstChanged == -1) firstChanged = i
            }
        }
        if (firstChanged == -1) return changed
        
        // Re-highlight forward from firstChanged until bracket depth matches
        var prevDepth = if (firstChanged > 0 && firstChanged - 1 < lineCaches.size) 
            lineCaches[firstChanged - 1].bracketDepthAtEnd else 0
        
        var prevInComment = if (firstChanged > 0 && firstChanged - 1 < lineCaches.size) 
            lineCaches[firstChanged - 1].inBlockCommentAtEnd else false
        for (i in firstChanged until lines.size) {
            changed.add(i)
            val cached = lineCaches.getOrNull(i)
            val newDepth = computeBracketDepth(lines[i], prevDepth)
            val newInComment = computeBlockCommentState(lines[i], prevInComment)
            val commentChanged = cached != null && newInComment != cached.inBlockCommentAtEnd
            // If depth AND block comment state match cached values, we can stop
            if (cached != null && newDepth == cached.bracketDepthAtEnd && !commentChanged) {
                break
            }
            prevDepth = newDepth
            prevInComment = newInComment
        }
        return changed
    }

    /** Quick bracket depth computation for a single line */
    private fun computeBracketDepth(line: String, initialDepth: Int): Int {
        var depth = initialDepth
        var inString = false
        var stringQuote: Char = ' '
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString) {
                if (c == '\\') { i += 2; continue }
                if (c == stringQuote) inString = false
                i++
                continue
            }
            when (c) {
                '\"', '\'', '`' -> {
                    inString = true
                    stringQuote = c
                }
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth = (depth - 1).coerceAtLeast(0)
            }
            i++
        }
        return depth
    }

    /** Quick block comment state computation for a single line */
    private fun computeBlockCommentState(line: String, initialInComment: Boolean): Boolean {
        val spec = cachedLanguage?.let { LanguageSpecs.forLanguage(it) } ?: return false
        val start = spec.blockCommentStart ?: return false
        val end = spec.blockCommentEnd ?: return false
        var inComment = initialInComment
        var i = 0
        while (i < line.length) {
            if (inComment) {
                val close = line.indexOf(end, i)
                if (close == -1) return true
                i = close + end.length
                inComment = false
            } else {
                if (line.startsWith(start, i)) {
                    inComment = true
                    i += start.length
                } else {
                    i++
                }
            }
        }
        return inComment
    }

    /** Highlight a single line and return segments + bracket depth at end */
    private fun highlightLine(
        line: String,
        initialBracketDepth: Int,
        initialInBlockComment: Boolean,
        spec: LanguageSpec,
        colors: EditorColors
    ): Pair<List<Triple<Int, Int, Color>>, Pair<Int, Boolean>> {
        val segments = mutableListOf<Triple<Int, Int, Color>>()
        val bracketColors = listOf(
            Color(0xFFFFD700),
            Color(0xFFDA70D6),
            Color(0xFF179FFF)
        )
        var bracketDepth = initialBracketDepth
        var inBlockComment = initialInBlockComment
        var i = 0
        val n = line.length

        while (i < n) {
            val c = line[i]

            // Block comment continuation from previous line
            if (inBlockComment) {
                val close = if (spec.blockCommentEnd != null) line.indexOf(spec.blockCommentEnd, i) else -1
                if (close == -1) {
                    // Rest of line is in block comment
                    segments.add(Triple(i, n, colors.comment))
                    i = n
                    continue
                }
                val end = close + spec.blockCommentEnd!!.length
                segments.add(Triple(i, end, colors.comment))
                i = end
                inBlockComment = false
                continue
            }

            // Line comments
            if (spec.lineComment != null && line.startsWith(spec.lineComment!!, i)) {
                segments.add(Triple(i, n, colors.comment))
                i = n
                continue
            }

            // Block comment start
            if (spec.blockCommentStart != null && line.startsWith(spec.blockCommentStart!!, i)) {
                val close = if (spec.blockCommentEnd != null && spec.blockCommentStart != null) line.indexOf(spec.blockCommentEnd!!, i + spec.blockCommentStart!!.length) else -1
                if (close == -1) {
                    // Block comment spans to end of line (continues on next line)
                    segments.add(Triple(i, n, colors.comment))
                    inBlockComment = true
                    i = n
                    continue
                }
                val end = close + spec.blockCommentEnd!!.length
                segments.add(Triple(i, end, colors.comment))
                i = end
                continue
            }

            // Strings (single-line only)
            if (c in spec.stringDelimiters) {
                val end = scanLineString(line, i, c)
                segments.add(Triple(i, end, colors.string))
                i = end
                continue
            }

            // Numbers
            if (c.isDigit()) {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '.' || line[j] == '_')) j++
                segments.add(Triple(i, j, colors.number))
                i = j
                continue
            }

            // Identifiers / keywords / functions / types
            if (c.isLetter() || c == '_' || c == '$') {
                var j = i + 1
                while (j < n && (line[j].isLetterOrDigit() || line[j] == '_' || line[j] == '$')) j++
                val word = line.substring(i, j)
                val color = when {
                    word in spec.keywords -> colors.keyword
                    word in spec.types -> colors.type
                    word.isNotEmpty() && word[0].isUpperCase() -> colors.type
                    j < n && line[j] == '(' -> colors.function
                    else -> null
                }
                if (color != null) segments.add(Triple(i, j, color))
                i = j
                continue
            }

            // Brackets
            if (c == '(' || c == '[' || c == '{') {
                val color = bracketColors[Math.floorMod(bracketDepth, bracketColors.size)]
                segments.add(Triple(i, i + 1, color))
                bracketDepth++
                i++
                continue
            }
            if (c == ')' || c == ']' || c == '}') {
                bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                val color = bracketColors[Math.floorMod(bracketDepth, bracketColors.size)]
                segments.add(Triple(i, i + 1, color))
                i++
                continue
            }

            // Operators
            if (!c.isWhitespace() && c in "+-*/%=<>!&|^~?:.") {
                segments.add(Triple(i, i + 1, colors.operator))
                i++
                continue
            }
            i++
        }

        return Pair(segments, Pair(bracketDepth, inBlockComment))
    }

    private fun scanLineString(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < line.length) {
            when (line[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                else -> i++
            }
        }
        return line.length
    }

    /** Reset cache (e.g., when language changes) */
    fun reset() {
        lineCaches.clear()
        cachedLanguage = null
        cachedColors = null
        tmHighlighter.reset()
    }
}
