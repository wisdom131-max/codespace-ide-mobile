package com.codespace.ide.lsp

/**
 * P41 Phase I — LSP Snippet Engine
 *
 * Parses LSP snippet syntax (insertTextFormat == 2) and produces:
 * - Clean text with placeholders replaced by their default values
 * - A list of tab-stops with positions in the cleaned text
 *
 * Supported syntax:
 * - $1, $2, ...      → tab-stop with no default (empty)
 * - ${1:default}     → tab-stop with default text
 * - ${1|a,b,c|}      → tab-stop with choice dropdown
 * - $0               → final cursor position (after last tab-stop)
 * - $$                → literal dollar sign (escaped)
 * - ${TM_FILENAME}   → variable (replaced with empty for now — future: resolve from context)
 *
 * Reference: https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/#snippet_syntax
 */

/** A single tab-stop in a parsed snippet. */
data class SnippetTabStop(
    val index: Int,          // Tab-stop number (1, 2, 3...). $0 is the final position (Int.MAX_VALUE).
    val startOffset: Int,    // Start position in the cleaned text
    val endOffset: Int,      // End position in the cleaned text (start == end for empty tab-stops)
    val defaultText: String, // Default text at this tab-stop (may be empty)
    val choices: List<String> = emptyList(), // For ${1|a,b,c|} syntax
)

/** Result of parsing a snippet string. */
data class SnippetParseResult(
    val cleanedText: String,        // Text with placeholders replaced by defaults
    val tabStops: List<SnippetTabStop>, // Tab-stops in order (excluding $0)
    val finalCursorOffset: Int,      // Position of $0 (or end of text if not present)
)

/** Active snippet edit session in the editor. */
data class SnippetSession(
    val snippetStart: Int,           // Start offset of the entire snippet in the document
    val snippetEnd: Int,              // End offset (exclusive) — updated as text changes
    val tabStops: List<SnippetTabStop>, // Tab-stops relative to snippetStart
    val activeStopIndex: Int = 0,     // Currently active tab-stop index
    val finalCursorOffset: Int,      // $0 position relative to snippetStart
)

/**
 * Parse an LSP snippet string into cleaned text + tab-stops.
 *
 * Example: "fun ${1:name}(): ${2:Unit} {\n    $0\n}"
 * → cleanedText = "fun name(): Unit {\n    \n}"
 *   tabStops = [
 *     SnippetTabStop(1, 4, 8, "name"),
 *     SnippetTabStop(2, 12, 16, "Unit"),
 *   ]
 *   finalCursorOffset = 22
 */
