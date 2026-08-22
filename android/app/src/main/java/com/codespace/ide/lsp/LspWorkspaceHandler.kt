package com.codespace.ide.lsp

import org.json.JSONArray
import org.json.JSONObject

/**
 * R4-9: Extracted from LspManager — workspace request building.
 *
 * Builds JSON-RPC params for workspace/executeCommand,
 * workspace/symbol, and workspace/willRenameFiles requests.
 * LspManager delegates to this and handles the actual RPC send.
 */
object LspWorkspaceHandler {

    fun buildExecuteCommandParams(command: String, args: JSONArray): JSONObject {
        return JSONObject().apply {
            put("command", command)
            put("arguments", args)
        }
    }

    fun buildWorkspaceSymbolParams(query: String): JSONObject {
        return JSONObject().apply {
            put("query", query)
        }
    }

    fun buildWillRenameFilesParams(
        oldUri: String,
        newUri: String,
    ): JSONObject {
        val file = JSONObject().apply {
            put("oldUri", oldUri)
            put("newUri", newUri)
        }
        return JSONObject().apply {
            put("files", JSONArray().apply { put(file) })
        }
    }

    fun buildDidRenameFilesParams(
        oldUri: String,
        newUri: String,
    ): JSONObject {
        val file = JSONObject().apply {
            put("oldUri", oldUri)
            put("newUri", newUri)
        }
        return JSONObject().apply {
            put("files", JSONArray().apply { put(file) })
        }
    }
}
