package com.codespace.ide.chat.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT SIX 6.2B — a continuation carries the generated summary, labelled as generated, and never
 * as something the user said.
 */
class Stage62HandoffSummaryTest {

    private fun turns() = listOf(
        SessionHandoff.Turn("user", "Decided: keep stage-5.3 unmerged."),
        SessionHandoff.Turn("assistant", "TODO: wire the producer call site"),
    )

    @Test
    fun `the generated summary travels in its own labelled section`() {
        val h = SessionHandoff.build(
            turns(), sessionNumber = 2, originLabel = "git",
            generatedSummary = "GENERATED SUMMARY of earlier turns (excerpts, not verbatim; the original session remains the record).\n- [FAILURE] write_file -> REFUSED: ask mode\n",
        )
        val text = h.render()
        assertTrue(text.contains("Generated summary of earlier turns (not verbatim"))
        assertTrue(text.contains("REFUSED"))
        assertTrue("the hop label must survive", text.contains("This is session 2"))
    }

    @Test
    fun `a session with nothing summarised renders no summary section`() {
        val text = SessionHandoff.build(turns(), sessionNumber = 2, originLabel = "git").render()
        assertFalse(text.contains("Generated summary of earlier turns"))
    }

    @Test
    fun `a credential inside a turn is still dropped when a summary is present`() {
        val h = SessionHandoff.build(
            turns() + SessionHandoff.Turn("user", "my token is ghp_012345678901234567890123456789012345"),
            generatedSummary = "GENERATED SUMMARY of earlier turns\n- [OTHER] something\n",
        )
        assertFalse(h.render().contains("ghp_"))
    }
}
