package com.codespace.ide.chat

import com.codespace.ide.chat.ui.ChatRowModel
import com.codespace.ide.chat.ui.NativeChatListRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expanded Thinking disclosure shows the assistant's actions, never its reasoning.
 *
 * Steps are the InfoNote lines the engine already emits for tool use ("read_file: 42 lines").
 * If that collection ever drifts - wrong window, wrong row type - the expanded card silently
 * empties and the user is left with a chevron that opens nothing.
 */
class ThinkingStepsTest {

    @Test
    fun stepsAreTheConsecutiveNotesThatFollowTheThinkingRow() {
        val rows = listOf(
            ChatRowModel.Thinking(seconds = 3),
            ChatRowModel.InfoNote("read_file: 42 lines"),
            ChatRowModel.InfoNote("write_file: src/Main.kt"),
            ChatRowModel.AssistantStream("done", cursorOn = false),
            ChatRowModel.InfoNote("not part of the step list"),
        )
        val steps = NativeChatListRenderer.stepsAfter(rows, 0)
        assertEquals(listOf("read_file: 42 lines", "write_file: src/Main.kt"), steps)
    }

    @Test
    fun aRowThatIsNotThinkingHasNoSteps() {
        val rows = listOf(ChatRowModel.InfoNote("orphan"), ChatRowModel.InfoNote("after"))
        assertTrue(NativeChatListRenderer.stepsAfter(rows, 0).isEmpty())
    }

    @Test
    fun aThinkingRowWithNoFollowingNotesReportsNone() {
        val rows = listOf(ChatRowModel.Thinking(seconds = 1))
        assertTrue(NativeChatListRenderer.stepsAfter(rows, 0).isEmpty())
    }
}
