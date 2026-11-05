package com.codespace.ide.chat.panel

/**
 * Owner item 5: removing one attachment must remove ONLY that attachment.
 *
 * Kept as a pure function rather than inline in the session so the rule is testable without an
 * Android runtime: a list of pending attachments, an index from the chip the user tapped, and the
 * list minus exactly that row. Out-of-range removes nothing (a stale chip after a rebuild must not
 * silently delete an unrelated file), and the draft text is not part of this list at all, which is
 * why removing a chip can never touch what the user has typed.
 */
internal object PendingRemoval {

    fun <T> withoutAt(items: List<T>, index: Int): List<T> =
        if (index < 0 || index >= items.size) items else items.filterIndexed { i, _ -> i != index }
}
