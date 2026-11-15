package com.codespace.ide.agent.research

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 1.10: progress says what is happening and then gets out of the way.
 *
 * The failure this guards is a stale line: an answer arrives while the panel still claims
 * "Searching 42s", which reads as a hang that never ended.
 */
class ResearchProgressTest {

    @After
    fun clear() = ResearchProgress.finish()

    @Test
    fun nothingIsPublishedBeforeARoundStarts() {
        assertNull(ResearchProgress.snapshot())
    }

    @Test
    fun aManyPartRoundSaysWhereItIs() {
        val s = ResearchProgress.begin("Searching", 3)
        assertEquals(3, s.of)
        assertEquals(1, s.part)
        ResearchProgress.step(2, "Searching part 2")
        val now = ResearchProgress.snapshot()
        assertNotNull(now)
        assertEquals(2, now!!.part)
        assertEquals("Searching part 2 (2 of 3)", now.line(now.startedAtMs).substringBeforeLast(" "))
    }

    @Test
    fun aSingleSearchDoesNotPretendToHaveParts() {
        val s = ResearchProgress.begin("Searching", 1)
        assertTrue(s.line(s.startedAtMs).startsWith("Searching 0s"))
    }

    @Test
    fun finishingClearsTheLine() {
        ResearchProgress.begin("Searching", 2)
        assertNotNull(ResearchProgress.snapshot())
        ResearchProgress.finish()
        assertNull(ResearchProgress.snapshot())
    }

    @Test
    fun aStepWithoutARoundIsIgnoredRatherThanInvented() {
        ResearchProgress.finish()
        ResearchProgress.step(4, "Searching part 4")
        assertNull(ResearchProgress.snapshot())
    }
}
