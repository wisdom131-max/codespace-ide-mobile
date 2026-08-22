package com.codespace.ide.lsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4-6: Extracted from LspManager — code action request building.
 *
 * Builds JSON-RPC params for textDocument/codeAction and
 * codeAction/resolve requests.
 * LspManager delegates to this and handles the actual RPC send.
 *
 * Inspired by sora-editor's CodeActionWindow which manages code actions
 * separately from the LSP client.
 */
object LspCodeActionHandler {

    fun buildCodeActionParams(
        uri: String,
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
        only: List<String>? = null,
    ): JSONObject {
        val range = JSONObject().apply {
            put("start", JSONObject().apply {
                put("line", startLine)
                put("character", startChar)
            })
            put("end", JSONObject().apply {
                put("line", endLine)
                put("character", endChar)
            })
        }
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("range", range)
            if (only != null) {
                put("context", JSONObject().apply {
                    put("diagnostics", JSONArray())
                    put("only", JSONArray().apply { only.forEach { put(it) } })
                })
            } else {
                put("context", JSONObject().apply {
                    put("diagnostics", JSONArray())
                })
            }
        }
    }

    fun buildResolveParams(codeAction: JSONObject): JSONObject {
        return JSONObject().apply {
            put("item", codeAction)
        }
    }
}
