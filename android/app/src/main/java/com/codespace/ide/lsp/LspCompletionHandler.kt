package com.codespace.ide.lsp

import org.json.JSONObject

/**
 * R4-2: Extracted from LspManager — completion request building.
 *
 * Builds the JSON-RPC params for textDocument/completion requests.
 * LspManager delegates to this and handles the actual RPC send.
 */
object LspCompletionHandler {

    fun buildCompletionParams(
        uri: String,
        line: Int,
        character: Int,
        triggerCharacter: String? = null,
        triggerKind: Int = 1, // 1=Invoked, 2=TriggerCharacter, 3=TriggerForIncompleteCompletions
    ): JSONObject {
        val context = JSONObject().apply {
            put("triggerKind", triggerKind)
            if (triggerCharacter != null) {
                put("triggerCharacter", triggerCharacter)
            }
        }
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
            put("context", context)
        }
    }

    fun buildResolveParams(itemId: Long): JSONObject {
        return JSONObject().apply {
            put("item", JSONObject().apply {
                put("label", "")
                put("id", itemId)
            })
        }
    }
}
