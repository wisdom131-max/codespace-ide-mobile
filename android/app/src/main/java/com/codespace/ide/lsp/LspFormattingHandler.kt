package com.codespace.ide.lsp

import org.json.JSONObject

/**
 * R4-7: Extracted from LspManager — formatting request building.
 *
 * Builds JSON-RPC params for textDocument/formatting,
 * textDocument/rangeFormatting, and textDocument/onTypeFormatting requests.
 * LspManager delegates to this and handles the actual RPC send.
 *
 * Inspired by sora-editor's LspFormatter which manages formatting
 * separately from the LSP client.
 */
object LspFormattingHandler {

    fun buildFormattingParams(uri: String, options: JSONObject): JSONObject {
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("options", options)
        }
    }

    fun buildRangeFormattingParams(
        uri: String,
        startLine: Int,
        startChar: Int,
        endLine: Int,
        endChar: Int,
        options: JSONObject,
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
            put("options", options)
        }
    }

    fun buildOnTypeFormattingParams(
        uri: String,
        line: Int,
        character: Int,
        ch: String,
        options: JSONObject,
    ): JSONObject {
        return JSONObject().apply {
            put("textDocument", JSONObject().apply { put("uri", uri) })
            put("position", JSONObject().apply {
                put("line", line)
                put("character", character)
            })
            put("ch", ch)
            put("options", options)
        }
    }

    fun buildDefaultFormattingOptions(): JSONObject {
        return JSONObject().apply {
            put("tabSize", 4)
            put("insertSpaces", true)
        }
    }
}
