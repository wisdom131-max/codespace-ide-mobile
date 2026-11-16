package com.codespace.ide.chat.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SIX 6.4 — the plus button must not mint the same successor twice. */
class Stage63HandoffGuardTest {

    private fun decide(children: List<Pair<String, String>>, rows: Map<String, Int>) =
        HandoffGuard.decide("parent", children, { rows[it] ?: 0 }, { "Session 2 from git" })

    @Test
    fun `an empty existing successor is reused instead of duplicated`() {
        val d = decide(listOf("child" to "parent"), mapOf("child" to 0))
        assertTrue(d is HandoffGuard.Decision.Reuse)
        assertEquals("child", (d as HandoffGuard.Decision.Reuse).sessionId)
        assertEquals("Session 2 from git", d.label)
    }

    @Test
    fun `a successor that already holds a turn does not swallow a new branch`() {
        assertEquals(HandoffGuard.Decision.Create, decide(listOf("child" to "parent"), mapOf("child" to 3)))
    }

    @Test
    fun `with no successor yet a new one is created`() {
        assertEquals(HandoffGuard.Decision.Create, decide(emptyList(), emptyMap()))
        assertEquals(HandoffGuard.Decision.Create, decide(listOf("" to "parent"), emptyMap()))
    }

    @Test
    fun `a missing child session counts as empty and is reused, not crashed on`() {
        val d = decide(listOf("gone" to "parent"), emptyMap())
        assertTrue(d is HandoffGuard.Decision.Reuse)
    }
}
