package com.codespace.ide.editor

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * PERSIST-A (2026-09-13): find-widget state that survives restarts.
 *
 * VS Code keeps find state (query + case/word/regex toggles) per editor model
 * and restores it across restarts. Our find bar lives inside CodeEditor as
 * remember{} state, so it reset on every launch. This global store (plain prefs,
 * cross-project — the query is not project-scoped data) holds the last find
 * state; CodeEditor seeds its find vars from it and writes changes back, so the
 * find bar reopens exactly where you left it.
 *
 * Init pattern: CodeSpaceApplication calls init(this) alongside
 * FeatureToggleStore.init (lateinit prefs + lazy state seeding).
 */
object EditorFindState {

    /** Immutable per-file find configuration (G08). */
    data class FindSnapshot(
        val query: String,
        val caseSensitive: Boolean,
        val wholeWord: Boolean,
        val useRegex: Boolean,
    )

    private const val PREFS = "editor_find_state"
    private lateinit var prefs: SharedPreferences

    private const val KEY_QUERY = "query"
    private const val KEY_CASE = "case_sensitive"
    private const val KEY_WORD = "whole_word"
    private const val KEY_REGEX = "use_regex"

    var query by mutableStateOf("")
    var caseSensitive by mutableStateOf(false)
    var wholeWord by mutableStateOf(false)
    var useRegex by mutableStateOf(false)

    // G08 (P4b): the store was ONE global slot — every file shared the last query
    // and toggles, so closing file A's find bar leaked its query into file B (VS
    // Code keys find state per editor MODEL). Entries are now keyed by the
    // canonical path; the global vars remain as the untitled-buffer default and
    // cross-file "last used" seed.
    private fun fileKey(path: String?, field: String): String =
        field + "@" + com.codespace.ide.util.CanonicalPaths.canonicalKey(path ?: "")

    /** Per-file snapshot, falling back to the global last-used state. */
    fun snapshotFor(path: String?): FindSnapshot {
        if (path.isNullOrBlank() || !::prefs.isInitialized) {
            return FindSnapshot(query, caseSensitive, wholeWord, useRegex)
        }
        val q = prefs.getString(fileKey(path, KEY_QUERY), null) ?: query
        val c = if (prefs.contains(fileKey(path, KEY_CASE))) prefs.getBoolean(fileKey(path, KEY_CASE), false) else caseSensitive
        val w = if (prefs.contains(fileKey(path, KEY_WORD))) prefs.getBoolean(fileKey(path, KEY_WORD), false) else wholeWord
        val r = if (prefs.contains(fileKey(path, KEY_REGEX))) prefs.getBoolean(fileKey(path, KEY_REGEX), false) else useRegex
        return FindSnapshot(q ?: "", c, w, r)
    }

    /** Persist the per-file entry (and refresh the global last-used defaults). */
    fun saveFor(path: String?, snapshot: FindSnapshot) {
        query = snapshot.query
        caseSensitive = snapshot.caseSensitive
        wholeWord = snapshot.wholeWord
        useRegex = snapshot.useRegex
        if (!::prefs.isInitialized || path.isNullOrBlank()) {
            persist()
            return
        }
        prefs.edit()
            .putString(fileKey(path, KEY_QUERY), snapshot.query)
            .putBoolean(fileKey(path, KEY_CASE), snapshot.caseSensitive)
            .putBoolean(fileKey(path, KEY_WORD), snapshot.wholeWord)
            .putBoolean(fileKey(path, KEY_REGEX), snapshot.useRegex)
            .apply()
        persist()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        query = prefs.getString(KEY_QUERY, "") ?: ""
        caseSensitive = prefs.getBoolean(KEY_CASE, false)
        wholeWord = prefs.getBoolean(KEY_WORD, false)
        useRegex = prefs.getBoolean(KEY_REGEX, false)
    }

    fun persist() {
        if (::prefs.isInitialized) {
            prefs.edit()
                .putString(KEY_QUERY, query)
                .putBoolean(KEY_CASE, caseSensitive)
                .putBoolean(KEY_WORD, wholeWord)
                .putBoolean(KEY_REGEX, useRegex)
                .apply()
        }
    }
}
