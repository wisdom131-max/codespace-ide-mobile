package com.codespace.ide.chat

import com.codespace.ide.chat.ui.ChatRowModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * STAGE 9 (Checkpoint 0.5): a notice keeps its meaning across a save and reload.
 *
 * The category decides whether a line is allowed into the Thinking Tree, so losing it on reload
 * would quietly turn real work into chatter, or chatter into work. Older records had no category
 * at all and must read back as app notices, never as work steps.
 */
class TranscriptNoticeKindTest {

    private fun roundTrip(messages: List<StoredMessage>) =
        ChatTranscript.messagesToRows(ChatTranscript.decode(ChatTranscript.encode(messages)).messages)

    @Test
    fun `every category survives a save and reload`() {
        val saved = ChatRowModel.NoticeKind.entries.map { kind ->
            StoredMessage(role = "note", text = "t-" + kind.name, complete = true, atMs = 1L, noteKind = kind.name)
        }
        val notes = roundTrip(saved).filterIsInstance<ChatRowModel.InfoNote>()
        assertEquals(3, notes.size)
        assertEquals(ChatRowModel.NoticeKind.entries.toList(), notes.map { it.kind })
    }

    @Test
    fun `a work step stays a work step`() {
        val rows = roundTrip(
            listOf(
                StoredMessage(
                    role = "note", text = "Edited src/a.kt", complete = true, atMs = 1L,
                    noteKind = ChatRowModel.NoticeKind.WORK_STEP.name,
                ),
            ),
        )
        assertEquals(ChatRowModel.NoticeKind.WORK_STEP, rows.filterIsInstance<ChatRowModel.InfoNote>().single().kind)
    }

    @Test
    fun `a record with no category reads back as an app notice`() {
        val rows = roundTrip(listOf(StoredMessage(role = "note", text = "legacy", complete = true, atMs = 1L)))
        assertEquals(ChatRowModel.NoticeKind.APP_NOTICE, rows.filterIsInstance<ChatRowModel.InfoNote>().single().kind)
    }

    @Test
    fun `an unknown category never becomes a work step`() {
        val rows = roundTrip(
            listOf(StoredMessage(role = "note", text = "x", complete = true, atMs = 1L, noteKind = "NOT_A_KIND")),
        )
        assertEquals(ChatRowModel.NoticeKind.APP_NOTICE, rows.filterIsInstance<ChatRowModel.InfoNote>().single().kind)
    }

    @Test
    fun `a v3 line written before categories existed still reads`() {
        val elevenColumns = "m\tid1\tnote\t1\t0\t0\t-\told note\t-\t\t"
        val rows = ChatTranscript.messagesToRows(ChatTranscript.decode("v3\n" + elevenColumns).messages)
        assertEquals(ChatRowModel.NoticeKind.APP_NOTICE, rows.filterIsInstance<ChatRowModel.InfoNote>().single().kind)
    }
}
