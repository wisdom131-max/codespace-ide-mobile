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
