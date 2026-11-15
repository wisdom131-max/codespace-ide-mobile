package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 2b: the rule that decides whether an interrupted turn offers Resume.
 *
 * The cases that matter are the ones that must NOT offer it: a finished reply, and a
 * conversation with no assistant message at all.
 */
class TurnResumeTest {

    @Test
    fun anUnfinishedAssistantMessageOffersResume() {
        assertEquals(TurnResume.Action.OFFER_RESUME, TurnResume.afterRestore(lastAssistantComplete = false))
    }

    @Test
    fun aFinishedAssistantMessageOffersNothing() {
        assertEquals(TurnResume.Action.NONE, TurnResume.afterRestore(lastAssistantComplete = true))
    }

    @Test
    fun aConversationWithNoAssistantMessageOffersNothing() {
        assertEquals(TurnResume.Action.NONE, TurnResume.afterRestore(lastAssistantComplete = null))
    }

    @Test
    fun resumeIsRefusedWhileATurnIsAlreadyRunning() {
        assertFalse(TurnResume.canResume(TurnResume.Action.OFFER_RESUME, streaming = true))
    }

    @Test
    fun resumeIsAllowedWhenNothingIsRunning() {
        assertTrue(TurnResume.canResume(TurnResume.Action.OFFER_RESUME, streaming = false))
    }

    @Test
    fun nothingIsResumableWithoutAnOffer() {
        assertFalse(TurnResume.canResume(TurnResume.Action.NONE, streaming = false))
    }

    @Test
    fun theBarSaysNothingWasResent() {
        assertTrue(TurnResume.TITLE.contains("Nothing has been resent"))
        assertEquals("Resume", TurnResume.BUTTON)
    }
}
