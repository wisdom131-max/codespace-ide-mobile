package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadowDecisionTest {
    @Test fun boundsAreExact() {
        assertFalse(ShadowDecision.inCanary("k", 0))
        assertTrue(ShadowDecision.inCanary("k", 100))
    }
    @Test fun isStableForSameKey() {
        val a = ShadowDecision.inCanary("session-42", 50)
        val b = ShadowDecision.inCanary("session-42", 50)
        assertTrue(a == b)
    }
}
