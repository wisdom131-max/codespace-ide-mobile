package com.codespace.ide.editor

import androidx.compose.runtime.mutableStateOf

/**
 * The one place the indexer says, in plain words, that a safety cap stopped it.
 *
 * Owner rule (2026-11-06): a limit that stops work is a measured safety net, not a silent
 * truncation. The watcher caps its snapshot at 8,000 files and its observer set at 256
 * directories; when either trips, the Explorer footer carries one sentence saying what is
 * covered and what is not, and the Output tab gets the name-only detail.
 *
 * Compose state in an object: the Explorer reads it, the indexer writes it off the main
 * thread, and Compose handles a state write from any thread.
 */
object IndexWatchNotice {

    private val _text = mutableStateOf<String?>(null)

    /** Null when nothing was capped - the normal case, and the only case on small projects. */
    val text: String? get() = _text.value

    fun show(message: String) {
        _text.value = message
    }

    fun clear() {
        _text.value = null
    }
}
