package com.codespace.ide.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The repeated action detector: stops a loop, never stops ordinary work. */
class StuckDetectorTest {

    @Before fun clear() = StuckDetector.reset()

    @Test
    fun `the third identical call is refused with advice`() {
        assertNull(StuckDetector.check("read_file", "a.kt"))
        assertNull(StuckDetector.check("read_file", "a.kt"))
        val stop = StuckDetector.check("read_file", "a.kt")
        assertTrue("third identical call must stop", stop != null && stop.contains("STOP"))
        assertTrue("and say what to do instead", stop!!.contains("change the ARGUMENT"))
    }

    @Test
    fun `reading different files is never a loop`() {
        assertNull(StuckDetector.check("read_file", "a.kt"))
        assertNull(StuckDetector.check("read_file", "b.kt"))
        assertNull(StuckDetector.check("read_file", "c.kt"))
        assertNull(StuckDetector.check("read_file", "d.kt"))
    }

    @Test
    fun `a different tool on the same target is not a loop`() {
        assertNull(StuckDetector.check("read_file", "a.kt"))
        assertNull(StuckDetector.check("grep_search", "a.kt"))
        assertNull(StuckDetector.check("read_file", "a.kt"))
    }

    @Test
    fun `one stop does not block the next attempt`() {
        repeat(3) { StuckDetector.check("run_command", "gradle test") }
        assertNull("history clears when it fires", StuckDetector.check("run_command", "gradle test"))
    }
}
