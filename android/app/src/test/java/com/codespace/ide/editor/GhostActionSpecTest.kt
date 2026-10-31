package com.codespace.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accept exactly one line, and hide without losing the next suggestion. */
class GhostActionSpecTest {

    @Test
    fun `accepting a line keeps the rest of the suggestion`() {
        val result = GhostActionSpec.acceptLine(listOf("first", "second", "third"))
        assertEquals("first", result.text)
        assertEquals(listOf("second", "third"), result.remaining)
    }

    @Test
    fun `accepting the last line leaves nothing behind`() {
        val result = GhostActionSpec.acceptLine(listOf("only"))
        assertEquals("only", result.text)
        assertTrue(result.remaining.isEmpty())
    }

    @Test
    fun `there is nothing to accept on an empty suggestion`() {
        assertEquals("", GhostActionSpec.acceptLine(emptyList()).text)
        assertFalse(GhostActionSpec.canAcceptLine(listOf("   ")))
    }

    @Test
    fun `snooze expires`() {
        assertTrue(GhostActionSpec.snoozeActive(1_000L, 999L))
        assertFalse(GhostActionSpec.snoozeActive(1_000L, 1_000L))
        assertTrue(GhostActionSpec.SNOOZE_MS >= 10_000L)
    }

    @Test
    fun `a new suggestion is not silenced by an old snooze`() {
        assertNotEquals(
            GhostActionSpec.snoozeKey("console.log(x)"),
            GhostActionSpec.snoozeKey("console.log(y)"),
        )
    }
}
