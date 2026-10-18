package com.codespace.ide.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BATCH8-P2 (advisor ruling, 2026-10-18): the per-mode tap-path contract.
 *
 * Owner device evidence: the soft keyboard never opened in OFF mode — even
 * without a tab switch — while FULL worked everywhere. Root cause (READ in
 * TerminalPane.onSingleTapConfirmed): BATCH7-P1's OFF gate did a bare `return`
 * BEFORE the default tap path (requestFocus + showSoftInput). Before BATCH7,
 * OFF fell through to the default tap: the regression was introduced by the
 * Off-gate move, not by lifecycle.
 *
 * The handler routes its early-exit decisions through LinkTapPolicy; these tests
 * pin the matrix so the regression cannot return silently. LIMITATION (honest):
 * the focus/keyboard call itself is a View interaction — JVM tests can pin the
 * POLICY, the owner's per-mode sheet test proves the keyboard on device.
 */
class LinkTapPolicyTest {

    private val allModes = LinkTapModeStore.Mode.values()

    @Test fun defaultTapPath_runsInEveryMode() {
        // THE regression: no mode may swallow requestFocus + showSoftInput.
        // BATCH7's OFF did exactly that on device.
        for (mode in allModes) {
            assertFalse("mode $mode must never swallow the default tap path", LinkTapPolicy.swallowsDefaultTap(mode))
        }
    }

    @Test fun off_skipsLinkHandling_allOthersDoNot() {
        assertTrue("OFF must skip link handling (no extraction, no probe)", LinkTapPolicy.skipsLinkHandling(LinkTapModeStore.Mode.OFF))
        assertFalse("LOG_TOKEN runs the pipeline (extract + log)", LinkTapPolicy.skipsLinkHandling(LinkTapModeStore.Mode.LOG_TOKEN))
        assertFalse("RESOLVE runs the pipeline (extract + probe + show)", LinkTapPolicy.skipsLinkHandling(LinkTapModeStore.Mode.RESOLVE))
        assertFalse("FULL runs the pipeline (extract + probe + open)", LinkTapPolicy.skipsLinkHandling(LinkTapModeStore.Mode.FULL))
    }

    @Test fun afterDispatch_onlyFullReturnsEarly() {
        // FULL: a resolved file is about to open and take focus.
        assertTrue(LinkTapPolicy.returnsEarlyAfterDispatch(LinkTapModeStore.Mode.FULL))
        // RESOLVE shows the result WITHOUT opening — the terminal keeps the keyboard.
        assertFalse("RESOLVE must fall through to the default tap", LinkTapPolicy.returnsEarlyAfterDispatch(LinkTapModeStore.Mode.RESOLVE))
        // LOG_TOKEN never dispatches, but the pin holds if it ever does.
        assertFalse(LinkTapPolicy.returnsEarlyAfterDispatch(LinkTapModeStore.Mode.LOG_TOKEN))
        assertFalse(LinkTapPolicy.returnsEarlyAfterDispatch(LinkTapModeStore.Mode.OFF))
    }

    @Test fun off_mode_matrix_complete() {
        // The complete OFF contract in one place: link work skipped, default tap kept.
        val off = LinkTapModeStore.Mode.OFF
        assertTrue(LinkTapPolicy.skipsLinkHandling(off))
        assertFalse(LinkTapPolicy.swallowsDefaultTap(off))
        assertFalse(LinkTapPolicy.returnsEarlyAfterDispatch(off))
    }
}
