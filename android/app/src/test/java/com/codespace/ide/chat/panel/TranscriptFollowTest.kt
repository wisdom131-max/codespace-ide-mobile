package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 3 — auto-scroll decisions.
 *
 * The two failure modes this pins down: a transcript that ignores new content while the user
 * is reading at the bottom, and a transcript that drags them away from what they are reading.
 * Both are pure decisions, so they are tested without a device or a frame clock.
 */
class TranscriptFollowTest {

    @Test
    fun aScrollThatEndsAtTheBottomResumesFollowing() {
        assertEquals(true, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 0))
        assertEquals(true, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 24))
    }

    @Test
    fun aScrollThatEndsAwayFromTheBottomStopsFollowing() {
        assertEquals(false, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 25))
        assertEquals(false, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 2400))
    }

    @Test
    fun aProgrammaticScrollNeverChangesTheFlag() {
        assertNull(AutoScrollPolicy.followAfterUserScroll(moving = false, remainingPx = 0))
        assertNull(AutoScrollPolicy.followAfterUserScroll(moving = false, remainingPx = 5000))
    }

    @Test
    fun contentOnlyFollowsWhileFollowingAndWithSomewhereToGo() {
        assertTrue(AutoScrollPolicy.shouldAutoScroll(following = true, remainingPx = 120))
        assertFalse(AutoScrollPolicy.shouldAutoScroll(following = false, remainingPx = 120))
        assertFalse(AutoScrollPolicy.shouldAutoScroll(following = true, remainingPx = 0))
    }

    @Test
    fun theFollowFlagStartsPinnedAndReportsTheWayBack() {
        val flag = FollowFlag()
        assertTrue("a fresh transcript follows the newest message", flag.following)
        flag.recordUserFollow(false)
        assertFalse(flag.following)
        flag.recordUserFollow(true)
        assertTrue(flag.following)
    }
}
