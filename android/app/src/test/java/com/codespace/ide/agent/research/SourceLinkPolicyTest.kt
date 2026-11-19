package com.codespace.ide.agent.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1.5 (2026-11-19): a citation is only clickable when tapping it can open a real destination.
 * These pin the gate in front of the Android intent, not the intent itself (device pending).
 */
class SourceLinkPolicyTest {

    @Test
    fun `an ordinary https source is openable`() {
        assertTrue(ResearchSources.isOpenable("https://developer.android.com/guide"))
    }

    @Test
    fun `a bare or malformed source is not openable`() {
        assertFalse(ResearchSources.isOpenable("not a url"))
        assertFalse(ResearchSources.isOpenable(""))
        assertFalse(ResearchSources.isOpenable("https://"))
    }

    @Test
    fun `a script url is never openable`() {
        assertFalse(ResearchSources.isOpenable("javascript:alert(1)"))
    }

    @Test
    fun `control characters cannot smuggle a url past the gate`() {
        assertFalse(ResearchSources.isOpenable("https://example.com/\u0000x"))
    }
}
