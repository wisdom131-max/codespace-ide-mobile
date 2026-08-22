package com.codespace.ide.lsp

import org.json.JSONObject

/**
 * R4-5: Extracted from LspManager — signature help request building.
 *
 * Builds JSON-RPC params for textDocument/signatureHelp requests.
 * LspManager delegates to this and handles the actual RPC send.
 */
object LspSignatureHandler {

    fun buildSignatureHelpParams(uri: String, line: Int, character: Int): JSONObject {
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
        }
    }
}
