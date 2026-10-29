package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch 3 — the pure half of transcript auto-scroll.
 *
 * These assertions are the difference between "keeps the answer visible" and "yanks the user
 * back to the bottom while they read": the follow flag may only be changed by USER movement,
 * and the scroll itself only happens when following AND there is somewhere to go.
 */
class TranscriptAutoScrollTest {

    @Test
    fun aUserAtTheBottomKeepsFollowing() {
        assertEquals(true, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 0))
    }

    @Test
    fun aUserWithinTheSlopIsStillFollowing() {
        // A finger is not pixel-exact: a few px from the end must not stop the follow.
        assertEquals(true, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 20))
        assertEquals(true, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 24))
    }

    @Test
    fun aUserWhoScrollsUpStopsFollowing() {
        assertEquals(false, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 25))
        assertEquals(false, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 4000))
    }

    @Test
    fun aFlingIsStillUserMovementSoItIsHonoured() {
        // isScrollInProgress stays true through a fling, so the check keeps applying while
        // the list coasts; a fling that ends mid-transcript still means "do not chase me".
        assertEquals(false, AutoScrollPolicy.followAfterUserScroll(moving = true, remainingPx = 900))
    }

    @Test
    fun ourOwnScrollNeverChangesTheFollowFlag() {
        // This is the guard against the feedback loop: the auto-scroll itself is not a
        // user scroll, so it must not be able to switch following off (or on).
        assertNull(AutoScrollPolicy.followAfterUserScroll(moving = false, remainingPx = 0))
        assertNull(AutoScrollPolicy.followAfterUserScroll(moving = false, remainingPx = 5000))
    }

    @Test
    fun theViewOnlyMovesWhenFollowingAndThereIsRoomToMove() {
        assertTrue(AutoScrollPolicy.shouldAutoScroll(following = true, remainingPx = 10))
        assertFalse("never move a user who scrolled up", AutoScrollPolicy.shouldAutoScroll(following = false, remainingPx = 10))
        assertFalse("nothing to do at the bottom", AutoScrollPolicy.shouldAutoScroll(following = true, remainingPx = 0))
        assertFalse("short transcripts do not scroll", AutoScrollPolicy.shouldAutoScroll(following = true, remainingPx = -30))
    }

    @Test
    fun theGrowthKeyChangesOnNewContentAndNotOtherwise() {
        val a = ChatRowModel.User("hi")
        val stream1 = ChatRowModel.AssistantStream("Hel", cursorOn = true)
        val stream2 = ChatRowModel.AssistantStream("Hello", cursorOn = true)

        val oneStream = listOf(a, stream1)
        val oneStreamMore = listOf(a, stream2)
        assertTrue(
            "async growth must fire the effect",
            transcriptGrowthKey(oneStream) != transcriptGrowthKey(oneStreamMore)
        )

        val twoRows = listOf(a, stream1, ChatRowModel.InfoNote("note"))
        assertTrue(
            "a new message must fire the effect",
            transcriptGrowthKey(oneStream) != transcriptGrowthKey(twoRows)
        )

        // Same content re-composed (e.g. an unrelated tick) must NOT fire.
        assertEquals(transcriptGrowthKey(twoRows), transcriptGrowthKey(listOf(a, stream1, ChatRowModel.InfoNote("note"))))
    }

    @Test
    fun everyGrowthKeyIsDistinctForAdjacentTranscripts() {
        // Guards the arithmetic: size and last-text length must not collide for the common
        // "one more row, three fewer chars" case.
        val base = listOf<ChatRowModel>(ChatRowModel.User("a"))
        val grown = base + ChatRowModel.AssistantStream("xy", cursorOn = true)
        assertTrue(transcriptGrowthKey(base) != transcriptGrowthKey(grown))

        val emptyStream = listOf(ChatRowModel.User("a"), ChatRowModel.AssistantStream("", cursorOn = true))
        assertTrue(transcriptGrowthKey(base) != transcriptGrowthKey(emptyStream))
    }
}
