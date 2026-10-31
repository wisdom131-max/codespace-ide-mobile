package com.codespace.ide.preview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "A file was saved" in one place.
 *
 * LivePreviewServer has had an SSE reload channel and `pushReload()` since PhaseX, and nothing
 * ever called it: the preview only refreshed when the user tapped reload. Rather than wire the SSE
 * client (which needs a script injected into every served page, including the user's own HTML that
 * may not want scripts), the editor tells this bus, and the preview reloads itself when the saved
 * path is the file it is showing.
 *
 * Pure and Android-free, so the decision is unit-tested instead of eyeballed.
 */
object PreviewReloadBus {

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /** Last path saved, for a preview that is watching one file. */
    @Volatile
    var lastSavedPath: String? = null
        private set

    fun notifySaved(path: String) {
        lastSavedPath = path
        _revision.value = _revision.value + 1
    }

    fun reset() {
        lastSavedPath = null
        _revision.value = 0L
    }
}
