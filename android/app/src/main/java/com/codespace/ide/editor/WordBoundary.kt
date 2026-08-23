package com.codespace.ide.editor

/**
 * Word boundary detection for double-tap / long-press word selection.
 * Handles camelCase, snake_case, kebab-case, and dot notation.
 */
object WordBoundary {

    /**
     * Find the word boundaries at [offset] in [text].
     * Returns (start, end) where text[start..end) is the word.
     *
     * Rules:
     * - If at whitespace, select the whitespace run (VS Code behavior)
     * - If at a word char (letter, digit, _), expand to include all word chars
     * - Include dots for property access (obj.prop.method)
     * - Include hyphens in CSS/HTML contexts (kebab-case)
     * - Include $ for PHP/Kotlin variables and @ for Java annotations
     * - CamelCase: if the char is uppercase, stop at the previous lowercase→uppercase boundary
     */
    fun findWordBoundaries(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty() || offset < 0 || offset >= text.length) {
            return Pair(offset.coerceIn(0, text.length), offset.coerceIn(0, text.length))
        }

        val char = text[offset]

        // Whitespace: select the whitespace run
        if (char.isWhitespace()) {
            var start = offset
            var end = offset
            while (start > 0 && text[start - 1].isWhitespace()) start--
            while (end < text.length && text[end].isWhitespace()) end++
            return Pair(start, end)
        }

        // Determine if this is a "word" context based on the char type
        val isWordChar: (Char) -> Boolean = when {
            char.isLetterOrDigit() || char == '_' -> { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' || c == '@' }
            char == '.' -> { c -> c.isLetterOrDigit() || c == '_' || c == '.' || c == '$' }
            char == '-' -> { c -> c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' }
            char == '$' -> { c -> c.isLetterOrDigit() || c == '_' || c == '$' || c == '@' }
            char == '@' -> { c -> c.isLetterOrDigit() || c == '_' || c == '@' }
            else -> { c -> c == char }
        }

        var start = offset
        var end = offset

        // Expand left
        while (start > 0 && isWordChar(text[start - 1])) start--

        // Expand right
        while (end < text.length && isWordChar(text[end])) end++

        // CamelCase refinement: if we're in a camelCase identifier, trim to the sub-word
        // e.g., "myVariableName" with cursor on "Variable" should select just "Variable"
        if (char.isLetter() && end - start > 1) {
            val word = text.substring(start, end)
            // Only apply camelCase split if the word has mixed case (not ALL_CAPS constants)
            val hasLower = word.any { it.isLowerCase() }
            val hasUpper = word.any { it.isUpperCase() }
            if (hasLower && hasUpper && !word.all { it.isUpperCase() || it == '_' || it.isDigit() }) {
                // Find the camelCase boundary at the cursor position
                // An uppercase char starts a new sub-word if preceded by a lowercase char
                var camelStart = start
                // Walk left looking for lowercase→uppercase transition
                var i = offset
                while (i > start && !text[i - 1].isLowerCase()) i--
                if (i > start && text[i].isUpperCase() && text[i - 1].isLowerCase()) {
                    camelStart = i
                }
                // Walk right looking for uppercase→lowercase transition (end of sub-word)
                var camelEnd = end
                i = offset + 1
                while (i < end && text[i].isUpperCase()) i++
                if (i > offset + 1 && i < end && text[i].isLowerCase()) {
                    camelEnd = i
                }
                // Only apply if we found a valid sub-word
                if (camelEnd > camelStart) {
                    start = camelStart
                    end = camelEnd
                }
            }
        }

        return Pair(start, end)
    }

    /**
     * Find line boundaries at [offset] — for triple-tap line selection.
     * Returns (start, end) of the line including the trailing newline.
     */
    fun findLineBoundaries(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return Pair(0, 0)
        var start = offset.coerceIn(0, text.length)
        var end = offset.coerceIn(0, text.length)
        while (start > 0 && text[start - 1] != '\n') start--
        while (end < text.length && text[end] != '\n') end++
        // Include the trailing newline if present
        if (end < text.length && text[end] == '\n') end++
        return Pair(start, end)
    }
}
