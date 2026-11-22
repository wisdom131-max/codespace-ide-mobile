package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackTallyTest {

    @Test
    fun votesAreCounted() {
        val c = FeedbackTally.Counter()
        c.positive()
        c.negative()
        c.negative()
        val t = c.tally()
        assertEquals(1, t.up)
        assertEquals(2, t.down)
        assertEquals(-1, t.net)
    }

    @Test
    fun reasonsAreTally() {
        val c = FeedbackTally.Counter()
        c.negative("Too slow")
        c.negative("too slow")
        assertEquals(2, c.tally().reasons["too slow"])
    }

    @Test
    fun aBlankReasonIsIgnored() {
        val c = FeedbackTally.Counter()
        c.negative("   ")
        assertEquals(0, c.tally().reasons.size)
        assertEquals(1, c.tally().down)
    }
}
