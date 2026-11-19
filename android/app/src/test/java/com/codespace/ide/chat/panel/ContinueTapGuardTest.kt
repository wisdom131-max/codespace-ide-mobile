package com.codespace.ide.chat.panel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** P1.2: a second Continue tap while one is in flight must not start a second continuation. */
class ContinueTapGuardTest {

    @Test
    fun `an idle composer may continue`() {
        assertTrue(Continuation.mayStart(streaming = false, inFlight = false))
    }

    @Test
    fun `a streaming turn may not`() {
        assertFalse(Continuation.mayStart(streaming = true, inFlight = false))
    }

    @Test
    fun `an in-flight continuation may not be started twice`() {
        assertFalse(Continuation.mayStart(streaming = false, inFlight = true))
        assertFalse(Continuation.mayStart(streaming = true, inFlight = true))
    }
}
