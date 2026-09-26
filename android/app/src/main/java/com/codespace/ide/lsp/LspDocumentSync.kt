package com.codespace.ide.lsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4-8: Extracted from LspManager — document synchronization request builders.
 *
 * Pure functions that build the JSON-RPC params for textDocument/didOpen,
 * didChange, didClose, and didSave notifications. LspManager delegates
 * to these and handles the actual send + state tracking.
 *
 * Inspired by sora-editor's LspEditor which manages document sync separately
 * from request routing.
 */
object LspDocumentSync {

    fun buildDidOpenParams(uri: String, languageId: String, text: String, version: Int): JSONObject {
        val td = JSONObject().apply {
            put("uri", uri)
            put("languageId", languageId)
            put("version", version)
            put("text", text)
        }
        return JSONObject().apply { put("textDocument", td) }
    }

    fun buildDidChangeParams(uri: String, text: String, version: Int): JSONObject {
        val td = JSONObject().apply {
            put("uri", uri)
            put("version", version)
        }
        val change = JSONObject().apply { put("text", text) }
        val changes = JSONArray().apply { put(change) }
        return JSONObject().apply {
            put("textDocument", td)
            put("contentChanges", changes)
        }
    }

    // LS04 (P4b): incremental didChange support.
    //
    // declaredSyncKind() parses the server's textDocumentSync capability: a plain
    // number (0=None, 1=Full, 2=Incremental) or an object { openClose, change }.
    // Returns 1 (Full) when absent — the conservative assumption (full form is
    // always spec-valid even for incremental servers).
    fun declaredSyncKind(capabilities: org.json.JSONObject?): Int {
        val sync = capabilities?.opt("textDocumentSync") ?: return 1
        return when (sync) {
            is Int -> sync
            is org.json.JSONObject -> sync.optInt("change", 1)
            else -> 1
        }
    }

    /**
     * ONE minimal range edit computed from the text the server last received
     * (common-prefix/suffix trim). If the changed region is large relative to the
     * old text, fall back to the full-text form — cheap to compute, cheap to send.
     */
    fun buildIncrementalDidChangeParams(uri: String, oldText: String, newText: String, version: Int): JSONObject {
        var prefix = 0
        val minLen = minOf(oldText.length, newText.length)
        while (prefix < minLen && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < minLen - prefix &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) suffix++
        val oldEnd = oldText.length - suffix
        val newEnd = newText.length - suffix
        // Large-change guard: an edit touching >60% of the old text sends FULL sync.
        if (oldEnd - prefix > oldText.length * 6 / 10) {
            return buildDidChangeParams(uri, newText, version)
        }
        val startLC = offsetToLineCol(oldText, prefix)
        val endLC = offsetToLineCol(oldText, oldEnd)
        val td = JSONObject().apply { put("uri", uri); put("version", version) }
        val change = JSONObject().apply {
            put("range", JSONObject().apply {
                put("start", JSONObject().apply { put("line", startLC.first); put("character", startLC.second) })
                put("end", JSONObject().apply { put("line", endLC.first); put("character", endLC.second) })
            })
            put("text", newText.substring(prefix, newEnd))
        }
        return JSONObject().apply {
            put("textDocument", td)
            put("contentChanges", JSONArray().put(change))
        }
    }

    /** UTF-16 code-unit line/character of a string offset (LSP position convention). */
    private fun offsetToLineCol(text: String, offset: Int): Pair<Int, Int> {
        var line = 0
        var lastNewline = -1
        var i = 0
        while (i < offset && i < text.length) {
            if (text[i] == '\n') { line++; lastNewline = i }
            i++
        }
        return Pair(line, offset - lastNewline - 1)
    }

    fun buildDidCloseParams(uri: String): JSONObject {
        val td = JSONObject().apply { put("uri", uri) }
        return JSONObject().apply { put("textDocument", td) }
    }

    fun buildDidSaveParams(uri: String, content: String): JSONObject {
        val td = JSONObject().apply { put("uri", uri) }
        return JSONObject().apply {
            put("textDocument", td)
            put("text", content)
        }
    }
}
