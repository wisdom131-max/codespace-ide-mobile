package com.codespace.ide.lsp

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * P41-W: Semantic Tokens — parses LSP textDocument/semanticTokens/full response
 * and maps token types to syntax highlighting colors.
 *
 * LSP semantic tokens use delta encoding:
 * Each token is [deltaLine, deltaStart, length, tokenType, tokenModifiers]
 * where deltaLine/deltaStart are relative to the previous token.
 *
 * The server's capabilities include a legend mapping tokenType indices to
 * string names (namespace, type, class, function, variable, etc.).
 */
object SemanticTokensApplier {

    data class SemanticRange(
        val startOffset: Int,
        val endOffset: Int,
        val color: Color,
        /**
         * Per-line coordinates for structural desync prevention.
         * When line >= 0, spans are column-relative within the owning line.
         * When line < 0, absolute offsets are used (legacy path).
         */
        val line: Int = -1,
        val startCol: Int = -1,
        val endCol: Int = -1,
    )

    /** LSP standard token types (ordered by index) */
    private val standardTokenTypes = listOf(
        "namespace", "type", "class", "enum", "interface",
        "struct", "typeParameter", "parameter", "variable", "property",
        "enumMember", "decorator", "event", "function", "method",
        "macro", "keyword", "modifier", "comment", "string",
        "number", "regexp", "operator",
    )

    /** Color mapping for LSP token types — matches VS Code Dark+ theme */
    private val tokenTypeColors: Map<String, Color> = mapOf(
        "namespace"     to Color(0xFFC586C0), // purple
        "type"           to Color(0xFF4EC9B0), // teal
        "class"          to Color(0xFF4EC9B0), // teal
        "enum"            to Color(0xFF4EC9B0), // teal
        "interface"       to Color(0xFFB5CEA8), // light green
        "struct"          to Color(0xFF4EC9B0), // teal
        "typeParameter"   to Color(0xFF569CD6), // blue
        "parameter"       to Color(0xFF9CDCFE), // light blue
        "variable"        to Color(0xFF9CDCFE), // light blue
        "property"        to Color(0xFF9CDCFE), // light blue
        "enumMember"      to Color(0xFF4FC1FF), // bright blue
        "decorator"       to Color(0xFFDCDCAA), // yellow
        "event"           to Color(0xFFFFD700), // gold
        "function"        to Color(0xFFDCDCAA), // yellow
        "method"          to Color(0xFFDCDCAA), // yellow
        "macro"           to Color(0xFFC586C0), // purple
        "keyword"         to Color(0xFF569CD6), // blue
        "modifier"        to Color(0xFF569CD6), // blue
        "comment"         to Color(0xFF6A9955), // green
        "string"          to Color(0xFFCE9178), // orange
        "number"          to Color(0xFFB5CEA8), // light green
        "regexp"          to Color(0xFFD16969), // red
        "operator"        to Color(0xFFD4D4D4), // white
    )

    /**
     * Parse the semantic tokens data array and convert to character offsets.
     *
     * @param data The LSP semantic tokens `data` array (delta-encoded)
     * @param text The source text of the document
     * @param legend The server's legend: { tokenTypes: [...], tokenModifiers: [...] }
     * @return List of SemanticRange with start/end offsets and colors
     */
    fun parse(
        data: JSONArray,
        text: String,
        legend: JSONObject?,
    ): List<SemanticRange> {
        val tokenTypes = legend?.optJSONArray("tokenTypes") ?: JSONArray()
        val result = mutableListOf<SemanticRange>()

        // Pre-compute line start offsets for fast lookup
        val lineStarts = computeLineStarts(text)

        var prevLine = 0
        var prevStart = 0
        var i = 0
        while (i < data.length()) {
            if (i + 4 >= data.length()) break
            val deltaLine = data.getInt(i)
            val deltaStart = data.getInt(i + 1)
            val length = data.getInt(i + 2)
            val tokenType = data.getInt(i + 3)
            // tokenModifiers = data.getInt(i + 4) // not used for coloring yet
            i += 5

            val line = if (deltaLine == 0) prevLine else prevLine + deltaLine
            val start = if (deltaLine == 0) prevStart + deltaStart else deltaStart

            prevLine = line
            prevStart = start

            // Convert (line, start) to absolute character offset
            val lineStartOffset = lineStarts.getOrElse(line) { -1 }
            if (lineStartOffset < 0) continue

            val absStart = lineStartOffset + start
            val absEnd = (absStart + length).coerceAtMost(text.length)
            if (absStart >= text.length || absStart >= absEnd) continue

            // Map token type index → name → color
            val typeName = if (tokenType < tokenTypes.length()) tokenTypes.getString(tokenType) else null
            val color = typeName?.let { tokenTypeColors[it] }
                ?: if (tokenType in standardTokenTypes.indices) tokenTypeColors[standardTokenTypes[tokenType]] else null

            if (color != null) {
                result.add(SemanticRange(
                    startOffset = absStart, endOffset = absEnd, color = color,
                    line = line, startCol = start, endCol = start + length,
                ))
            }
        }

        return result
    }

    private fun computeLineStarts(text: String): IntArray {
        val starts = mutableListOf(0)
        for (i in text.indices) {
            if (text[i] == '\n') starts.add(i + 1)
        }
        return starts.toIntArray()
    }
}
