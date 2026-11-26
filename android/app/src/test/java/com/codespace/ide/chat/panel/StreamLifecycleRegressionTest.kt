package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.engine.ChatTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * STAGE 4 and STAGE 5 (Checkpoint 0.5) - the two scenarios the owner named that had no test:
 * a LONG response, and the requirement that a long response arrives whole, in order, once.
 *
 * The published text is throttled (50 ms or 512 new characters), so the risk a long answer carries
 * is not speed but LOSS: a delta that only updates the buffer and never reaches the row would leave
 * the user with a truncated answer that still looks finished. These tests assert the whole text,
 * character for character, and that exactly one assistant row exists to hold it.
 */
class StreamLifecycleRegressionTest {

    private class ChunkTransport(private val chunks: List<String>) : ChatTransport {
        var payloads = ArrayList<String>()
        override fun stream(url: String, headers: Map<String, String>, payloadJson: String): Flow<String> {
            payloads.add(payloadJson)
            return flowOf(*chunks.toTypedArray())
        }
    }

    private fun sse(text: String) =
        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}\n\n"

    private val finish = "data: {\"candidates\":[{\"finishReason\":\"STOP\"}]}\n\n"

    private fun session(transport: ChatTransport) = ChatSession(
        "proj",
        { if (it == com.codespace.ide.chat.keys.SlotNaming.primarySlot("gemini")) "k-test" else null },
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        { transport },
    )

    private fun awaitIdle(s: ChatSession) = runBlocking {
        var spins = 0
        while (s.ui.value.streaming && spins < 400) {
            delay(10)
            spins++
        }
        assertFalse("the turn must settle, not stream for ever", s.ui.value.streaming)
    }

    @Test
    fun `a long response arrives whole, in order, and in one row`() {
        // 400 deltas of a few characters each: well past the 512-character publication threshold
        // several times over, which is where a lost or reordered delta would show up.
        val words = (1..400).map { "w" + it }
        val transport = ChunkTransport(words.map { sse(it) } + listOf(finish))
        val s = session(transport)

        s.send("go")
        awaitIdle(s)

        val answers = s.ui.value.rows.filterIsInstance<ChatRowModel.AssistantStream>()
        assertEquals("one logical reply, one row", 1, answers.size)
        assertEquals("every delta, in the order sent", words.joinToString(""), answers.single().text)
        assertFalse("the cursor stops when the answer does", answers.single().cursorOn)
    }

    @Test
    fun `a long response is not duplicated into a second row when it completes`() {
        val words = (1..250).map { "x" + it }
        val transport = ChunkTransport(words.map { sse(it) } + listOf(finish))
        val s = session(transport)

        s.send("go")
        awaitIdle(s)
        // A second terminal event must not append another copy of the same answer.
        s.send("go")
        awaitIdle(s)

        val answers = s.ui.value.rows.filterIsInstance<ChatRowModel.AssistantStream>()
        assertEquals("two sends, two rows - never a split reply", 2, answers.size)
        assertEquals(words.joinToString(""), answers.first().text)
        assertEquals(words.joinToString(""), answers.last().text)
    }
}
