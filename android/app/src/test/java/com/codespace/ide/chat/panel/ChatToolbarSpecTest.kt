package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bottom-row collapse rules at the widths the owner named, with and without the
 * keyboard. The row now holds MODE, MODEL and TOOLS only: the plus moved to its own
 * row above the field and the microphone was removed outright (owner spec 2026-10-29),
 * so neither competes for width here. The item set is read from the enum, so adding or
 * removing an item updates every invariant instead of leaving a hardcoded count behind.
 */
class ChatToolbarSpecTest {

    private val all = ToolbarItem.values().toSet()

    private fun spec(w: Int, ime: Boolean = false) = ChatToolbarLayout.forWidth(w, ime)

    @Test
    fun theRowHoldsExactlyModeModelAndTools() {
        assertEquals(setOf(ToolbarItem.MODE, ToolbarItem.MODEL, ToolbarItem.TOOLS), all)
    }

    @Test
    fun noItemIsEverLostOrDuplicatedAtAnyWidth() {
        for (w in listOf(0, 120, 240, 280, 320, 360, 400, 480, 600, 840, 2000)) {
            for (ime in listOf(false, true)) {
                val s = spec(w, ime)
                val combined = s.inline + s.overflow
                assertEquals("w=$w ime=$ime: every item accounted for once", all, combined.toSet())
                assertEquals("w=$w ime=$ime: no duplicates", all.size, combined.size)
            }
        }
    }

    @Test
    fun theModelChipIsAlwaysInlineAtEveryWidth() {
        for (w in -50..2000 step 10) {
            for (ime in listOf(false, true)) {
                assertTrue("w=$w ime=$ime", ToolbarItem.MODEL in spec(w, ime).inline)
            }
        }
    }

    @Test
    fun at240OnlyTheModelStaysInlineAndTheRestOverflowsInsteadOfClipping() {
        val s = spec(240)
        assertTrue(s.hasOverflow)
        assertEquals(listOf(ToolbarItem.MODEL), s.inline)
        assertEquals(listOf(ToolbarItem.MODE, ToolbarItem.TOOLS), s.overflow)
    }

    @Test
    fun at280ModeJoinsTheModelAndOnlyToolsOverflows() {
        val s = spec(280)
        assertEquals(listOf(ToolbarItem.MODE, ToolbarItem.MODEL), s.inline)
        assertEquals(listOf(ToolbarItem.TOOLS), s.overflow)
        assertFalse("no effort suffix at 280dp, it would ellipsize", s.modelShowsEffort)
        assertFalse("icon-only mode at 280dp", s.modeShowsLabel)
    }

    @Test
    fun atAnOrdinaryPhoneWidthNothingOverflows() {
        // With the plus and mic gone the whole row fits from 300dp: this is the
        // owner's screenshot width, where it used to clip.
        for (w in listOf(300, 320, 360, 400)) {
            assertFalse("w=$w must not overflow", spec(w).hasOverflow)
            assertEquals(
                listOf(ToolbarItem.MODE, ToolbarItem.MODEL, ToolbarItem.TOOLS),
                spec(w).inline,
            )
        }
    }

    @Test
    fun theModeLabelAppearsAt360AndTheEffortSuffixAt480() {
        assertFalse(spec(320).modeShowsLabel)
        assertTrue(spec(360).modeShowsLabel)
        assertFalse("effort needs more room than 400dp", spec(400).modelShowsEffort)
        assertTrue(spec(480).modelShowsEffort)
    }

    @Test
    fun inlineItemsKeepTheVisualOrder() {
        val expected = listOf(ToolbarItem.MODE, ToolbarItem.MODEL, ToolbarItem.TOOLS)
        for (w in listOf(240, 280, 300, 360, 420, 480, 700)) {
            val s = spec(w)
            assertEquals("w=$w order", expected.filter { it in s.inline }, s.inline)
            assertEquals("w=$w overflow order", expected.filter { it in s.overflow }, s.overflow)
        }
    }

    @Test
    fun theKeyboardNeverMovesMoreIntoInlineThanWithoutIt() {
        for (w in 200..700 step 20) {
            assertTrue(
                "w=$w: keyboard must not widen the row",
                spec(w, ime = true).inline.size <= spec(w, ime = false).inline.size,
            )
        }
    }

    @Test
    fun everyTouchTargetIsAtLeast40dp() {
        // Deliberately lowered from 48dp (2026-10-29) at the owner's request to shorten the
        // composer: the target is still larger than the 28dp visual box, so the extra area
        // is finger, and 40dp clears the platform floor. This test exists so the number can
        // never drift DOWN unnoticed - if someone shrinks it again, it fails here first.
        for (w in listOf(0, 280, 360, 480, 2000)) {
            assertTrue("w=$w", spec(w).touchTargetDp >= 40)
        }
    }

    @Test
    fun theModelChipBudgetNeverGoesBelowItsFloor() {
        for (w in 140..900 step 10) {
            val s = spec(w)
            if (ToolbarItem.MODEL in s.inline) {
                assertTrue("w=$w chip max ${s.modelChipMaxDp}", s.modelChipMaxDp >= 96)
            }
        }
    }

    // The status row is gone (owner spec section 4 removed the visible approvals label),
    // so there is no "drop its text when narrow" rule to assert any more.

    @Test
    fun degenerateWidthsDoNotCrashAndKeepEverythingReachable() {
        for (w in listOf(-50, 0, 1)) {
            val s = spec(w)
            assertEquals(all, (s.inline + s.overflow).toSet())
        }
    }

    @Test
    fun dropdownOrderMatchesTheVideo() {
        assertEquals(listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT), ChatMode.dropdownOrder)
        assertEquals("Agent", ChatMode.AGENT.label)
    }
}
