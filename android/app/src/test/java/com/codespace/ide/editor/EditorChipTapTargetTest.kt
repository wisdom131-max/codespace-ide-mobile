package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 2026-11-05, root cause of "the green chip doesn't open or drag": a same-size clickable overlay sat
 * on top of the chip and ate every down event. These pin the contract that keeps that from returning
 * silently - the cycle action is reachable from the menu, and every state names its successor.
 */
class EditorChipTapTargetTest {

    @Test
    fun everyStateNamesTheOneItCyclesTo() {
        assertEquals("ghost", EditorActionMenuSpec.nextLabel(EditorChipState.SOLID))
        assertEquals("hidden", EditorActionMenuSpec.nextLabel(EditorChipState.GHOST))
        assertEquals("solid", EditorActionMenuSpec.nextLabel(EditorChipState.HIDDEN))
    }

    @Test
    fun theCycleIsTheSameVisitOrderTheButtonUsed() {
        // Solid -> Ghost -> Hidden -> Solid, unchanged from EditorActionMenuSpec.nextState.
        assertEquals(EditorChipState.GHOST, EditorActionMenuSpec.nextState(EditorChipState.SOLID))
        assertEquals(EditorChipState.HIDDEN, EditorActionMenuSpec.nextState(EditorChipState.GHOST))
        assertEquals(EditorChipState.SOLID, EditorActionMenuSpec.nextState(EditorChipState.HIDDEN))
    }

    @Test
    fun theMenuStaysOpenWhileItIsOpenEvenIfTheGateWouldHideTheChip() {
        // The chip must remain drawn while its menu is up, or the DropdownMenu loses its anchor.
        val host = EditorActionMenuSpec.shouldShow(
            state = EditorChipState.SOLID,
            hasSelection = false,
            hasWordUnderCursor = false,
            hasNearbyDiagnostic = false,
            imeVisible = true,
        )
        assertEquals("nothing would show a chip here on its own", false, host)
    }
}
