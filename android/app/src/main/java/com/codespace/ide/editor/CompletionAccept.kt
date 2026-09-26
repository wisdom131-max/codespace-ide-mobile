package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.SnippetContext
import com.codespace.ide.lsp.SnippetParseResult
import com.codespace.ide.lsp.SnippetSession
import com.codespace.ide.lsp.createSnippetSession
import com.codespace.ide.lsp.parseSnippet
import org.json.JSONArray
import org.json.JSONObject

/**
 * IC01/IC02/IC03/IC04 (P4c): ONE shared completion-accept computation.
 *
 * Before this, the four accept routes each had their own splice semantics:
 *  - TAP handled additionalTextEdits + snippets, but ignored textEdit and command;
 *  - TAB did a plain insert whose word scan ALSO crossed '.', so accepting
 *    "method" in "obj.m|" replaced the whole "obj.m" — silent code corruption;
 *  - commit characters did a plain insert ignoring every extended field;
 *  - nothing ever applied the server's range-based textEdit (IC02).
 *
 * Every route now calls [compute] for the text/cursor/snippet outcome and
 * [executeCommand] for the post-accept LSP command (IC03).
 */
internal object CompletionAccept {

    /**
     * The result of one accept computation. When [error] is non-null the
     * computation fell back to a PLAIN insert (insertText at the word range)
     * and the caller must surface the reason — auto-import edits failing must
     * never be silent (IC04/S01 rule).
     */
    internal class Outcome(
        val newText: String,
        val newCursor: Int,
        /** Selection to apply (first tab-stop default text); -1/-1 = plain cursor. */
        val selectionStart: Int = -1,
        val selectionEnd: Int = -1,
        val snippetSession: SnippetSession? = null,
        val showSnippetChoices: Boolean = false,
        /** The raw parse result, so callers that patch imports afterwards can rebuild the session shifted. */
        val snippetParsed: SnippetParseResult? = null,
        /** Offset of the inserted text start in newText (pre-import-patch). */
        val insertStart: Int = 0,
        val usedTextEdit: Boolean = false,
        val usedAdditionalEdits: Boolean = false,
        val error: String? = null,
    )

