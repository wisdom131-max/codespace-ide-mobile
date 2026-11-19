package com.codespace.ide.chat.panel

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.6 (2026-11-19): a successor session must never look informed when nothing was carried. The
 * threshold is a guard, not a summary generator: it refuses the empty and the trivial.
 */
class HandoffSummaryGuardTest {

    private fun usable(s: String) = s.isNotBlank() && s.trim().length >= 40

    @Test
    fun `an empty summary is refused`() {
        org.junit.Assert.assertFalse(usable(""))
        org.junit.Assert.assertFalse(usable("   \n "))
    }

    @Test
    fun `a trivial summary is refused`() {
        org.junit.Assert.assertFalse(usable("ok"))
        org.junit.Assert.assertFalse(usable("continued"))
    }

    @Test
    fun `a real summary passes`() {
        assertTrue(usable("Task: fix the Cloudflare 400. Decisions: flatten content parts. Constraint: main only."))
    }
}
