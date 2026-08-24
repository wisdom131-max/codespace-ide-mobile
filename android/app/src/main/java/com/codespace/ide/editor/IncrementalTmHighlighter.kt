package com.codespace.ide.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.EditorColors
import com.codespace.ide.editor.textmate.TextMateEngine
import com.codespace.ide.editor.textmate.TextMateEngineHolder
import com.codespace.ide.editor.textmate.TmIntegration
import com.codespace.ide.editor.textmate.TmStateStack
import com.codespace.ide.editor.textmate.TmTokenizer
import com.codespace.ide.editor.textmate.TmTheme
import com.codespace.ide.editor.textmate.TmScopeMatcher

/**
 * Incremental TextMate highlighter with per-line state caching.
 *
 * Instead of re-tokenizing the entire file on every keystroke (O(n)),
 * this caches per-line tokenization results and only re-tokenizes from
 * the first changed line until the state stack converges with a cached state.
 *
 * For single-character edits, this is typically O(1) — just the current line.
 * For multi-line edits, only affected lines are re-tokenized.
 *
 * Architecture reference: sora-editor's TextMateAnalyzer (LGPL 2.1 — study only).
 */
class IncrementalTmHighlighter {

    /** Cached per-line tokenization: content, tokens, and state after this line. */
    private data class TmLineCache(
        val content: String,
        val tokens: List<TmTokenizer.TmToken>,
        val stateAfter: TmStateStack,
    )

    private val lineCaches = mutableListOf<TmLineCache>()
    private var cachedScopeName: String? = null

    /**
     * Highlight text incrementally using TextMate grammars.
     * Returns null if TextMate is not available for this language.
     */
    fun highlight(
        text: String,
        language: Language,
        colors: EditorColors,
    ): AnnotatedString? {
        if (!TextMateEngineHolder.isActive()) return null
        val engine = TextMateEngineHolder.getIfInitialized() ?: return null
        val scopeName = TmIntegration.languageToScope(language) ?: return null
        if (!engine.hasGrammar(scopeName)) return null

        // If language changed, clear cache
        if (scopeName != cachedScopeName) {
            lineCaches.clear()
            cachedScopeName = scopeName
        }

        val lines = text.split("\n")
        val theme = engine.getTheme()

        // Find first changed line
        var firstChanged = -1
        for (i in lines.indices) {
            val cached = lineCaches.getOrNull(i)
            if (cached == null || cached.content != lines[i]) {
                firstChanged = i
                break
            }
        }

        if (firstChanged == -1 && lines.size == lineCaches.size) {
            // Nothing changed — rebuild from cache
            return buildFromCache(lines, text, colors, theme)
        }

        // Get state before the first changed line
        val stateBefore: TmStateStack = if (firstChanged <= 0) {
            TmStateStack.NULL
        } else if (firstChanged - 1 < lineCaches.size) {
            lineCaches[firstChanged - 1].stateAfter
        } else {
            TmStateStack.NULL
        }

        // Re-tokenize from firstChanged until state converges or we reach end
        var currentState = stateBefore
        var lineIdx = firstChanged
        if (firstChanged == -1) lineIdx = lineCaches.size // only new lines at end

        while (lineIdx < lines.size) {
            val lineText = lines[lineIdx]
            // Use the engine's per-line tokenizer
            val result = engine.tokenizeLine(scopeName, lineText, currentState) ?: break
            currentState = result.newState

            // Update or append cache
            val cache = TmLineCache(lineText, result.tokens, currentState)
            if (lineIdx < lineCaches.size) {
                // Check if state converged — same content and same state = stop
                val oldCache = lineCaches[lineIdx]
                lineCaches[lineIdx] = cache
                if (oldCache.content == lineText &&
                    TmStateStack.equals(oldCache.stateAfter, currentState) &&
                    firstChanged != -1) {
                    // State converged — remaining lines are still valid
                    lineIdx++
                    break
                }
            } else {
                lineCaches.add(cache)
            }
            lineIdx++
        }

        // Trim excess caches if text got shorter
        while (lineCaches.size > lines.size) lineCaches.removeAt(lineCaches.lastIndex)

        return buildFromCache(lines, text, colors, theme)
    }

