package com.codespace.ide.agent.docs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 2.11: the plan for taking frames, before a decoder is touched.
 *
 * Two failures this pins: every frame landing at 0ms (the same black first frame), and a request for
 * an unreasonable number of frames quietly filling the phone.
 */
class FramePlanTest {

    @Test
    fun framesAreSpreadAcrossTheWholeVideo() {
        val plan = FramePlan.timestamps(60_000, 3)
        assertEquals(3, plan.size)
        assertEquals(listOf(15_000L, 30_000L, 45_000L), plan)
    }

    @Test
    fun noFrameIsTakenAtZero() {
        assertTrue(FramePlan.timestamps(10_000, 4).none { it == 0L })
    }

    @Test
    fun theVeryEndIsAvoidedBecauseItOftenDoesNotDecode() {
        val plan = FramePlan.timestamps(60_000, 3)
        assertTrue("last plan entry must stay inside the video", plan.last() < 60_000)
    }

    @Test
    fun aTinyVideoYieldsOneUsefulInstantNotDuplicates() {
        val plan = FramePlan.timestamps(150, 5)
        assertEquals(1, plan.size)
        assertEquals(75L, plan.single())
    }

    @Test
    fun theCountIsBounded() {
        assertEquals(60, FramePlan.clampCount(500))
        assertEquals(1, FramePlan.clampCount(0))
        assertEquals(60, FramePlan.timestamps(600_000, 500).size)
    }

    @Test
    fun nonsenseDurationsPlanNothing() {
        assertTrue(FramePlan.timestamps(0, 3).isEmpty())
        assertTrue(FramePlan.timestamps(-5, 3).isEmpty())
        assertTrue(FramePlan.timestamps(1000, 0).isEmpty())
    }

    @Test
    fun frameNamesSortAndNeverCollideWithTheVideo() {
        assertEquals("clip-frame01.jpg", FramePlan.frameName("clip.mp4", 0, "jpg"))
        assertEquals("clip-frame10.jpg", FramePlan.frameName("clip.mp4", 9, "jpg"))
        assertEquals("frame-frame01.png", FramePlan.frameName("", 0, "png"))
    }
}
