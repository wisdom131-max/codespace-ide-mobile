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

/** Applies syntax highlighting + lint error squiggles as a VisualTransformation. */
class SyntaxTransformation(
    private val language: Language,
    private val colors: EditorColors,
    private val lintErrors: List<LintError> = emptyList(),
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = SyntaxHighlighter.highlight(text.text, language, colors)
        if (lintErrors.isEmpty()) return TransformedText(highlighted, OffsetMapping.Identity)

        // Overlay wavy-red underlines for lint errors
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
        return TransformedText(withLint, OffsetMapping.Identity)
    }
}
