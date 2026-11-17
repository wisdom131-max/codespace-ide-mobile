package com.codespace.ide.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PROJECT SEVEN 7.1D — follow the live end, never fight the reader. */
class Stage71ScrollFollowTest {

    @Test
    fun `a reader at the very bottom is followed`() {
        assertTrue(ChatScrollFollow.shouldFollow(value = 1_000, maxValue = 1_000))
    }

    @Test
    fun `a reader within one line of the bottom is still followed`() {
        assertTrue(ChatScrollFollow.shouldFollow(value = 950, maxValue = 1_000))
        assertTrue(ChatScrollFollow.shouldFollow(value = 904, maxValue = 1_000))
    }

    @Test
    fun `a reader who scrolled back is left alone`() {
        assertFalse(ChatScrollFollow.shouldFollow(value = 400, maxValue = 1_000))
        assertFalse(ChatScrollFollow.shouldFollow(value = 0, maxValue = 5_000))
    }

    @Test
    fun `an unscrollable list follows, so the first content is not stranded`() {
        assertTrue(ChatScrollFollow.shouldFollow(value = 0, maxValue = 0))
    }

    @Test
    fun `the tolerance is about one line, not half the screen`() {
        assertTrue(ChatScrollFollow.LINE_DP in 40..120)
    }
}
