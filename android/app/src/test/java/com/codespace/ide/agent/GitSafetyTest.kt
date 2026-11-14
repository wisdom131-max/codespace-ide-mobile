package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHASE 1 regression tests (owner approval 2026-11-14).
 *
 * These pin the two properties the shipped defect violated: a single approval can never stage the
 * whole tree, and a push to a protected branch can never ride on a commit approval.
 */
class GitSafetyTest {

    @Test
    fun theWholeTreeCanNeverBeStaged() {
        for (wildcard in listOf("-A", "-a", "--all", ".", "./", "*", "**", "src/*", ":/")) {
            val plan = GitSafety.planStaging(listOf(wildcard), listOf("a.kt"))
            assertFalse("'" + wildcard + "' must be refused", plan.ok)
            assertTrue("'" + wildcard + "' must be named in the problem", plan.problems.any { it.contains(wildcard.removePrefix("-")) || it.contains(wildcard) })
        }
    }

    @Test
    fun namedPathsAreStagedExactly() {
        val plan = GitSafety.planStaging(listOf("src/A.kt", "./src/B.kt", "src/A.kt"), emptyList())
        assertTrue(plan.ok)
        assertEquals(listOf("src/A.kt", "src/B.kt"), plan.paths)
    }

    @Test
    fun absoluteAndTraversalPathsAreRefused() {
        assertFalse(GitSafety.planStaging(listOf("/root/project/x.kt"), emptyList()).ok)
        assertFalse(GitSafety.planStaging(listOf("../outside.kt"), emptyList()).ok)
        assertFalse(GitSafety.planStaging(listOf("src/../../escape.kt"), emptyList()).ok)
    }

    @Test
    fun legacyCallWithoutPathsOnlyTakesTrackedChanges() {
        val porcelain = " M src/A.kt\nM  src/B.kt\n?? new-untracked.kt\nR  old.kt -> src/C.kt\n"
        val tracked = GitSafety.trackedModifiedFromStatus(porcelain)
        assertEquals(listOf("src/A.kt", "src/B.kt", "src/C.kt"), tracked)
        val plan = GitSafety.planStaging(emptyList(), tracked)
        assertTrue(plan.ok)
        assertFalse("an untracked file must not be swept in", plan.paths.contains("new-untracked.kt"))
    }

    @Test
    fun legacyCallRefusesCredentialFiles() {
        val plan = GitSafety.planStaging(emptyList(), listOf("app/.env", "src/A.kt"))
        assertTrue("the ordinary file still goes", plan.paths.contains("src/A.kt"))
        assertFalse("the credential file does not", plan.paths.contains("app/.env"))
        assertTrue(plan.problems.any { it.contains(".env") })
    }

    @Test
    fun nothingToCommitIsSaidPlainly() {
        val plan = GitSafety.planStaging(emptyList(), emptyList())
        assertFalse(plan.ok)
        assertTrue(plan.problems.first().startsWith("Nothing to commit"))
    }

    @Test
    fun mainAndMasterAreProtected() {
        assertTrue(GitSafety.isProtectedBranch("main"))
        assertTrue(GitSafety.isProtectedBranch("refs/heads/main"))
        assertTrue(GitSafety.isProtectedBranch(" Master "))
        assertFalse(GitSafety.isProtectedBranch("feature/git-safety"))
        assertFalse(GitSafety.isProtectedBranch("mainline"))
    }

    @Test
    fun pushTargetIsReadFromGitNotInvented() {
        val target = GitSafety.pushTarget("origin\thttps://github.com/o/r.git (fetch)\norigin\thttps://github.com/o/r.git (push)", "main")
        assertEquals("origin", target?.remote)
        assertEquals("main", target?.branch)
        assertEquals("origin/main", target?.label)
        assertEquals("https://github.com/o/r.git", target?.url)
        assertNull("no remote means no push target", GitSafety.pushTarget("", "main"))
        assertNull("a detached head has no branch to push", GitSafety.pushTarget("origin\tu", "HEAD"))
    }

    @Test
    fun decliningAPushKeepsTheCommitAndSaysSo() {
        val target = GitSafety.PushTarget("origin", "main", null)
        val text = GitSafety.declinedPush(target, "abc123 fix: x")
        assertTrue(text.contains("kept locally"))
        assertTrue(text.contains("abc123"))
        assertTrue(text.contains("origin/main"))
    }

    @Test
    fun approvalTextsShowTheCountsAndTheTarget() {
        val plan = GitSafety.planStaging(listOf("a", "b", "c"), emptyList())
        val stage = GitSafety.stageApprovalText(plan, "fix: the thing\n\nbody line")
        assertTrue(stage.contains("3 file(s)"))
        assertTrue(stage.contains("fix: the thing"))
        assertFalse("the body is not the subject", stage.contains("body line"))
        val target = GitSafety.PushTarget("origin", "main", "git@github.com:o/r.git")
        assertTrue(GitSafety.mainPushApprovalText(target).contains("cannot be undone"))
        assertTrue(GitSafety.pushApprovalText(target).contains("origin/main"))
    }

    @Test
    fun subjectIsTheFirstNonBlankLineCollapsed() {
        assertEquals("fix: timeout", GitSafety.subject("\n\n   fix: timeout   \nbody"))
        assertEquals("", GitSafety.subject("   \n "))
    }
}
