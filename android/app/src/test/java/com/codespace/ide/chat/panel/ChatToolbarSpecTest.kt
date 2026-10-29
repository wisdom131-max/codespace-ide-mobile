package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U3 toolbar collapse rules at the widths the owner named, with and without the keyboard. */
class ChatToolbarSpecTest {

    /** Every toolbar item, read from the enum: adding one updates the invariants. */
    private val all = ToolbarItem.values().toSet()

    private fun spec(w: Int, ime: Boolean = false) = ChatToolbarLayout.forWidth(w, ime)

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
    fun theMicIsAlwaysReachableSomewhere() {
        for (w in -50..2000 step 10) {
            for (ime in listOf(false, true)) {
                val x = spec(w, ime)
                assertTrue(
                    "w=$w ime=$ime: dictation must be inline or in overflow, never dropped",
                    ToolbarItem.MIC in x.inline || ToolbarItem.MIC in x.overflow,
                )
            }
        }
    }

    @Test
    fun theModelChipIsAlwaysInlineAtEveryWidth() {
        // The point of the row. A toolbar that hides the model while keeping a lesser
        // control is the wrong trade, so it is inline from 0dp up.
        for (w in -50..2000 step 10) {
            for (ime in listOf(false, true)) {
                assertTrue("w=$w ime=$ime", ToolbarItem.MODEL in spec(w, ime).inline)
            }
        }
    }

    @Test
    fun at280TheToolbarCollapsesIntoAnOverflowInsteadOfClipping() {
        val s = spec(280)
        assertTrue("280dp must use an overflow menu", s.hasOverflow)
        assertTrue("model stays visible at 280dp", ToolbarItem.MODEL in s.inline)
        // With the send cluster, chevron and mic reserved, 280dp fits model + add.
        assertEquals(listOf(ToolbarItem.ADD_CONTEXT, ToolbarItem.MODEL), s.inline)
        assertEquals(
            listOf(ToolbarItem.MODE, ToolbarItem.MIC, ToolbarItem.TOOLS),
            s.overflow,
        )
        assertFalse("no effort suffix at 280dp, it would ellipsize", s.modelShowsEffort)
        assertFalse("icon-only mode at 280dp", s.modeShowsLabel)
    }

    @Test
    fun at360EverythingStillFitsWithNoOverflowOrItDegradesGracefully() {
        val s = spec(360)
        assertTrue("model inline at 360", ToolbarItem.MODEL in s.inline)
        assertTrue("add-context inline at 360", ToolbarItem.ADD_CONTEXT in s.inline)
        // Whatever did not fit is in overflow, never silently absent.
        assertEquals(all, (s.inline + s.overflow).toSet())
    }

    @Test
    fun at480TheFullVsCodeRowFitsWithLabelsAndEffort() {
        val s = spec(480)
        assertFalse("no overflow at 480dp", s.hasOverflow)
        assertEquals(
            listOf(
                ToolbarItem.ADD_CONTEXT, ToolbarItem.MODE, ToolbarItem.MODEL,
                ToolbarItem.MIC, ToolbarItem.TOOLS,
            ),
            s.inline,
        )
        // The mic and the send cluster cost width, so "model . effort" moves out to 560dp.
        assertTrue("mode label fits at 480dp", s.modeShowsLabel)
        assertFalse("effort suffix needs 560dp with a mic present", s.modelShowsEffort)
        assertTrue("effort appears once the row is wide", spec(560).modelShowsEffort)
        assertTrue(s.statusShowsText)
    }

    @Test
    fun inlineItemsKeepTheVsCodeVisualOrder() {
        val expected = listOf(
            ToolbarItem.ADD_CONTEXT, ToolbarItem.MODE, ToolbarItem.MODEL,
            ToolbarItem.MIC, ToolbarItem.TOOLS,
        )
        for (w in listOf(300, 360, 420, 480, 700)) {
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
    fun everyTouchTargetIsAtLeast48dp() {
        for (w in listOf(0, 280, 360, 480, 2000)) {
            assertTrue("w=$w", spec(w).touchTargetDp >= 48)
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

    @Test
    fun statusRowDropsItsTextBeforeItClips() {
        assertFalse(spec(280).statusShowsText)
        assertTrue(spec(360).statusShowsText)
    }

    @Test
    fun degenerateWidthsDoNotCrashAndKeepEverythingReachable() {
        for (w in listOf(-50, 0, 1)) {
            val s = spec(w)
            assertEquals(all, (s.inline + s.overflow).toSet())
        }
    }

    @Test
    fun dropdownOrderMatchesTheVideo() {
        // The agent-mode video shows Ask, Edit, Agent top to bottom.
        assertEquals(listOf(ChatMode.ASK, ChatMode.EDIT, ChatMode.AGENT), ChatMode.dropdownOrder)
        assertEquals("Agent", ChatMode.AGENT.label)
    }
}
