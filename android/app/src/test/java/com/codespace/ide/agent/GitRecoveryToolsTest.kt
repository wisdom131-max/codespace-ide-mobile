package com.codespace.ide.agent

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GIT RECOVERY SAFETY (2026-11-12).
 *
 * The runner seam means these tests see the EXACT argument vectors. That is the point: a recovery
 * tool is judged by the commands it does NOT run (no reset --hard, no checkout without confirm, no
 * side chosen in a conflict) as much as by the ones it does.
 */
class GitRecoveryToolsTest {

    private class Recorder(private val answers: Map<String, String> = emptyMap()) : GitExtraTools.Runner {
        val calls = mutableListOf<List<String>>()
        override fun run(args: List<String>): String {
            calls.add(args)
            val key = args.firstOrNull() ?: ""
            answers[args.joinToString(" ")]?.let { return it }
            answers[key]?.let { return it }
            return when (key) {
                "rev-parse" -> if (args.contains("--is-inside-work-tree")) "true" else ""
                else -> ""
            }
        }
    }

    private fun repo(): File {
        val d = kotlin.io.path.createTempDirectory("gitrec").toFile()
        d.deleteOnExit()
        return d
    }

    // ------------------------------------------------------------------ log

    @Test
    fun logRendersHashDateAuthorAndSubject() {
        val out = GitRecoveryTools.renderLog(
            "abc1234|2026-11-12|Ada|checkpoint the tree\n" +
            "def5678|2026-11-11|Grace|fix the parser",
            20, null,
        )
        assertTrue(out, out.contains("abc1234"))
        assertTrue(out, out.contains("2026-11-12"))
        assertTrue(out, out.contains("Ada"))
        assertTrue(out, out.contains("fix the parser"))
    }

    @Test
    fun logIsCappedAndSaysSo() {
        val raw = (1..40).joinToString("\n") { "h$it|2026-11-12|a|subject $it" }
        val out = GitRecoveryTools.renderLog(raw, 40, null)
        assertTrue(out, out.contains("capped at 40"))
    }

    @Test
    fun anEmptyHistorySaysSoInsteadOfReturningABlankAnswer() {
        val out = GitRecoveryTools.renderLog("", 20, "src")
        assertTrue(out, out.contains("No commits matched"))
        assertTrue(out, out.contains("src"))
    }

    @Test
    fun logPassesAPathAsAPathspecifierNotAsARevision() {
        val run = Recorder()
        GitRecoveryTools.log(repo(), run, 5, "src/main.kt")
        val args = run.calls.first { it.first() == "log" }
        assertEquals("--", args[args.size - 2])
        assertEquals("src/main.kt", args.last())
        assertEquals("5", args[args.indexOf("-n") + 1])
    }

    // ------------------------------------------------------------- checkpoint

    @Test
    fun aCheckpointUsesStashCreateAndAPrivateRefAndTouchesNoFiles() {
        val run = Recorder(mapOf("stash" to "deadbeefcafe1234"))
        val out = GitRecoveryTools.checkpoint(repo(), run, "before refactor", "2026-11-12T10-00-00Z")
        val stash = run.calls.first { it.first() == "stash" }
        assertEquals(listOf("stash", "create", "before refactor"), stash)
        // The rule that keeps the user's work in place: no `stash push`, no `checkout`, no `reset`.
        assertTrue(run.calls.none { it.first() == "checkout" || it.first() == "reset" })
        assertTrue(run.calls.any { it.first() == "update-ref" && it.contains("refs/vncode/checkpoint/2026-11-12T10-00-00Z") })
        assertTrue(out, out.contains("deadbeefcaf"))
        assertTrue("says the tree was untouched", out.contains("NOT modified"))
    }

    @Test
    fun aCheckpointOnACleanTreeRefusesInsteadOfInventingOne() {
        val run = Recorder(mapOf("stash" to "", "status" to ""))
        val out = GitRecoveryTools.checkpoint(repo(), run, "x", "s")
        assertTrue(out, out.contains("Nothing to checkpoint"))
        assertTrue(run.calls.none { it.first() == "update-ref" })
    }

    @Test
    fun aCheckpointNamesTheUntrackedFilesItCannotHold() {
        val run = Recorder(mapOf("stash" to "abc123", "status" to "?? notes.md\n?? scratch/"))
        val out = GitRecoveryTools.checkpoint(repo(), run, "x", "s")
        assertTrue(out, out.contains("notes.md"))
        assertTrue(out, out.contains("NOT in this checkpoint"))
    }

