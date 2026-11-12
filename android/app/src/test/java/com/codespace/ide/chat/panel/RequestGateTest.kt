package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Area 5: stream ownership, as a rule with no Android in it. */
class RequestGateTest {

    @Test
    fun theRunningRequestOwnsItsStream() {
        val g = RequestGate()
        val gen = g.bump()
        assertTrue(g.owns(gen))
        assertFalse(g.stale(gen))
    }

    @Test
    fun stopInvalidatesTheRunningStream() {
        val g = RequestGate()
        val gen = g.bump()
        g.bump() // cancel() bumps
        assertFalse("bytes already in flight must be dropped", g.owns(gen))
    }

    @Test
    fun aNewSendTakesOwnershipFromTheOldOne() {
        val g = RequestGate()
        val first = g.bump()
        val second = g.bump()
        assertFalse(g.owns(first))
        assertTrue(g.owns(second))
    }

    @Test
    fun anArmedRetryLosesToANewerTurn() {
        val g = RequestGate()
        val armed = g.bump()
        g.bump()
        assertFalse("a timer from a minute ago must not append", g.owns(armed))
    }

    @Test
    fun anUnnumberedEventNeverOwnsTheStream() {
        assertFalse(RequestGate().owns(0))
    }

    @Test
    fun ownershipIsPerSessionObject() {
        val a = RequestGate()
        val b = RequestGate()
        val genA = a.bump()
        b.bump()
        assertTrue("a switch must not stop the session now on screen", a.owns(genA))
    }
}
