package com.codespace.ide.lsp

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * R4-4: Extracted from LspManager — diagnostics storage and handler management.
 *
 * Manages per-language, per-URI diagnostic arrays and notification handlers.
 * LspManager delegates diagnostics get/set/handler operations to this object.
 *
 * Inspired by sora-editor's DiagnosticsContainer which manages diagnostics
 * separately from the LSP client logic.
 */
object LspDiagnosticsHandler {

    // Per-language → (uri → diagnostics)
    private val diagnosticsMap: ConcurrentHashMap<Language, ConcurrentHashMap<String, JSONArray>> = ConcurrentHashMap()

    // Per-language diagnostics change handlers
    private val handlerMap: ConcurrentHashMap<Language, (String, JSONArray) -> Unit> = ConcurrentHashMap()

    fun getDiagnostics(language: Language, uri: String): JSONArray? {
        return diagnosticsMap[language]?.get(uri)
    }

    fun setDiagnostics(language: Language, uri: String, diagnostics: JSONArray) {
        val langMap = diagnosticsMap.getOrPut(language) { ConcurrentHashMap() }
        langMap[uri] = diagnostics
        // Notify handler if registered
        handlerMap[language]?.invoke(uri, diagnostics)
    }

    fun setHandler(language: Language, handler: (String, JSONArray) -> Unit) {
        handlerMap[language] = handler
    }

    fun clearHandler(language: Language) {
        handlerMap.remove(language)
    }

    fun clearLanguage(language: Language) {
        diagnosticsMap.remove(language)
        handlerMap.remove(language)
    }

    fun clearAll() {
        diagnosticsMap.clear()
        handlerMap.clear()
    }
}
