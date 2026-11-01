package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chip's drag maths.
 *
 * Owner report: the drag did not move the chip. Cause was the Popup: the chip is the popup's
 * content, so the window moves with it and a delta measured inside the window cancels itself.
 * These pin the absolute tracking that replaced it - including the "window already moved under
 * me" frame that used to freeze the chip.
 */
class EditorChipDragTest {

    private fun placement() = EditorChipPlacement(start = 100f to 100f, maxX = 300f, maxY = 500f)

    @Test
    fun theChipFollowsTheFinger() {
        val p = placement()
        p.beginDrag(0f, 0f)
        // 27.5 px at density 2.75 is 10 dp to the right.
        p.dragTo(localPx = 27.5f, localPy = 0f, densityPx = 2.75f)
        assertEquals(110f, p.x, 0.01f)
        assertEquals(100f, p.y, 0.01f)
    }

    @Test
    fun aFrameWhereTheWindowAlreadyMovedDoesNotCancelTheDrag() {
        val p = placement()
        p.beginDrag(0f, 0f)
        p.dragTo(localPx = 27.5f, localPy = 0f, densityPx = 2.75f)
        val afterFirst = p.x
        // The popup window has now moved to the chip's new position, so the same stationary
        // finger reports local position ~0 again. The chip must not move back.
        p.dragTo(localPx = 0f, localPy = 0f, densityPx = 2.75f)
        assertEquals(afterFirst, p.x, 0.01f)
    }

    @Test
    fun theChipStopsAtTheEdges() {
        val p = placement()
        p.beginDrag(0f, 0f)
        val chip = EditorActionMenuSpec.diameterDp(EditorChipState.SOLID).toFloat()
        val margin = EditorActionMenuSpec.EDGE_MARGIN_DP
        p.dragTo(localPx = 10_000f, localPy = 10_000f, densityPx = 2.75f)
        assertEquals(300f - chip - margin, p.x, 0.01f)
        assertEquals(500f - chip - margin, p.y, 0.01f)
        // The chip keeps a margin from the edge, so the icon never sits half outside the editor
        // and never under the pane border where it cannot be tapped.
        p.dragTo(localPx = -10_000f, localPy = -10_000f, densityPx = 2.75f)
        assertEquals(margin, p.x, 0.01f)
        assertEquals(margin, p.y, 0.01f)
    }

    @Test
    fun aFingerThatDidNotLongPressFirstDoesNotMoveTheChip() {
        val p = placement()
        // No beginDrag: a plain tap must leave the chip exactly where it was.
        p.dragTo(localPx = 500f, localPy = 500f, densityPx = 2.75f)
        assertEquals(100f, p.x, 0.01f)
        assertEquals(100f, p.y, 0.01f)
    }
}
