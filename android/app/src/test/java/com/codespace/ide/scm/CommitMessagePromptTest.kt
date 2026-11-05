package com.codespace.ide.scm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-05: the Source Control AI button's prompt, asserted rather than discovered. */
class CommitMessagePromptTest {

    @Test
    fun thePromptNamesTheChangesAndAsksForTheSubjectLine() {
        val p = CommitMessagePrompt.build(listOf("app/src/Main.kt", "README.md"), emptyList())
        assertTrue(p.contains("app/src/Main.kt"))
        assertTrue(p.contains("staged"))
        assertTrue("asks for a conventional subject", p.contains("conventional commit"))
        assertTrue("bounds the subject length", p.contains("72"))
        assertTrue("forbids edits", p.contains("Do not change any files"))
    }

    @Test
    fun anEmptyChangeSetStillAsksSomethingAnswerable() {
        val p = CommitMessagePrompt.build(emptyList(), emptyList())
        assertTrue(p.contains("no changes"))
        assertFalse("never sends an empty prompt", p.isBlank())
    }

    @Test
    fun theListIsBoundedSoThePromptCannotExplode() {
        val many = (1..40).map { "f" + it + ".kt" }
        val p = CommitMessagePrompt.build(emptyList(), many)
        assertTrue(p.contains("f20.kt"))
        assertFalse("items past the cap are counted, not listed", p.contains("f21.kt"))
        assertTrue(p.contains("and 20 more"))
    }

    @Test
    fun unstagedChangesSayUnstaged() {
        val p = CommitMessagePrompt.build(emptyList(), listOf("a.kt"))
        assertTrue(p.contains("unstaged"))
    }
}
