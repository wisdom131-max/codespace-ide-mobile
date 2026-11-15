package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * PHASE 8b: the chevron entries act as themselves.
 *
 * The bug this pins: every enabled menu item called stop, so "Stop and Send" cancelled the reply
 * and silently dropped the typed text. Queue and steer are unbuilt, and an unbuilt entry that
 * cancels the answer when tapped is worse than no entry at all.
 */
class SendMenuDispatchTest {

    @Test
    fun stopAndSendIsItsOwnAction() {
        assertEquals(SendAction.STOP_AND_SEND, ChatSendState.actionOf(SendMenuEntry.STOP_AND_SEND))
        assertNotEquals(SendAction.STOP, ChatSendState.actionOf(SendMenuEntry.STOP_AND_SEND))
    }

    @Test
    fun theUnbuiltEntriesDoNothingRatherThanStopping() {
        assertEquals(SendAction.NONE, ChatSendState.actionOf(SendMenuEntry.ADD_TO_QUEUE))
        assertEquals(SendAction.NONE, ChatSendState.actionOf(SendMenuEntry.STEER))
    }

    @Test
    fun whileStreamingWithTextTheChevronIsOfferedAndStopAndSendIsEnabled() {
        val spec = ChatSendState.resolve(streaming = true, needsSetup = false, modelBlank = false, hasText = true)
        assertEquals(SendAction.STOP, spec.tapAction)
        assertEquals(true, spec.showsChevron)
        assertEquals(true, spec.menu.first().enabled)
    }

    @Test
    fun whileStreamingWithNoTextStopAndSendIsDisabledWithAReason() {
        val spec = ChatSendState.resolve(streaming = true, needsSetup = false, modelBlank = false, hasText = false)
        val first = spec.menu.first()
        assertEquals(false, first.enabled)
        assertEquals("Type a message first", first.reason)
    }

    @Test
    fun anEmptyIdleFieldCannotSend() {
        val spec = ChatSendState.resolve(streaming = false, needsSetup = false, modelBlank = false, hasText = false)
        assertEquals(SendAction.NONE, spec.tapAction)
        assertEquals(true, spec.dimmed)
    }
}