    // ---------------------------------------------------------------- restore

    @Test
    fun restoreWithoutConfirmOnlyPreviewsAndChangesNothing() {
        val run = Recorder(mapOf("diff" to " src/a.kt | 4 ++--\n 1 file changed"))
        val out = GitRecoveryTools.restore(repo(), run, "refs/vncode/checkpoint/s", listOf("src/a.kt"), confirm = false)
        assertTrue(out, out.contains("PREVIEW ONLY"))
        assertTrue(out, out.contains("src/a.kt | 4"))
        assertTrue("no checkout may run without confirm", run.calls.none { it.first() == "checkout" })
    }

    @Test
    fun restoreWithConfirmTouchesOnlyTheNamedPaths() {
        val run = Recorder(mapOf("diff" to " src/a.kt | 4 ++--"))
        GitRecoveryTools.restore(repo(), run, "abc123", listOf("src/a.kt"), confirm = true)
        val checkout = run.calls.first { it.first() == "checkout" }
        assertEquals(listOf("checkout", "abc123", "--", "src/a.kt"), checkout)
        assertTrue("never resets the tree", run.calls.none { it.first() == "reset" })
    }

    @Test
    fun restoreRefusesWithoutFilesSoNothingCanBeRevertedWholesale() {
        val run = Recorder()
        val out = GitRecoveryTools.restore(repo(), run, "abc123", emptyList(), confirm = true)
        assertTrue(out, out.contains("files="))
        assertTrue(run.calls.none { it.first() == "checkout" })
    }

    @Test
    fun restoreIsANoOpWhenTheFilesAlreadyMatch() {
        val run = Recorder(mapOf("diff" to ""))
        val out = GitRecoveryTools.restore(repo(), run, "abc123", listOf("a.kt"), confirm = true)
        assertTrue(out, out.contains("Nothing to restore"))
        assertTrue(run.calls.none { it.first() == "checkout" })
    }

    // -------------------------------------------------------------- conflicts

    @Test
    fun conflictsAreListedAndNoSideIsChosen() {
        val run = Recorder(mapOf("diff" to "src/a.kt\nsrc/b.kt"))
        val out = GitRecoveryTools.conflicts(repo(), run, "list", null)
        assertTrue(out, out.contains("src/a.kt"))
        assertTrue(out, out.contains("src/b.kt"))
        assertTrue("says the choice is the user's", out.contains("Nothing is chosen for you"))
        assertTrue(run.calls.none { it.contains("--ours") || it.contains("--theirs") })
    }

    @Test
    fun stagingAFileThatIsNotConflictedIsRefused() {
        val run = Recorder(mapOf("diff" to "src/a.kt"))
        val out = GitRecoveryTools.conflicts(repo(), run, "stage", "src/other.kt")
        assertTrue(out, out.contains("not conflicted"))
        assertTrue(run.calls.none { it.first() == "add" })
    }

    @Test
    fun stagingAConflictedFileAddsExactlyThatPath() {
        val run = Recorder(mapOf("diff" to "src/a.kt"))
        GitRecoveryTools.conflicts(repo(), run, "stage", "src/a.kt")
        assertEquals(listOf("add", "--", "src/a.kt"), run.calls.first { it.first() == "add" })
    }

    @Test
    fun abortOnlyRunsWhenThereIsAMergeToAbort() {
        val clean = Recorder(mapOf("diff" to "", "status" to ""))
        val refused = GitRecoveryTools.conflicts(repo(), clean, "abort", null)
        assertTrue(refused, refused.contains("No merge is in progress"))
        assertTrue(clean.calls.none { it.first() == "merge" })

        val dirty = Recorder(mapOf("diff" to "src/a.kt"))
        GitRecoveryTools.conflicts(repo(), dirty, "abort", null)
        assertEquals(listOf("merge", "--abort"), dirty.calls.first { it.first() == "merge" })
    }

    @Test
    fun aNonRepositoryIsRefusedBeforeAnyCommandRuns() {
        val run = object : GitExtraTools.Runner {
            val calls = mutableListOf<List<String>>()
            override fun run(args: List<String>): String {
                calls.add(args)
                return "false"
            }
        }
        val out = GitRecoveryTools.log(repo(), run, 5, null)
        assertTrue(out, out.contains("Not a git repository"))
        assertEquals(1, run.calls.size)
    }
}
