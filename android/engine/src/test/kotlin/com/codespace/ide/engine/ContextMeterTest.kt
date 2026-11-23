package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class ContextMeterTest {

    @Test
    fun aSmallCountIsShownPlainly() {
        assertEquals("940", ContextMeter.format(940))
    }

    @Test
    fun aLargeCountIsShownInThousands() {
        assertEquals("133.6K", ContextMeter.format(133_600))
        assertEquals("200K", ContextMeter.format(200_000))
    }

    @Test
    fun thePercentageIsTheShareOfTheWindow() {
        val r = ContextMeter.measure(
            ContextMeter.Segments(messageChars = 240_000, systemChars = 8_000),
            windowTokens = 200_000,
        )
        assertEquals(31, r.percent)
        assertEquals("62K", r.usedLabel)
    }

    @Test
    fun reservedSpaceCountsAsUsedBecauseItIsNotAvailable() {
        val r = ContextMeter.measure(ContextMeter.Segments(reservedChars = 4_000), windowTokens = 1000)
        assertEquals(1000, r.reservedTokens)
        assertEquals(100, r.percent)
    }

    @Test
    fun anEmptyConversationIsZeroNotAnError() {
        val r = ContextMeter.measure(ContextMeter.Segments(), windowTokens = 0)
        assertEquals(0, r.percent)
        assertEquals(0, r.usedTokens)
    }
}
