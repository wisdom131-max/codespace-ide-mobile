package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 4.7 workstream D: the identity rules added to the existing [ReplyOwnership].
 *
 * Coverage honesty: these assert the RULES, which is what ChatSession asks. They do not prove the
 * wiring inside ChatSession, and nothing here is device-verified.
 */
class Stage47ReplyIdentityTest {

    @Test
    fun oneExecutionDeliversOnce() {
        assertTrue(ReplyOwnership.mayDeliver("exec-1", emptySet()))
        assertFalse(ReplyOwnership.mayDeliver("exec-1", setOf("exec-1")))
    }

    @Test
    fun aRepeatedSendWithTheSameIdentityIsNotASecondDelivery() {
        val delivered = mutableSetOf<String>()
        assertTrue(ReplyOwnership.mayDeliver("exec-2", delivered))
        delivered.add("exec-2")
        assertFalse(ReplyOwnership.mayDeliver("exec-2", delivered))
    }

    @Test
    fun aBlankIdentityNeverMatches() {
        assertFalse("no identity means no delivery decision", ReplyOwnership.mayDeliver("", setOf("")))
        assertFalse(ReplyOwnership.mayDispatchTool(" ", setOf("")))
    }

    @Test
    fun aCompletedToolCallIsNotDispatchedTwice() {
        assertTrue(ReplyOwnership.mayDispatchTool("call_a", emptySet()))
        assertFalse(ReplyOwnership.mayDispatchTool("call_a", setOf("call_a")))
        assertTrue("a different call is a different action", ReplyOwnership.mayDispatchTool("call_b", setOf("call_a")))
    }

    @Test
    fun aLateReplyForAnotherSessionIsNotAppendable() {
        assertTrue(ReplyOwnership.ownsSession("s1", "s1"))
        assertFalse(ReplyOwnership.ownsSession("s1", "s2"))
        assertFalse(ReplyOwnership.ownsSession(null, "s2"))
        assertFalse(ReplyOwnership.ownsSession("s1", null))
    }

    @Test
    fun aRestoredTranscriptIsNotRestoredAgain() {
        assertTrue(ReplyOwnership.mayRestoreReply("exec-9", null))
        assertTrue(ReplyOwnership.mayRestoreReply("exec-9", "exec-8"))
        assertFalse(ReplyOwnership.mayRestoreReply("exec-9", "exec-9"))
    }

    @Test
    fun legitimateSeparateSubmissionsAreUnaffected() {
        assertEquals(ReplyOwnership.Start.FRESH, ReplyOwnership.startAction(false, false, 0))
        assertEquals(ReplyOwnership.Start.REPLACE_RUNNING, ReplyOwnership.startAction(true, false, 10))
        assertEquals(
            "two identical questions a minute apart are two questions",
            ReplyOwnership.Start.REPLACE_RUNNING,
            ReplyOwnership.startAction(true, true, 60_000),
        )
        assertEquals(
            "a double tap on Send is not a second request",
            ReplyOwnership.Start.IGNORE_DUPLICATE_TAP,
            ReplyOwnership.startAction(true, true, 200),
        )
    }

    @Test
    fun aCompletedGenerationStillCannotBeCompletedTwice() {
        assertTrue(ReplyOwnership.mayComplete(gen = 5, lastCompletedGen = 4, owns = true))
        assertFalse(ReplyOwnership.mayComplete(gen = 5, lastCompletedGen = 5, owns = true))
        assertFalse("a superseded request may not complete", ReplyOwnership.mayComplete(3, 2, owns = false))
    }
}
