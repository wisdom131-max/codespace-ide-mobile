package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** U3 send control: states, the reference menu order, and engine honesty. */
class ChatSendStateTest {

    private fun s(streaming: Boolean = false, setup: Boolean = false, blank: Boolean = false, text: Boolean = false) =
        ChatSendState.resolve(streaming, setup, blank, text)

    @Test
    fun idleEmptyIsDimmedAndDoesNothing() {
        val c = s()
        assertEquals(SendMode.IDLE_EMPTY, c.mode)
        assertEquals(SendAction.NONE, c.tapAction)
        assertTrue(c.dimmed)
        assertFalse(c.showsChevron)
    }

    @Test
    fun readyWithTextSends() {
        val c = s(text = true)
        assertEquals(SendAction.SEND, c.tapAction)
        assertFalse(c.dimmed)
        assertFalse(c.showsStop)
    }

    @Test
    fun streamingBecomesStopSquareWithAChevron() {
        val c = s(streaming = true)
        assertTrue(c.showsStop)
        assertTrue(c.showsChevron)
        assertEquals(SendAction.STOP, c.tapAction)
        assertEquals("Stop", c.label)
    }

    @Test
    fun theStreamingMenuMatchesTheReferenceOrderExactly() {
        val labels = s(streaming = true, text = true).menu.map { it.entry.label }
        assertEquals(listOf("Stop and Send", "Add to Queue", "Steer with Message"), labels)
    }

    @Test
    fun stopAndSendNeedsTextAndExplainsWhyNot() {
        val empty = s(streaming = true).menu.first()
        assertFalse(empty.enabled)
        assertEquals("Type a message first", empty.reason)
        val typed = s(streaming = true, text = true).menu.first()
        assertTrue(typed.enabled)
        assertNull(typed.reason)
    }

    @Test
    fun queueAndSteerAreEnabledOnlyWhenThereIsText() {
        // PHASE 2/3 (2026-11-17): the engines now exist, so the honest state changed from
        // "disabled until built" to "enabled when there is something to queue or steer". No text
        // still refuses with a reason - an enabled entry that does nothing is the original bug.
        val menu = s(streaming = true, text = true).menu
        val queue = menu.first { it.entry == SendMenuEntry.ADD_TO_QUEUE }
        val steer = menu.first { it.entry == SendMenuEntry.STEER }
        for (item in listOf(queue, steer)) {
            assertTrue("${item.entry} must be usable while streaming with text", item.enabled)
            assertNull(item.reason)
        }
        assertEquals("Alt+Enter", queue.shortcutHint)
        assertEquals("Enter", steer.shortcutHint)

        val empty = s(streaming = true, text = false).menu
        for (item in empty.filter { it.entry != SendMenuEntry.STOP_AND_SEND }) {
            assertFalse("${item.entry} needs text", item.enabled)
            assertEquals("Type a message first", item.reason)
        }
    }

    @Test
    fun setupAndPickModelTakePriorityOverEverythingElse() {
        assertEquals(SendAction.OPEN_SETUP, s(setup = true, text = true, streaming = true).tapAction)
        assertEquals(SendAction.OPEN_MODEL_PICKER, s(blank = true, text = true).tapAction)
        assertTrue(s(setup = true).menu.isEmpty())
    }

    @Test
    fun nonStreamingStatesNeverShowAMenu() {
        for (state in listOf(s(), s(text = true), s(setup = true), s(blank = true))) {
            assertTrue(state.menu.isEmpty())
            assertFalse(state.showsChevron)
        }
    }
}
