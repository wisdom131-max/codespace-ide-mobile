package com.codespace.ide.chat.ui

/**
 * PROJECT SEVEN 7.1D — when the list may follow new content, and when it must keep still.
 *
 * The rule exists because both extremes are wrong: not following leaves a reply growing off-screen, and
 * following always yanks the reader away from earlier content they deliberately scrolled back to. The
 * decision is one comparison, extracted so it can be tested rather than argued about.
 */
object ChatScrollFollow {

    /** How close to the end counts as "reading the live end": about one line. */
    const val LINE_DP = 96

    fun shouldFollow(value: Int, maxValue: Int, tolerance: Int = LINE_DP): Boolean {
        if (maxValue <= 0) return true // nothing to scroll: the next content will need following
        val distance = maxValue - value
        return distance <= tolerance
    }
}
