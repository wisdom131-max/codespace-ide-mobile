package com.codespace.ide.chat.panel

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * M3: a staged file opened from the "new window" task belongs to the editor in the MAIN task.
 * The request is queued here and consumed by the shell, which brings the tab up. Deliberately
 * a one-slot queue: a file-open is a navigation intent, not a history.
 */
object ChatFileOpenRequest {
    private val slot = MutableStateFlow<String?>(null)
    val pending = slot

    fun request(path: String) {
        slot.value = path
    }

    fun consume(path: String) {
        if (slot.value == path) slot.value = null
    }
}