fun parseSnippet(snippet: String): SnippetParseResult {
    val cleaned = StringBuilder()
    val tabStops = mutableListOf<SnippetTabStop>()
    var finalCursorOffset = -1
    var i = 0

    while (i < snippet.length) {
        val c = snippet[i]

        if (c == '$') {
            if (i + 1 >= snippet.length) {
                cleaned.append(c)
                i++
                continue
            }

            val next = snippet[i + 1]

            if (next == '$') {
                // Escaped dollar sign
                cleaned.append('$')
                i += 2
                continue
            }

            if (next == '{') {
                // ${...} syntax — could be ${1:default}, ${1|a,b,c|}, ${VAR}, or ${VAR:default}
                val closeIdx = snippet.indexOf('}', i + 2)
                if (closeIdx == -1) {
                    cleaned.append(c)
                    i++
                    continue
                }

                val content = snippet.substring(i + 2, closeIdx)
                val colonIdx = content.indexOf(':')
                val pipeIdx = content.indexOf('|')

                // Check if it's a tab-stop (starts with a number)
                val numEnd = if (colonIdx != -1) colonIdx else if (pipeIdx != -1) pipeIdx else content.length
                val numStr = content.substring(0, numEnd)
                val num = numStr.toIntOrNull()

                if (num != null) {
                    // It's a tab-stop
                    val stopOffset = cleaned.length

                    if (num == 0) {
                        // $0 — final cursor position
                        finalCursorOffset = cleaned.length
                        i = closeIdx + 1
                        continue
                    }

                    var defaultText = ""
                    var choices = emptyList<String>()

                    if (pipeIdx != -1 && (colonIdx == -1 || pipeIdx < colonIdx)) {
                        // ${1|a,b,c|} — choices
                        val choicesStr = content.substring(pipeIdx + 1)
                        // Remove trailing | if present
                        val cleanedChoices = choicesStr.removeSuffix("|")
                        choices = cleanedChoices.split(",").map { it.trim() }
                        defaultText = choices.firstOrNull() ?: ""
                    } else if (colonIdx != -1) {
                        // ${1:default} — default text
                        defaultText = content.substring(colonIdx + 1)
                    }

                    cleaned.append(defaultText)
                    tabStops.add(SnippetTabStop(
                        index = num,
                        startOffset = stopOffset,
                        endOffset = stopOffset + defaultText.length,
                        defaultText = defaultText,
                        choices = choices,
                    ))
                    i = closeIdx + 1
                    continue
                } else {
                    // Variable like ${TM_FILENAME} — replace with empty for now
                    // Future: resolve variables from context
                    i = closeIdx + 1
                    continue
                }
            }

            // $<digit> syntax — simple tab-stop like $1, $2
            if (next.isDigit()) {
                val numStart = i + 1
                var numEnd = numStart
                while (numEnd < snippet.length && snippet[numEnd].isDigit()) numEnd++
                val num = snippet.substring(numStart, numEnd).toInt()

                if (num == 0) {
                    finalCursorOffset = cleaned.length
                    i = numEnd
                    continue
                }

                val stopOffset = cleaned.length
                tabStops.add(SnippetTabStop(
                    index = num,
                    startOffset = stopOffset,
                    endOffset = stopOffset, // empty — no default
                    defaultText = "",
                ))
                i = numEnd
                continue
            }

            // $<letter> — variable like $TM_FILENAME
            if (next.isLetter()) {
                val varEnd = i + 1
                while (varEnd < snippet.length && (snippet[varEnd].isLetterOrDigit() || snippet[varEnd] == '_')) varEnd++
                // Replace variable with empty for now
                i = varEnd
                continue
            }

            // Just a $ followed by something else — literal
            cleaned.append(c)
            i++
            continue
        }

        cleaned.append(c)
        i++
    }

    // Sort tab-stops by index (1, 2, 3...) — $0 is handled separately
    tabStops.sortBy { it.index }

    // If no $0 was specified, cursor goes to end of text
    if (finalCursorOffset == -1) {
        finalCursorOffset = cleaned.length
    }

    return SnippetParseResult(
        cleanedText = cleaned.toString(),
        tabStops = tabStops,
        finalCursorOffset = finalCursorOffset,
    )
}

/**
 * Create a SnippetSession from a parsed snippet, anchored to the insertion point.
 * Tab-stop offsets are relative to the document (absolute), not the snippet text.
 */
fun createSnippetSession(
    insertOffset: Int,
    parsed: SnippetParseResult,
): SnippetSession {
    val absoluteStops = parsed.tabStops.map { stop ->
        stop.copy(
            startOffset = insertOffset + stop.startOffset,
            endOffset = insertOffset + stop.endOffset,
        )
    }
    return SnippetSession(
        snippetStart = insertOffset,
        snippetEnd = insertOffset + parsed.cleanedText.length,
        tabStops = absoluteStops,
        activeStopIndex = 0,
        finalCursorOffset = insertOffset + parsed.finalCursorOffset,
    )
}

/**
 * Get the absolute (document-level) range for the active tab-stop.
 * Returns null if no active tab-stop (session complete).
 */
fun SnippetSession.activeStopRange(): IntRange? {
    if (activeStopIndex >= tabStops.size) return null
    val stop = tabStops[activeStopIndex]
    return stop.startOffset until stop.endOffset
}

/**
 * Advance to the next tab-stop. Returns a new session with activeStopIndex incremented,
 * or null if we've passed the last tab-stop (session should end).
 */
fun SnippetSession.advance(): SnippetSession? {
    val nextIndex = activeStopIndex + 1
    if (nextIndex >= tabStops.size) {
        return null // No more tab-stops — session is done
    }
    return copy(activeStopIndex = nextIndex)
}

/**
 * Go back to the previous tab-stop. Returns a new session with activeStopIndex decremented,
 * or null if we're at the first tab-stop (can't go back).
 */
fun SnippetSession.retreat(): SnippetSession? {
    if (activeStopIndex <= 0) return null
    return copy(activeStopIndex = activeStopIndex - 1)
}

/**
 * Check if the cursor is still within the snippet's span.
 * Used to detect if the user has moved outside the snippet (should exit snippet mode).
 */
fun SnippetSession.containsCursor(cursor: Int): Boolean {
    return cursor >= snippetStart && cursor <= snippetEnd
}

/**
 * Update the snippet's end offset after the user typed within a tab-stop.
 * This recalculates the snippet boundaries based on the new text length.
 */
fun SnippetSession.updateSpan(newSnippetEnd: Int): SnippetSession {
    return copy(snippetEnd = newSnippetEnd)
}
