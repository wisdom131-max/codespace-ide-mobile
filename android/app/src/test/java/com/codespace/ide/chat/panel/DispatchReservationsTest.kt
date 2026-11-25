package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** STAGE 6 (Checkpoint 0.5): a tool call is claimed before it runs, once per execution. */
class DispatchReservationsTest {

    @Test
    fun `the first dispatch of a call is allowed and the second is refused`() {
        assertTrue(DispatchReservations.reserve("exec-1", "call-a"))
        assertFalse(DispatchReservations.reserve("exec-1", "call-a"))
    }

    @Test
    fun `the same call id in a different execution is a different call`() {
        assertTrue(DispatchReservations.reserve("exec-1", "call-a"))
        // A second turn may legitimately ask for the same edit; it must not be refused, and it
        // must not inherit the first turn's claim.
        assertTrue(DispatchReservations.reserve("exec-2", "call-a"))
    }

    @Test
    fun `a released claim may be taken again`() {
        assertTrue(DispatchReservations.reserve("exec-1", "call-b"))
        DispatchReservations.release("exec-1", "call-b")
        assertTrue(DispatchReservations.reserve("exec-1", "call-b"))
    }

    @Test
    fun `a blank identity never blocks a dispatch`() {
        assertTrue(DispatchReservations.reserve("", "call-c"))
        assertTrue(DispatchReservations.reserve("", "call-c"))
        assertTrue(DispatchReservations.reserve("exec-3", ""))
    }

    @Test
    fun `a claim is visible while it is held`() {
        assertTrue(DispatchReservations.reserve("exec-4", "call-d"))
        assertTrue(DispatchReservations.isClaimed("exec-4", "call-d"))
        assertFalse(DispatchReservations.isClaimed("exec-4", "call-e"))
    }
}
