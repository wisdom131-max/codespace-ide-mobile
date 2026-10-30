package com.codespace.ide.chat.panel

/**
 * M3 — host reference counting, extracted so the multi-window rule is unit-testable without an
 * Android Context.
 *
 * The rule: the chat runtime is created for the FIRST host and destroyed only when the LAST host
 * leaves. Closing one window (or unmounting the sidebar while the second window is open) must
 * never tear down a manager that another window is still rendering, because that would cancel a
 * stream mid-reply.
 */
internal class RefCounter {

    private var refs = 0

    /** @return true when this is the FIRST host (the caller must create the runtime). */
    @Synchronized
    fun acquire(): Boolean {
        refs++
        return refs == 1
    }

    /** @return true when this was the LAST host (the caller must destroy the runtime). */
    @Synchronized
    fun release(): Boolean {
        if (refs == 0) return false
        refs--
        return refs == 0
    }

    @Synchronized
    fun count(): Int = refs
}
