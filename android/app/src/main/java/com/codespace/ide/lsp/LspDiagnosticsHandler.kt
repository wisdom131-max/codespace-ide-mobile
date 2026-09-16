package com.codespace.ide.lsp

import org.json.JSONArray
import com.codespace.ide.domain.Language
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R4-4: Extracted from LspManager — diagnostics storage and handler management.
 *
 * Manages per-language, per-URI diagnostic arrays and notification handlers.
 * LspManager delegates diagnostics get/set/handler operations to this object.
 *
 * Inspired by sora-editor's DiagnosticsContainer which manages diagnostics
 * separately from the LSP client logic.
 *
 * BUG-B SPLIT-SAFETY (2026-09-16): handlers are now a LIST per language, not a
 * single slot. Split views mount one EditorPane (and therefore one diagnostics
 * handler) per pane; with the old single-slot map, pane B's registration silently
 * REPLACED pane A's, so a second pane on the same language froze the first
 * pane's squiggle updates. Every registered handler now receives every push and
 * drops pushes for URIs that are not its own live tab (EditorPane's existing
 * live-tab guard). addHandler/removeHandler are paired by DisposableEffect so
 * no pane leaks or steals another's diagnostics.
 */
object LspDiagnosticsHandler {

    // Per-language → (uri → diagnostics)
    private val diagnosticsMap: ConcurrentHashMap<Language, ConcurrentHashMap<String, JSONArray>> = ConcurrentHashMap()

    // Per-language diagnostics change handlers (MULTI — see header note)
    private val handlerMap: ConcurrentHashMap<Language, CopyOnWriteArrayList<(String, JSONArray) -> Unit>> = ConcurrentHashMap()

    /** Server push: store + notify (legacy name kept — LspManager 1582 call site). */
    fun setDiagnostics(language: Language, uri: String, diagnostics: JSONArray) {
        put(language, uri, diagnostics)
    }

    fun put(language: Language, uri: String, diagnostics: JSONArray) {
        diagnosticsMap.computeIfAbsent(language) { ConcurrentHashMap() }[uri] = diagnostics
        handlerMap[language]?.forEach { h -> h(uri, diagnostics) }
    }

    fun get(language: Language, uri: String): JSONArray? =
        diagnosticsMap[language]?.get(uri)

    fun clear(language: Language, uri: String) {
        val empty = JSONArray()
        diagnosticsMap[language]?.remove(uri)
        handlerMap[language]?.forEach { h -> h(uri, empty) }
    }

    /** Registers an additional handler; call removeHandler with the SAME instance on dispose. */
    fun addHandler(language: Language, handler: (String, JSONArray) -> Unit) {
        handlerMap.computeIfAbsent(language) { CopyOnWriteArrayList() }.add(handler)
    }

    fun removeHandler(language: Language, handler: (String, JSONArray) -> Unit) {
        handlerMap[language]?.remove(handler)
    }

    /** Legacy single-slot API kept for compile compatibility — now appends. */
    @Deprecated("use addHandler/removeHandler pair")
    fun setHandler(language: Language, handler: (String, JSONArray) -> Unit) {
        addHandler(language, handler)
    }

    /** Legacy clear — removes nothing under multi-handler; kept for compatibility. */
    @Deprecated("use removeHandler with your instance")
    fun clearHandler(language: Language) {
        handlerMap.remove(language)
    }

    fun clearLanguage(language: Language) {
        handlerMap.remove(language)
    }

    fun clearAll() {
        handlerMap.clear()
    }
}
