package com.codespace.ide.chat

import com.codespace.ide.chat.panel.AutoScrollPolicy
import com.codespace.ide.chat.panel.JumpButtonPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner item 4 - the chat scroll-to-bottom control must not sit there permanently.
 *
 * VS Code reference: chatListWidget.ts:137 computeScrollDownState -> showButton = !isScrolledToBottom
 * (position-driven, hidden at the end). The owner's addition is a ~3s linger after a tap or after
 * reaching the bottom, so the control does not blink out from under the finger.
 */
class JumpButtonVisibilityTest {

    private val slop = AutoScrollPolicy.SLOP_PX

    @Test
    fun awayFromTheBottomIsAlwaysVisible() {
        assertTrue(JumpButtonPolicy.visible(remainingPx = 900, atBottomSinceMs = null, nowMs = 0, wasAway = true))
    }

    @Test
    fun aFreshTranscriptAtTheBottomNeverFlashesTheControl() {
        // Nothing has scrolled away from the bottom yet: there is nowhere to jump TO.
        assertFalse(JumpButtonPolicy.visible(remainingPx = 0, atBottomSinceMs = null, nowMs = 5_000, wasAway = false))
    }

    @Test
    fun itLingersForThreeSecondsAfterArrivingAtTheBottomThenHides() {
        val arrived = 10_000L
        assertTrue(JumpButtonPolicy.visible(0, arrived, arrived, wasAway = true))
        assertTrue(JumpButtonPolicy.visible(0, arrived, arrived + 2_999, wasAway = true))
        assertFalse("three seconds is the limit", JumpButtonPolicy.visible(0, arrived, arrived + 3_000, wasAway = true))
        assertFalse(JumpButtonPolicy.visible(0, arrived, arrived + 60_000, wasAway = true))
    }

    @Test
    fun withinTouchSlopCountsAsTheBottom() {
        // A finger is not pixel-exact; the same slop the auto-scroll policy uses applies here.
        assertTrue(JumpButtonPolicy.visible(slop, atBottomSinceMs = 0, nowMs = 10, wasAway = true))
        assertFalse(JumpButtonPolicy.visible(slop, atBottomSinceMs = 0, nowMs = 10_000, wasAway = true))
        assertTrue(JumpButtonPolicy.visible(slop + 1, atBottomSinceMs = null, nowMs = 10_000, wasAway = true))
    }

    @Test
    fun scrollingAwayAgainBringsItStraightBack() {
        // Arrive (linger running), then leave: away wins regardless of the linger clock.
        val arrived = 1_000L
        assertFalse(JumpButtonPolicy.visible(0, arrived, arrived + 5_000, wasAway = true))
        assertTrue(JumpButtonPolicy.visible(400, null, arrived + 5_000, wasAway = true))
    }
}
