package com.codespace.ide.scm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-05: the reply-to-panel channel must put a MESSAGE in the box, not a paragraph. */
class CommitMessageReplyTest {

    @Test
    fun aFencedMessageWinsOverTheProseAroundIt() {
        val raw = """
            Sure! Here is a commit message:

            ```
            fix(scm): commit only staged files

            - scope the commit to the staged set
            - keep untracked files out of history
            ```

            Let me know if you want it shorter.
        """.trimIndent()
        val out = CommitMessageReply.extract(raw)
        assertEquals(
            "fix(scm): commit only staged files\n\n- scope the commit to the staged set\n" +
                "- keep untracked files out of history",
            out,
        )
        assertFalse("no fence survives", out.contains("```"))
        assertFalse("no preamble survives", out.contains("Sure!"))
    }

    @Test
    fun anUnfencedMessageDropsTheCommentaryLine() {
        val raw = "Here's a commit message:\nfeat(chat): retry keeps the record single\n\n- one user row"
        val out = CommitMessageReply.extract(raw)
        assertTrue(out.startsWith("feat(chat):"))
        assertTrue(out.contains("- one user row"))
    }

    @Test
    fun aPlainSubjectIsKeptExactly() {
        val out = CommitMessageReply.extract("  chore: bump engine pin  ")
        assertEquals("chore: bump engine pin", out)
    }

    @Test
    fun anEmptyAnswerYieldsAnEmptyBoxRatherThanNoise() {
        assertEquals("", CommitMessageReply.extract(""))
        assertEquals("", CommitMessageReply.extract("   \n  \n"))
    }

    @Test
    fun theMessageIsCappedSoTheBoxCannotBeFlooded() {
        val long = buildString {
            append("feat: many lines\n")
            repeat(20) { append("line ").append(it).append("\n") }
        }
        assertTrue(CommitMessageReply.extract(long).lines().size <= 8)
    }
}
