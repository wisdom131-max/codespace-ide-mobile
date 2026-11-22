package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RouterGateTest {

    @Test
    fun offMeansUnchanged() {
        assertEquals("medium", RouterGate.apply("medium", "high", true, false))
    }

    @Test
    fun anUnsupportedModelIsUnchanged() {
        assertEquals("medium", RouterGate.apply("medium", "high", false, true))
    }

    @Test
    fun onAndSupportedAppliesTheSuggestion() {
        assertEquals("high", RouterGate.apply("medium", "high", true, true))
    }

    @Test
    fun aBlankSuggestionIsUnchanged() {
        assertEquals("medium", RouterGate.apply("medium", " ", true, true))
    }
}
