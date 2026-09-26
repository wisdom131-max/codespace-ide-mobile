package com.codespace.ide.editor.textmate

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.EditorColors

/**
 * Integration bridge: converts TextMate tokens into Compose AnnotatedString.
 *
 * Written from scratch. Architecture reference: sora-editor's TextMateAnalyzer.tokenizeLine()
 * which produces Span objects from grammar token metadata (LGPL 2.1 — study only).
 *
 * This object sits between the TextMate engine and the existing SyntaxTransformation.
 * When a TextMate grammar is available for the current language, it produces an
 * AnnotatedString with proper scope-based colors. When no grammar is available,
 * the caller falls back to the existing SyntaxHighlighter.
 *
 * Color mapping:
 * 1. Try the loaded TextMate theme (scope → color)
 * 2. If no theme loaded, map common scopes to the current EditorColors palette
 * 3. Fall back to plain text color
 */
object TmIntegration {

    /**
     * Highlight text using a TextMate grammar.
     *
     * @param engine The TextMateEngine with loaded grammars
     * @param scopeName The grammar scope name (e.g., "source.kotlin")
     * @param text The full text to highlight
     * @param editorColors The current editor color palette (fallback colors)
     * @return AnnotatedString with syntax highlighting, or null if grammar not found
     */
    fun highlight(
        engine: TextMateEngine,
        scopeName: String,
        text: String,
        editorColors: EditorColors,
    ): AnnotatedString? {
        val tokens = engine.tokenizeText(scopeName, text) ?: return null
        val theme = engine.getTheme()

        return buildAnnotatedString {
            var lastEnd = 0
            for (token in tokens) {
                // Emit any untokenized gap
                if (token.start > lastEnd) {
                    append(text.substring(lastEnd, token.start))
                }
                val color = resolveColor(token.scopes, theme, editorColors)
                val style = resolveStyle(token.scopes, theme, color)
                withStyle(style) {
                    append(text.substring(token.start, token.end))
                }
                lastEnd = token.end
            }
            // Emit any remaining text
            if (lastEnd < text.length) {
                append(text.substring(lastEnd))
            }
        }
    }

    /**
     * Resolve a scope path to a Color using the theme or fallback palette.
     */
    private fun resolveColor(
        scopes: List<String>,
        theme: TmTheme?,
        editorColors: EditorColors,
    ): Color {
        // 1. Try the TextMate theme
        if (theme != null) {
            val rule = TmScopeMatcher.match(scopes, theme)
            if (rule?.foreground != null) {
                val argb = TmScopeMatcher.parseColor(rule.foreground)
                if (argb != null) return Color(argb)
            }
        }

        // 2. Fall back to EditorColors based on scope name patterns
        return scopeToEditorColor(scopes, editorColors)
    }

    /**
     * Map TextMate scope names to the existing EditorColors palette.
     * This provides reasonable colors even without a loaded theme.
     */
    private fun scopeToEditorColor(scopes: List<String>, colors: EditorColors): Color {
        // Check the most specific scope first (last in the list)
        for (scope in scopes.reversed()) {
            val parts = scope.split(".")
            when (parts[0]) {
                "keyword" -> return colors.keyword
                "string" -> return colors.string
                "constant" -> {
                    if (parts.size > 1 && parts[1] == "numeric") return colors.number
                    return colors.number
                }
                "comment" -> return colors.comment
                "entity" -> {
                    if (parts.size > 1 && parts[1] == "name") {
                        if (parts.size > 2 && parts[2] == "function") return colors.function
                        if (parts.size > 2 && parts[2] == "type") return colors.type
                        if (parts.size > 2 && parts[2] == "class") return colors.type
                    }
                    if (parts.size > 1 && parts[1] == "other") return colors.variable
                }
                "support" -> {
                    if (parts.size > 1 && parts[1] == "function") return colors.function
                    if (parts.size > 1 && parts[1] == "type") return colors.type
                    if (parts.size > 1 && parts[1] == "constant") return colors.number
                    if (parts.size > 1 && parts[1] == "class") return colors.type
                }
                "variable" -> return colors.variable
                "storage" -> {
                    if (parts.size > 1 && (parts[1] == "type" || parts[1] == "modifier")) return colors.keyword
                    return colors.keyword
                }
                "punctuation" -> return colors.operator
                "meta" -> {
                    // Meta scopes usually inherit — only color if it's a function call
                    if (parts.size > 1 && parts[1] == "function") return colors.function
                }
            }
        }
        return colors.text
    }

    /**
     * Resolve font style (bold/italic/underline) from theme or scope name.
     */
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

        // No theme — use scope-based defaults for emphasis
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

    /**
     * Map a Language enum to a TextMate scope name.
     *
     * TM05 (2026-09-26): the 25-branch Language→scope when-map is DELETED — it was
     * a SECOND parallel registry (drift family with VG12) that named ~25 scopes
     * while only 4 grammars ship (kotlin, python, javascript, json). The engine's
     * grammar fileTypes are now the ONE registry: the language maps through its own
     * extensions. No bundled grammar claims the extension → null → the caller falls
     * back to the built-in highlighter, honestly.
     */
    fun languageToScope(engine: TextMateEngine, language: Language): String? {
        for (ext in language.extensions) {
            val scope = engine.scopeForExtension(ext)
            if (scope != null) return scope
        }
        return null
    }
}
