package com.codespace.ide.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputLimitsTest {

    @Test
    fun ordinaryTextIsSendable() {
        assertTrue(InputLimits.check("fix the gradle error").ok)
    }

    @Test
    fun blankIsRefused() {
        assertFalse(InputLimits.check("   ").ok)
    }

    @Test
    fun oversizeIsRefusedWithTheLimit() {
        val v = InputLimits.check("x".repeat(50), maxChars = 10)
        assertFalse(v.ok)
        assertTrue(v.reason.contains("10"))
    }

    @Test
    fun binaryIsRefused() {
        assertFalse(InputLimits.check("a\u0000b").ok)
    }

    @Test
    fun tabsAndNewlinesAreFine() {
        assertTrue(InputLimits.check("line one\n\tline two").ok)
    }

    @Test
    fun otherControlCharactersAreRefused() {
        assertFalse(InputLimits.check("bell\u0007here").ok)
    }
}
