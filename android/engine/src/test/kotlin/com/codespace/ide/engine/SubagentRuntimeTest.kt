package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentRuntimeTest {
    private fun ok(t: SubagentRuntime.Task) = SubagentRuntime.Result(t.id, "done", true)
    @Test fun runsDelegates() {
        val r = SubagentRuntime().run(listOf(SubagentRuntime.Task("a", "x")), 0) { ok(it) }
        assertEquals(1, r.size)
        assertTrue(r[0].ok)
    }
    @Test fun depthLimitRefuses() {
        val r = SubagentRuntime(maxDepth = 1).run(listOf(SubagentRuntime.Task("a", "x")), 1) { ok(it) }
        assertFalse(r[0].ok)
        assertTrue(r[0].error.contains("depth"))
    }
    @Test fun aThrowingDelegateIsContained() {
        val r = SubagentRuntime().run(listOf(SubagentRuntime.Task("a", "x")), 0) { error("boom") }
        assertFalse(r[0].ok)
    }
    @Test fun parallelCapApplies() {
        val tasks = (1..5).map { SubagentRuntime.Task("t$it", "p") }
        assertEquals(3, SubagentRuntime().run(tasks, 0) { ok(it) }.size)
    }
}
