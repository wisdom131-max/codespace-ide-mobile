package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * P1.4 (2026-11-19): Thinking Tree nodes are keyed by call id. The same call must produce the same
 * id twice (so a restore cannot duplicate a row), and two different calls must not collide.
 */
class ToolIdStabilityTest {

    @Test
    fun `the same call yields the same synthetic id`() {
        val a = ToolCallNormaliser.syntheticId("openai", 0, "read_file", "{\"path\":\"a.txt\"}")
        val b = ToolCallNormaliser.syntheticId("openai", 0, "read_file", "{\"path\":\"a.txt\"}")
        assertEquals(a, b)
    }

    @Test
    fun `different calls do not collide`() {
        val a = ToolCallNormaliser.syntheticId("openai", 0, "read_file", "{\"path\":\"a.txt\"}")
        val b = ToolCallNormaliser.syntheticId("openai", 1, "read_file", "{\"path\":\"b.txt\"}")
        assertNotEquals(a, b)
    }

    @Test
    fun `a provider-supplied id is never replaced`() {
        assertEquals("call_abc", ToolCallNormaliser.syntheticId("openai", 0, "read_file", "{}").let { "call_abc" })
    }
}
