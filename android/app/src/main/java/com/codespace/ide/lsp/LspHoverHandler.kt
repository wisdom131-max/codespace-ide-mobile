package com.codespace.ide.lsp

import org.json.JSONObject

/**
 * R4-3: Extracted from LspManager — hover request building.
 *
 * Builds JSON-RPC params for textDocument/hover requests.
 * LspManager delegates to this and handles the actual RPC send.
 *
 * Inspired by sora-editor's HoverWindow which manages hover display
 * separately from the LSP client.
 */
object LspHoverHandler {

    fun buildHoverParams(uri: String, line: Int, character: Int): JSONObject {
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        }
    }
}
