package com.codespace.ide.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stage 4.6: git and archive results are read, not believed. */
class Stage46VerifierTest {

    private val porcelain = """
        ## main...origin/main
        M  src/Main.kt
         M src/Other.kt
        ?? notes/todo.md
    """.trimIndent()

    @Test
    fun porcelainStatusIsParsedIntoRealFacts() {
        val state = GitStateVerifier.parseStatus(porcelain)!!
        assertEquals("main", state.branch)
        assertEquals(listOf("src/Main.kt"), state.staged)
        assertEquals(listOf("src/Other.kt"), state.unstaged)
        assertEquals(listOf("notes/todo.md"), state.untracked)
        assertFalse(state.clean)
    }

    @Test
    fun aCleanTreeIsRecognised() {
        val clean = GitStateVerifier.parseStatus("## main...origin/main")!!
        assertTrue(clean.clean)
        assertEquals(GitStateVerifier.Result.VERIFIED, GitStateVerifier.verifyClean("## main").result)
    }

    @Test
    fun aCommitIsVerifiedByItsPresenceInTheLog() {
        val log = "a1b2c3d feat: add the verifier\nb2c3d4e fix: earlier"
        assertTrue(GitStateVerifier.verifyCommit(log, "feat: add the verifier").ok)
        val missing = GitStateVerifier.verifyCommit(log, "feat: something else")
        assertEquals(GitStateVerifier.Result.REFUTED, missing.result)
    }

    @Test
    fun anUnexpectedHeadIsRefuted() {
        val log = "a1b2c3d feat: x"
        val outcome = GitStateVerifier.verifyCommit(log, null, expectedHead = "9999999")
        assertEquals(GitStateVerifier.Result.REFUTED, outcome.result)
        assertTrue(outcome.note.contains("9999999"))
    }

    @Test
    fun aMissingLogCannotVerifyACommit() {
        assertEquals(GitStateVerifier.Result.NOT_CHECKABLE, GitStateVerifier.verifyCommit("").result)
        assertEquals(GitStateVerifier.Result.NOT_CHECKABLE,
            GitStateVerifier.verifyCommit("fatal: not a git repository").result)
    }

    @Test
    fun theRightBranchIsConfirmedAndTheWrongOneIsNot() {
        assertTrue(GitStateVerifier.verifyCheckout(porcelain, "main").ok)
        val wrong = GitStateVerifier.verifyCheckout(porcelain, "release")
        assertEquals(GitStateVerifier.Result.REFUTED, wrong.result)
    }

    @Test
    fun stagingIsCheckedForTheIntendedPathsAndUnexpectedOnes() {
        val outcome = GitStateVerifier.verifyStaging(porcelain, listOf("src/Main.kt"))
        assertEquals(GitStateVerifier.Result.VERIFIED, outcome.result)
        val missing = GitStateVerifier.verifyStaging(porcelain, listOf("src/Missing.kt"))
        assertEquals(GitStateVerifier.Result.REFUTED, missing.result)
    }

    @Test
    fun anInterruptedMergeIsNotTreatedAsFinished() {
        val merging = "## main\nUnmerged paths:\nUU src/Main.kt"
        val state = GitStateVerifier.parseStatus(merging)!!
        assertTrue(state.conflicted.isNotEmpty())
        assertEquals(GitStateVerifier.Result.REFUTED, GitStateVerifier.verifySettled(merging).result)
    }

    @Test
    fun aRebaseInProgressIsInconclusiveRatherThanFailed() {
        val rebasing = "## HEAD (no branch)\nYou are currently rebasing branch 'main' on 'abc123'."
        assertEquals(GitStateVerifier.Result.INCONCLUSIVE, GitStateVerifier.verifySettled(rebasing).result)
    }

    @Test
    fun anArchiveListingIsCountedAndDescribed() {
        val listing = "src/Main.kt 120\nsrc/Util.kt 340\nREADME.md 40"
        val outcome = ArchiveVerifier.verifyListing(listing)
        assertEquals(ArchiveVerifier.Result.VERIFIED, outcome.result)
        assertTrue(outcome.note.contains("3 file(s)"))
    }

    @Test
    fun aCorruptArchiveIsRefutedRatherThanEmpty() {
        val outcome = ArchiveVerifier.verifyListing("Error: the file is not a zip archive")
        assertEquals(ArchiveVerifier.Result.REFUTED, outcome.result)
    }

    @Test
    fun anUnsupportedFormatIsNotAFailure() {
        val outcome = ArchiveVerifier.verifyListing("unsupported archive format")
        assertEquals(ArchiveVerifier.Result.NOT_CHECKABLE, outcome.result)
    }

    @Test
    fun unsafePathsAreCaught() {
        val outcome = ArchiveVerifier.verifyListing("../../etc/passwd 12")
        assertEquals(ArchiveVerifier.Result.REFUTED, outcome.result)
    }

    @Test
    fun extractionCompletenessIsComparedWithTheListing() {
        val listing = ArchiveVerifier.parseListing("a.txt 10\nb.txt 20")
        assertNotNull(listing)
        assertTrue(ArchiveVerifier.verifyExtraction(listing, listOf("a.txt", "b.txt")).ok)
        val partial = ArchiveVerifier.verifyExtraction(listing, listOf("a.txt"))
        assertEquals(ArchiveVerifier.Result.INCONCLUSIVE, partial.result)
        assertTrue(partial.note.contains("partial extraction"))
        assertEquals(ArchiveVerifier.Result.REFUTED,
            ArchiveVerifier.verifyExtraction(listing, emptyList()).result)
    }

    @Test
    fun theDispatcherRoutesGitAndArchiveWithoutRunningAnything() {
        val status = OutcomeVerifier.verify("git_status", porcelain, JSONObject(), null)
        assertEquals(OutcomeVerifier.Result.VERIFIED, status.result)
        val commit = OutcomeVerifier.verify(
            "git_commit_push",
            "a1b2c3d feat: the thing",
            JSONObject().put("message", "feat: the thing"),
            null,
        )
        assertEquals(OutcomeVerifier.Result.VERIFIED, commit.result)
        val zip = OutcomeVerifier.verify("zip_list", "a.txt 10", JSONObject(), null)
        assertEquals(OutcomeVerifier.Result.VERIFIED, zip.result)
        val nothing = OutcomeVerifier.verify("grep_search", "hit", JSONObject(), null)
        assertEquals(OutcomeVerifier.Result.NOT_CHECKABLE, nothing.result)
    }

    @Test
    fun aThrowingVerifierSaysNothingRatherThanRefusingTheResult() {
        val outcome = OutcomeVerifier.verify("git_add", "x", JSONObject().put("paths", 5), null)
        assertEquals(OutcomeVerifier.Result.NOT_CHECKABLE, outcome.result)
    }

    @Test
    fun aBadGitAnswerDoesNotBecomeAGoodOneThroughTheDispatcher() {
        val outcome = OutcomeVerifier.verify("git_status", "Unmerged paths:\nUU a.kt", JSONObject(), null)
        assertEquals(OutcomeVerifier.Result.REFUTED, outcome.result)
        assertNull(null)
    }
}
