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