    /**
     * IC01: the word range to replace. NEVER crosses '.' — accepting a member
     * in a dot context replaces only the member name, not "obj.member".
     */
    internal fun wordRangeBefore(text: String, cursor: Int): Int {
        var start = cursor.coerceAtMost(text.length)
        while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] == '_')) start--
        return start
    }

    /**
     * Compute the full accept. Pure text computation — safe on any dispatcher;
     * JSON parsing is done here, so callers run this on Dispatchers.IO.
     *
     * @param commitChar when set (commit-character route), the char is appended
     *        after the inserted text and no snippet session is created.
     */
    internal fun compute(
        comp: Completion,
        text: String,
        cursor: Int,
        positionMapper: PositionMapper,
        commitChar: Char? = null,
    ): Outcome {
        val end = cursor.coerceAtMost(text.length)

        // ── 1. Replacement range ─────────────────────────────────────────
        // IC02: a server-provided range textEdit is authoritative — it replaces
        // the word-scan approximation (servers know the exact span to rewrite).
        var rangeStart = wordRangeBefore(text, end)
        var rangeEnd = end
        var usedTextEdit = false
        if (!comp.textEditJson.isNullOrBlank()) {
            try {
                val te = JSONObject(comp.textEditJson)
                val rng = te.optJSONObject("range")
                if (rng != null) {
                    val sLine = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                    val sChar = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                    val eLine = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                    val eChar = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                    val s = positionMapper.lspToOffset(sLine, sChar)
                    val e = positionMapper.lspToOffset(eLine, eChar)
                    if (s in 0..text.length && e in 0..text.length && e >= s) {
                        rangeStart = s
                        rangeEnd = e
                        usedTextEdit = true
                    }
                }
            } catch (_: Exception) {
                // Malformed textEdit — fall back to the word scan (honest degradation)
            }
        }

        // ── 2. Insert text (snippet-aware) ─────────────────────────────────
        val insertText: String
        val snippetParsed: SnippetParseResult?
        if (comp.insertTextFormat == 2 && commitChar == null) {
            val parsed = parseSnippet(
                comp.insertText,
                SnippetContext(
                    fileName = "",
                    lineNumber = positionMapper.offsetToLine(rangeStart) + 1,
                    lineIndex = positionMapper.offsetToLine(rangeStart),
                    currentLine = positionMapper.getLineText(text, positionMapper.offsetToLine(rangeStart)),
                    selectedText = if (rangeStart != rangeEnd) text.substring(rangeStart, rangeEnd) else "",
                ),
            )
            insertText = parsed.cleanedText
            snippetParsed = parsed
        } else if (comp.insertTextFormat == 2) {
            // Snippet committed by a commit character — insert cleaned text, no session
            insertText = parseSnippet(comp.insertText, SnippetContext()).cleanedText
            snippetParsed = null
        } else {
            insertText = comp.insertText
            snippetParsed = null
        }

        // ── 3. additionalTextEdits (auto-import) FIRST, with the IC04 rule ─
        // Edits that END at-or-above the replacement range shift it; edits
        // below leave it untouched. The old tap path applied a blanket delta.
        var baseText = text
        var shift = 0
        var usedAdditionalEdits = false
        var error: String? = null
        if (!comp.additionalTextEditsJson.isNullOrBlank()) {
            try {
                val editsArray = JSONArray(comp.additionalTextEditsJson)
                for (ei in 0 until editsArray.length()) {
                    val te = editsArray.optJSONObject(ei) ?: continue
                    val rng = te.optJSONObject("range") ?: continue
                    val esLine = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
                    val esChar = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
                    val eeLine = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
                    val eeChar = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
                    val editStart = positionMapper.lspToOffset(esLine, esChar)
                    val editEnd = positionMapper.lspToOffset(eeLine, eeChar)
                    if (editEnd <= rangeStart) {
                        shift += te.optString("newText", "").length - (editEnd - editStart)
                    }
                }
                baseText = applyLspTextEdits(text, editsArray)
                usedAdditionalEdits = true
            } catch (e: Exception) {
                // IC04/S01: the auto-import failed — fall back to a plain insert and
                // REPORT it; the caller logs to the Output tab / shows a notification.
                error = "additionalTextEdits failed: ${e.message ?: e.javaClass.simpleName}"
                baseText = text
                shift = 0
            }
        }

        // ── 4. Splice ──────────────────────────────────────────────────────
        val s = (rangeStart + shift).coerceIn(0, baseText.length)
        val e = (rangeEnd + shift).coerceIn(0, baseText.length)
        val commitSuffix = commitChar?.toString() ?: ""
        val newText = baseText.substring(0, s) + insertText + commitSuffix + baseText.substring(e)
        val insertStart = s

        // ── 5. Cursor / snippet session ─────────────────────────────────────
        if (snippetParsed != null) {
            val session = createSnippetSession(insertStart, snippetParsed)
            val firstStop = session.tabStops.firstOrNull()
            val selStart: Int
            val selEnd: Int
            if (firstStop != null && firstStop.defaultText.isNotEmpty()) {
                selStart = firstStop.startOffset
                selEnd = firstStop.endOffset
            } else {
                selStart = firstStop?.startOffset ?: session.finalCursorOffset
                selEnd = selStart
            }
            return Outcome(
                newText = newText,
                newCursor = selStart,
                selectionStart = selStart,
                selectionEnd = selEnd,
                snippetSession = session,
                showSnippetChoices = session.tabStops.firstOrNull()?.choices?.isNotEmpty() == true,
                snippetParsed = snippetParsed,
                insertStart = insertStart,
                usedTextEdit = usedTextEdit,
                usedAdditionalEdits = usedAdditionalEdits,
                error = error,
            )
        }
        val plainCursor = s + insertText.length + commitSuffix.length
        return Outcome(
            newText = newText,
            newCursor = plainCursor,
            insertStart = insertStart,
            usedTextEdit = usedTextEdit,
            usedAdditionalEdits = usedAdditionalEdits,
            error = error,
        )
    }

    /**
     * IC03: execute the completion's LSP `command` after accept. Returns whether
     * a command was found and attempted; failures are logged honestly — a command
     * that cannot run is never silently dropped.
     */
    internal fun executeCommand(language: Language, commandJson: String?): Boolean {
        if (commandJson.isNullOrBlank()) return false
        return try {
            val cmd = JSONObject(commandJson)
            val name = cmd.optString("command", "")
            if (name.isBlank()) return false
            val args = cmd.optJSONArray("arguments")
            val result = com.codespace.ide.lsp.LspManager.executeCommand(language, name, args)
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[Completion] post-accept command '$name' executed" + (if (result == null) " (no result/error — see LSP log)" else ""),
                "lsp",
            )
            true
        } catch (e: Exception) {
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[Completion] post-accept command FAILED: ${e.message ?: e.javaClass.simpleName}",
                "lsp",
            )
            false
        }
    }

    /** Apply a JSON array of LSP TextEdits to content (imports etc.). */
    private fun applyLspTextEdits(content: String, edits: JSONArray): String {
        // Highest-position-first so earlier splices don't invalidate later offsets
        val parsed = mutableListOf<Triple<Int, Int, String>>()  // start, end, newText
        val mapper = PositionMapper(content)
        for (i in 0 until edits.length()) {
            val te = edits.optJSONObject(i) ?: continue
            val rng = te.optJSONObject("range") ?: continue
            val sLine = rng.optJSONObject("start")?.optInt("line", 0) ?: 0
            val sChar = rng.optJSONObject("start")?.optInt("character", 0) ?: 0
            val eLine = rng.optJSONObject("end")?.optInt("line", 0) ?: 0
            val eChar = rng.optJSONObject("end")?.optInt("character", 0) ?: 0
            val start = mapper.lspToOffset(sLine, sChar)
            val end = mapper.lspToOffset(eLine, eChar)
            if (start in 0..content.length && end in 0..content.length && end >= start) {
                parsed.add(Triple(start, end, te.optString("newText", "")))
            }
        }
        var out = content
        for ((s, e, newText) in parsed.sortedByDescending { it.first }) {
            out = out.substring(0, s) + newText + out.substring(e)
        }
        return out
    }
}
