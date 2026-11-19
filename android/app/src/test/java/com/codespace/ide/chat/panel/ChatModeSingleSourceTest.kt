package com.codespace.ide.chat.panel

import com.codespace.ide.chat.session.SessionMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P0.2 (2026-11-19): mode has ONE authoritative read path. These pin the mapping the enforcement
 * gate now uses, so "stored says Agent, turn runs Ask" cannot pass unnoticed.
 */
class ChatModeSingleSourceTest {

    @Test
    fun `every stored mode maps to the same ui mode it was saved from`() {
        for (m in ChatMode.entries) {
            assertEquals(m, ChatModePersistence.toUi(ChatModePersistence.toStored(m)))
        }
    }

    @Test
    fun `stored agent is enforced as agent, not as ask`() {
        assertEquals(ChatMode.AGENT, ChatModePersistence.toUi(SessionMode.AGENT))
        assertEquals(ChatMode.EDIT, ChatModePersistence.toUi(SessionMode.EDIT))
        assertEquals(ChatMode.ASK, ChatModePersistence.toUi(SessionMode.ASK))
    }

    @Test
    fun `mode labels stay the three approved modes`() {
        assertEquals(setOf("Ask", "Edit", "Agent"), ChatMode.entries.map { it.label }.toSet())
    }
}
