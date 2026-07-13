package com.codespace.ide.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.codespace.ide.domain.Language
import com.codespace.ide.ui.EditorColors

object SyntaxHighlighter {

    fun highlight(text: String, language: Language, colors: EditorColors): AnnotatedString {
        val spec = LanguageSpecs.forLanguage(language)
        var bracketDepth = 0
        val bracketColors = listOf(
            Color(0xFFFFD700), // gold
            Color(0xFFDA70D6), // orchid
            Color(0xFF179FFF)  // blue
        )

        return buildAnnotatedString {
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]

                // Line comments
                if (spec.lineComment != null && text.startsWith(spec.lineComment, i)) {
                    val end = text.indexOf('\n', i).let { if (it == -1) n else it }
                    appendStyled(text.substring(i, end), colors.comment)
                    i = end
                    continue
                }
                // Block comments
                if (spec.blockCommentStart != null &&
                    text.startsWith(spec.blockCommentStart, i)
                ) {
                    val close = text.indexOf(spec.blockCommentEnd!!, i + spec.blockCommentStart.length)
                    val end = if (close == -1) n else close + spec.blockCommentEnd.length
                    appendStyled(text.substring(i, end), colors.comment)
                    i = end
                    continue
                }
                // Strings
                if (c == '"' || c == '\'' || c == '`') {
                    val end = scanString(text, i, c)
                    appendStyled(text.substring(i, end), colors.string)
                    i = end
                    continue
                }
                // Numbers
                if (c.isDigit()) {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                    appendStyled(text.substring(i, j), colors.number)
                    i = j
                    continue
                }
                // Identifiers / keywords / functions / types
                if (c.isLetter() || c == '_' || c == '$') {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '$')) j++
                    val word = text.substring(i, j)
                    val style = when {
                        word in spec.keywords -> colors.keyword
                        word in spec.types -> colors.type
                        word.isNotEmpty() && word[0].isUpperCase() -> colors.type
                        j < n && text[j] == '(' -> colors.function
                        else -> colors.text
                    }
                    appendStyled(word, style)
                    i = j
                    continue
                }
                // Brackets
                if (c == '(' || c == '[' || c == '{') {
                    val color = bracketColors[Math.floorMod(bracketDepth, bracketColors.size)]
                    appendStyled(c.toString(), color)
                    bracketDepth++
                    i++
                    continue
                }
                if (c == ')' || c == ']' || c == '}') {
                    bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                    val color = bracketColors[Math.floorMod(bracketDepth, bracketColors.size)]
                    appendStyled(c.toString(), color)
                    i++
                    continue
                }
                // Operators / punctuation
                if (!c.isWhitespace() && c in "+-*/%=<>!&|^~?:.") {
                    appendStyled(c.toString(), colors.operator)
                    i++
                    continue
                }
                append(c)
                i++
            }
        }
    }

    private fun AnnotatedString.Builder.appendStyled(s: String, color: Color) {
        withStyle(SpanStyle(color = color)) { append(s) }
    }

    private fun scanString(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> if (quote != '`') return i
                else -> i++
            }
        }
        return text.length
    }
}
