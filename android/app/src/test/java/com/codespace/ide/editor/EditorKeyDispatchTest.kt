package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH4-P1 regression tests (dead-chord dispatch): every EditorAction must
 * be DISPATCHED via chord (calling the same function the menu/button calls)
 * or DOCUMENTED in the exclusion map with an owner-visible reason — the
 * dead-chord state (only 10 of 40 chords firing while Settings listed them
 * all as bindable) can never be reintroduced silently.
 */
class EditorKeyDispatchTest {

    @Test
    fun everyBindableAction_isDispatchedOrDocumented() {
        val all = EditorAction.values().toSet()
        val dispatched = EditorKeyDispatch.DISPATCHED
        val excluded = EditorKeyDispatch.EXCLUDED.keys
        // Nothing in both buckets (a dispatch that also claims to be excluded is a lie).
        assertTrue(
            "no action may be both dispatched and excluded: " +
                (dispatched intersect excluded),
            (dispatched intersect excluded).isEmpty(),
        )
        // Every action covered.
        val uncovered = all - dispatched - excluded
        assertTrue(
            "every EditorAction must be dispatched or documented-excluded (uncovered: $uncovered)",
            uncovered.isEmpty(),
        )
        // No stale entries (an action removed from the enum would hide here).
        val stale = (dispatched + excluded) - all
        assertTrue("no stale registry entries: $stale", stale.isEmpty())
        // Every exclusion carries a real reason, not a placeholder.
        EditorKeyDispatch.EXCLUDED.forEach { (action, reason) ->
            assertTrue("exclusion reason for $action must be non-blank", reason.isNotBlank())
        }
    }

    @Test
    fun stepMatchIndex_wrapsBothWaysAndGuardsEmpty() {
        // The exact math the FindReplaceBar nav buttons used (modulo wrap).
        assertEquals(1, EditorKeyDispatch.stepMatchIndex(3, 0, +1))
        assertEquals(2, EditorKeyDispatch.stepMatchIndex(3, 1, +1))
        assertEquals(0, EditorKeyDispatch.stepMatchIndex(3, 2, +1)) // wrap forward
        assertEquals(2, EditorKeyDispatch.stepMatchIndex(3, 0, -1)) // wrap backward
        assertEquals(1, EditorKeyDispatch.stepMatchIndex(3, 2, -1))
        assertEquals(0, EditorKeyDispatch.stepMatchIndex(0, 0, +1)) // no matches: guard
        assertEquals(0, EditorKeyDispatch.stepMatchIndex(-1, 5, +1)) // defensive
    }
}
