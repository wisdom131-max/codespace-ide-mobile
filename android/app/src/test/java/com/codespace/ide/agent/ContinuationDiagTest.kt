package com.codespace.ide.agent

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ContinuationDiagTest {

    @Before fun setUp() { CopilotDiag.detach(); CopilotDiag.clear() }
    @After fun tearDown() { CopilotDiag.detach(); CopilotDiag.clear() }

    @Test fun `completion is recorded as completed`() {
        ContinuationDiag.completed("STOP")
        assertEquals(CopilotDiag.Kind.CONTINUATION_COMPLETED, CopilotDiag.recent().single().kind)
        assertTrue(CopilotDiag.recent().single().ok)
    }

    @Test fun `a length-limited finish is still a completion with its reason kept`() {
        ContinuationDiag.completed("LENGTH")
        val e = CopilotDiag.recent().single()
        assertEquals(CopilotDiag.Kind.CONTINUATION_COMPLETED, e.kind)
        assertEquals("LENGTH", e.detail)
    }

    @Test fun `failure is not recorded as completed`() {
        ContinuationDiag.failed("IOException")
        val kinds = CopilotDiag.recent().map { it.kind }
        assertEquals(listOf(CopilotDiag.Kind.CONTINUATION_FAILED), kinds)
        assertTrue(CopilotDiag.recent().none { it.ok })
    }

    @Test fun `failure and cancellation are distinguishable`() {
        ContinuationDiag.failed("IOException")
        ContinuationDiag.cancelled()
        val kinds = CopilotDiag.recent().map { it.kind }
        assertEquals(
            listOf(CopilotDiag.Kind.CONTINUATION_FAILED, CopilotDiag.Kind.CONTINUATION_CANCELLED),
            kinds,
        )
    }

    @Test fun `an ordinary turn emits no continuation event`() {
        ContinuationDiag.none()
        assertTrue(CopilotDiag.recent().isEmpty())
    }

    @Test fun `each terminal call emits exactly one event`() {
        ContinuationDiag.completed("STOP")
        assertEquals(1, CopilotDiag.recent().count { it.kind == CopilotDiag.Kind.CONTINUATION_COMPLETED })
    }

    @Test fun `a failed continuation cannot also appear as completed`() {
        ContinuationDiag.failed("TransportHttpException")
        assertTrue(CopilotDiag.recent().none { it.kind == CopilotDiag.Kind.CONTINUATION_COMPLETED })
    }
}