    /**
     * Build the full AnnotatedString from cached per-line tokens.
     */
    private fun buildFromCache(
        lines: List<String>,
        text: String,
        colors: EditorColors,
        theme: TmTheme?,
    ): AnnotatedString {
        // Precompute line start offsets
        val lineStarts = IntArray(lines.size)
        var pos = 0
        for (i in lines.indices) {
            lineStarts[i] = pos
            pos += lines[i].length + 1
        }

        return buildAnnotatedString {
            for (i in lines.indices) {
                val cache = lineCaches.getOrNull(i)
                val lineStart = lineStarts[i]

                if (cache != null) {
                    val lineLen = lines[i].length
                    // Safety: if cached content doesn't match actual line (stale cache from
                    // different file or race condition), skip tokens and emit raw text.
                    if (cache.content != lines[i]) {
                        append(lines[i])
                    } else {
                        var lastEnd = 0
                        for (token in cache.tokens) {
                            // Clamp token offsets to actual line length
                            val tStart = token.start.coerceIn(0, lineLen)
                            val tEnd = token.end.coerceIn(tStart, lineLen)
                            if (tStart > lastEnd) {
                                append(lines[i].substring(lastEnd, tStart))
                            }
                            val color = resolveColor(token.scopes, theme, colors)
                            val style = resolveStyle(token.scopes, theme, color)
                            withStyle(style) {
                                if (tEnd > tStart) append(lines[i].substring(tStart, tEnd))
                            }
                            lastEnd = tEnd
                        }
                        // Emit remaining text on this line
                        if (lastEnd < lineLen) {
                            append(lines[i].substring(lastEnd))
                        }
                    }
                } else {
                    append(lines[i])
                }

                if (i < lines.size - 1) append('\n')
            }
        }
    }

    private fun resolveColor(
        scopes: List<String>,
        theme: TmTheme?,
        editorColors: EditorColors,
    ): Color {
        if (theme != null) {
            val rule = TmScopeMatcher.match(scopes, theme)
            if (rule?.foreground != null) {
                val argb = TmScopeMatcher.parseColor(rule.foreground)
                if (argb != null) return Color(argb)
            }
        }
        return scopeToEditorColor(scopes, editorColors)
    }

    private fun scopeToEditorColor(scopes: List<String>, colors: EditorColors): Color {
        for (scope in scopes.reversed()) {
            val parts = scope.split(".")
            when (parts[0]) {
                "keyword" -> return colors.keyword
                "string" -> return colors.string
                "constant" -> return colors.number
                "comment" -> return colors.comment
                "entity" -> {
                    if (parts.size > 2 && parts[2] == "function") return colors.function
                    if (parts.size > 2 && (parts[2] == "type" || parts[2] == "class")) return colors.type
                    if (parts.size > 1 && parts[1] == "other") return colors.variable
                }
                "support" -> {
                    if (parts.size > 1 && parts[1] == "function") return colors.function
                    if (parts.size > 1 && (parts[1] == "type" || parts[1] == "class")) return colors.type
                    if (parts.size > 1 && parts[1] == "constant") return colors.number
                }
                "variable" -> return colors.variable
                "storage" -> return colors.keyword
                "punctuation" -> return colors.operator
                "meta" -> {
                    if (parts.size > 1 && parts[1] == "function") return colors.function
                }
            }
        }
        return colors.text
    }

    private fun resolveStyle(
        scopes: List<String>,
        theme: TmTheme?,
        color: Color,
    ): SpanStyle {
        var fontWeight: FontWeight? = null
        var fontStyle: FontStyle? = null
        var textDecoration: androidx.compose.ui.text.style.TextDecoration? = null

        if (theme != null) {
            val rule = TmScopeMatcher.match(scopes, theme)
            rule?.fontStyle?.let { fs ->
                if (fs.contains("bold")) fontWeight = FontWeight.Bold
                if (fs.contains("italic")) fontStyle = FontStyle.Italic
                if (fs.contains("underline")) textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
            }
        }

        if (theme == null) {
            for (scope in scopes.reversed()) {
                val parts = scope.split(".")
                if (parts[0] == "keyword" || parts[0] == "storage") {
                    fontWeight = FontWeight.Bold
                    break
                }
                if (parts[0] == "comment") {
                    fontStyle = FontStyle.Italic
                    break
                }
            }
        }

        return SpanStyle(
            color = color,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            textDecoration = textDecoration,
        )
    }

    /** Reset cache (e.g., when language changes). */
    fun reset() {
        lineCaches.clear()
        cachedScopeName = null
    }
}
