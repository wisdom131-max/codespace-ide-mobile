package com.codespace.ide.chat.panel

import com.codespace.ide.chat.ChatTranscript
import com.codespace.ide.chat.StoredMessage
import com.codespace.ide.chat.panel.ToolResultPresentation.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-area integration: the transcript's own cap and the presentation layer must agree.
 *
 * These are two layers that were written separately - ChatTranscript decides what a stored message
 * may be, ToolResultPresentation decides how it reads. If the cap hides its own cut, a truncated
 * result is drawn as a complete one after a reopen, which is the exact lie Area 6 forbids.
 */
class ChatPresentationIntegrationTest {

    @Test
    fun aMessageCappedByTheTranscriptReadsAsPartialAfterwards() {
        val huge = (1..5000).joinToString(" ") { "word$it" }
        val capped = ChatTranscript.cap(listOf(StoredMessage("assistant", huge, true, 0L)))
        val text = capped.single().text
        assertTrue("the cap must shorten the text", text.length < huge.length)
        assertEquals(Kind.PARTIAL, ToolResultPresentation.present("read_file", true, text).kind)
    }

    @Test
    fun aCappedMessageStillKeepsItsHead() {
        val huge = "HEADLINE " + (1..5000).joinToString(" ") { "word$it" }
        val text = ChatTranscript.cap(listOf(StoredMessage("assistant", huge, true, 0L)))
            .single().text
        assertTrue(text.startsWith("HEADLINE"))
    }

    @Test
    fun aShortMessageIsNotBrandedPartialByTheCap() {
        val text = ChatTranscript.cap(listOf(StoredMessage("assistant", "val x = 1", true, 0L)))
            .single().text
        assertEquals(Kind.RESULT, ToolResultPresentation.present("read_file", true, text).kind)
    }

    @Test
    fun anInterruptedTurnIsNeverSummarisedAsDone() {
        // The transcript stores an incomplete reply with complete=false and the UI adds the
        // interrupted note; the header summary must not contradict either of them.
        val s = ToolResultPresentation.turnSummary(toolCount = 4, kind = Kind.RESULT, truncated = true)
        assertFalse(s, s.contains("Done"))
    }

    @Test
    fun aToolRowDefaultsToSuccessSoExistingProducersStayValid() {
        val row = com.codespace.ide.chat.ui.ChatRowModel.ToolActivity("read_file", "a\nb")
        assertTrue(row.ok)
    }

    @Test
    fun aFailedRowReachesTheRendererAsAnErrorWithItsReason() {
        val row = com.codespace.ide.chat.ui.ChatRowModel.ToolActivity("read_file", "ENOENT: gone", ok = false)
        val p = ToolResultPresentation.present(row.label, row.ok, row.detail)
        assertEquals(Kind.ERROR, p.kind)
        assertTrue(p.summary.contains("ENOENT"))
    }

    @Test
    fun aTurnThatFailedAToolIsNotSummarisedAsAPlainAnswer() {
        val s = ToolResultPresentation.turnSummary(toolCount = 1, kind = Kind.ERROR, truncated = false)
        assertTrue(s, s.contains("error"))
    }
}
