package com.codespace.ide.chat

import com.codespace.ide.engine.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MEMORY CONTINUITY — what crosses from the transcript into the model's context.
 *
 * The rule that matters: the user's own words and the assistant's answers are replayed;
 * this UI's annotations are not. Replaying a note would make the model answer text the user
 * never typed, and dropping a real turn is the amnesia the whole change exists to fix.
 */
class ChatSeedTest {

    private fun m(role: String, text: String, complete: Boolean = true) =
        StoredMessage(role, text, complete, 0L)

    @Test
    fun `dialogue is replayed in order with its roles`() {
        val seed = ChatSeed.dialogue(
            listOf(
                m("user", "we chose clip rendering"),
                m("assistant", "understood"),
                m("user", "and the second thing?"),
            ),
        )
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            seed.map { it.role },
        )
        assertEquals("we chose clip rendering", seed.first().text)
        assertEquals("and the second thing?", seed.last().text)
    }

    @Test
    fun `ui notes are not replayed`() {
        val seed = ChatSeed.dialogue(
            listOf(
                m("user", "hello"),
                m("note", "Attached: notes.txt"),
                m("note", "Could not read the saved conversation"),
                m("assistant", "hi"),
            ),
        )
        assertEquals(listOf("hello", "hi"), seed.map { it.text })
    }

    @Test
    fun `duplicate ids cannot collide across a restore`() {
        val seed = ChatSeed.dialogue(listOf(m("user", "a"), m("assistant", "b"), m("user", "c")))
        assertEquals(3, seed.map { it.id }.toSet().size)
    }

    @Test
    fun `blank and unknown rows are dropped rather than sent as empty turns`() {
        val seed = ChatSeed.dialogue(
            listOf(m("user", "   "), m("system", "internal"), m("user", "real question")),
        )
        assertEquals(listOf("real question"), seed.map { it.text })
    }

    @Test
    fun `an interrupted reply is still replayed so the model sees what the user saw`() {
        val seed = ChatSeed.dialogue(listOf(m("user", "explain"), m("assistant", "half an ans", complete = false)))
        assertEquals(2, seed.size)
        assertTrue(seed.last().text.startsWith("half an ans"))
    }

    @Test
    fun `an empty transcript yields no seed at all`() {
        assertEquals(0, ChatSeed.dialogue(emptyList()).size)
    }
}
