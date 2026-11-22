package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DebateConsensusTest {

    @Test
    fun theMajorityWins() {
        val o = DebateConsensus.decide(listOf("use proot", "use proot", "use a microVM"))!!
        assertEquals("use proot", o.answer)
        assertEquals(2, o.votes)
        assertEquals(3, o.of)
    }

    @Test
    fun caseAndSpacingAreNotDisagreement() {
        val o = DebateConsensus.decide(listOf("Use  Proot", "use proot"))!!
        assertTrue(o.unanimous)
    }

    @Test
    fun aTieGoesToTheEarliest() {
        val o = DebateConsensus.decide(listOf("first", "second"))!!
        assertEquals("first", o.answer)
        assertEquals(1, o.votes)
    }

    @Test
    fun nothingUsableIsNull() {
        assertNull(DebateConsensus.decide(listOf("", "   ")))
    }
}
