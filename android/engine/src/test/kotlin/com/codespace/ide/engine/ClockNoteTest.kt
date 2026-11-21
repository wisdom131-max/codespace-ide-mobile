package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * PROJECT EIGHT 8.3 — the agent's clock. A fixed instant and a fixed zone make this reproducible,
 * which is the whole reason [ClockNote.now] accepts both.
 */
class ClockNoteTest {

    // 2026-11-21T20:40:00Z
    private val instant = 1_795_293_600_000L

    @Test
    fun `names the day date and time in the given zone`() {
        val line = ClockNote.now(instant, TimeZone.getTimeZone("Africa/Lagos"), Locale.US)
        assertTrue(line, line.startsWith("Current local time: "))
        assertTrue("weekday", line.contains("Saturday"))
        assertTrue("day and month", line.contains("21 November 2026"))
        assertTrue("24h time", line.contains("21:40"))
    }

    @Test
    fun `states the zone and its offset so a model can reason about it`() {
        val line = ClockNote.now(instant, TimeZone.getTimeZone("Africa/Lagos"), Locale.US)
        assertTrue("offset in force at that instant", line.contains("UTC+01:00"))
    }

    @Test
    fun `the same instant reads differently in another zone`() {
        val lagos = ClockNote.now(instant, TimeZone.getTimeZone("Africa/Lagos"), Locale.US)
        val utc = ClockNote.now(instant, TimeZone.getTimeZone("UTC"), Locale.US)
        assertTrue(lagos.contains("21:40"))
        assertTrue(utc.contains("20:40"))
        assertFalse(lagos == utc)
    }

    @Test
    fun `a negative offset is labelled with a minus sign`() {
        val line = ClockNote.now(instant, TimeZone.getTimeZone("America/New_York"), Locale.US)
        assertTrue(line, line.contains("UTC-05:00"))
        assertTrue("15:40 in New York in November", line.contains("15:40"))
    }

    @Test
    fun `the line is a single line so it cannot break the prompt layout`() {
        val line = ClockNote.now(instant, TimeZone.getTimeZone("Africa/Lagos"), Locale.US)
        assertFalse("no newlines", line.contains("\n"))
        assertEquals(1, line.lines().size)
    }
}
