package com.codespace.ide.preview

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Custom stylesheet for the Markdown preview (VS Code's markdown.styles, one slot).
 *
 * The value is injected into a <style> element inside the rendered document, so it is
 * sanitised on the way in: a stray closing tag would otherwise end the style block and
 * drop raw CSS into the body of the page.
 */
object PreviewTheme {

    private const val PREFS = "preview_theme"
    private const val KEY = "custom_css"

    var css: String by mutableStateOf("")
        private set

    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        css = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "").orEmpty()
    }

    fun save(context: Context, value: String) {
        css = value
        loaded = true
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    /** Removes anything that could terminate the style element early. */
    fun sanitize(value: String): String =
        value.replace("</style", "<\\/style", ignoreCase = true)
}
