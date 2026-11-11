package com.codespace.ide.agent

import com.codespace.ide.chat.panel.AgentToolWire
import com.codespace.ide.chat.panel.ChatMode
import com.codespace.ide.chat.panel.ToolMetaIndex
import com.codespace.ide.chat.panel.ToolRegistry
import com.codespace.ide.chat.panel.ToolRelevance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * Wave 0.1 (owner approval 2026-11-11): grep_replace_all, asserted where it actually fails.
 *
 * The interesting cases are not the happy path. They are: a partial write reported as success, a
 * cap that silently processes a subset, a symlink that walks out of the workspace, and a search
 * string containing a backslash that a regex-based implementation would eat.
 */
class GrepReplaceAllTest {

    @get:Rule val tmp = TemporaryFolder()

    private var seq = 0

    private fun tree(vararg files: Pair<String, String>): File {
        seq++
        val root = tmp.newFolder("ws" + seq)
        for ((path, content) in files) {
            val f = File(root, path)
            f.parentFile?.mkdirs()
            f.writeText(content)
        }
        return root
    }

    // 1 + 2: across three files, with per-file counts
    @Test
    fun replacesAcrossThreeFilesAndCountsEachOne() {
        val root = tree(
            "a.kt" to "val x = TODO\nval y = TODO\n",
            "b.kt" to "val z = TODO\n",
            "c.txt" to "no match here\n",
            "src/d.kt" to "TODO once\n",
        )
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = false)
        assertNull(plan.refused)
        assertEquals(4, plan.total)
        assertEquals(3, plan.files)
        val byRel = plan.changes.associate { it.rel to it.count }
        assertEquals(2, byRel["a.kt"])
        assertEquals(1, byRel["b.kt"])
        assertEquals(1, byRel["src/d.kt"])
        // the report lists every affected file, never a truncated list
        val report = MultiFileReplace.report(plan, dryRun = false)
        assertTrue(report.contains("a.kt: 2"))
        assertTrue(report.contains("b.kt: 1"))
        assertTrue(report.contains("src/d.kt: 1"))
    }

    // 3: dry run reports without changing anything
    @Test
    fun dryRunReportsTheSameFilesAndWritesNothing() {
        val root = tree("a.kt" to "TODO\n", "b.kt" to "TODO TODO\n")
        val before = File(root, "a.kt").readText()
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = true)
        assertEquals(2, plan.files)
        assertEquals(3, plan.total)
        assertTrue(plan.changes.all { it.updated == null })
        assertEquals(before, File(root, "a.kt").readText())
        assertTrue(MultiFileReplace.report(plan, dryRun = true).startsWith("DRY RUN"))
    }

    // 4: zero matches is a clear no-op
    @Test
    fun zeroMatchesIsAnExplicitNoOp() {
        val root = tree("a.kt" to "nothing here\n")
        val plan = MultiFileReplace.plan(root, "absent", "x", "", dryRun = false)
        assertNull(plan.refused)
        assertTrue(plan.changes.isEmpty())
        val msg = MultiFileReplace.noMatches(plan, "")
        assertTrue(msg.contains("No matches"))
        assertTrue(msg.contains("Nothing was changed"))
    }

    // 5: .git is never touched
    @Test
    fun gitDirectoryIsNeverSearched() {
        val root = tree(
            ".git/config" to "TODO = true\n",
            "a.kt" to "TODO\n",
        )
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = false)
        assertEquals(1, plan.files)
        assertEquals("a.kt", plan.changes.first().rel)
        assertEquals("TODO = true\n", File(root, ".git/config").readText())
    }

    // 6: binary files are skipped with a reason
    @Test
    fun binaryFilesAreSkippedWithAReason() {
        val root = tmp.newFolder("wsbin")
        File(root, "a.kt").writeText("TODO\n")
        File(root, "logo.bin").writeBytes(byteArrayOf(0x54, 0x4F, 0x44, 0x00, 0x4F))
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = false)
        assertEquals(1, plan.files)
        val skipped = plan.skipped.associate { it.rel to it.reason }
        assertEquals("binary", skipped["logo.bin"])
        assertTrue(MultiFileReplace.report(plan, dryRun = false).contains("logo.bin (binary)"))
        assertEquals(5, File(root, "logo.bin").readBytes().size)
    }

    // 7: the 200-file cap refuses instead of processing a subset
    @Test
    fun moreThanTwoHundredCandidatesRefusesWithoutWriting() {
        val root = tmp.newFolder("wscap")
        for (i in 1..201) File(root, "f%03d.kt".format(i)).writeText("TODO\n")
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = false)
        assertNotNull("the cap must refuse, not trim", plan.refused)
        assertTrue(plan.refused!!.contains("200"))
        assertTrue(plan.changes.isEmpty())
        assertEquals("TODO\n", File(root, "f001.kt").readText())
    }

    // 8 + 9: a link that leaves the workspace is not followed
    @Test
    fun symlinkOutOfTheWorkspaceIsRejected() {
        val root = tmp.newFolder("wslink")
        File(root, "a.kt").writeText("TODO\n")
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("TODO\n")
        val link = File(root, "escape")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (t: Throwable) {
            return // filesystem without symlink support: nothing to assert here
        }
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = false)
        assertEquals(1, plan.files)
        assertEquals("a.kt", plan.changes.first().rel)
        assertTrue(plan.skipped.any { it.rel.startsWith("escape") && it.reason.contains("symlink") })
        assertEquals("TODO\n", File(outside, "secret.txt").readText())
    }

    @Test
    fun aFilterCannotReachOutsideTheWorkspace() {
        val root = tree("a.kt" to "TODO\n")
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "../../etc", dryRun = true)
        assertNull(plan.refused)
        assertTrue(plan.changes.isEmpty())
    }

    // 10: quotes, backslashes, newlines, dollar signs stay literal
    @Test
    fun specialCharactersAreReplacedLiterally() {
        val root = tree("a.kt" to "val s = \"say \\\"hi\\\"\"\n")
        val search = "\\\"hi\\\""
        val replace = "\\\"bye\\\" \\\\n \$1"
        val plan = MultiFileReplace.plan(root, search, replace, "", dryRun = false)
        assertEquals(1, plan.total)
        val updated = plan.changes.first().updated!!
        assertTrue(updated.contains("\\\"bye\\\" \\\\n \$1"))
        assertFalse(updated.contains("\\\"hi\\\""))

        val multi = tree("m.kt" to "line1\nline2\n")
        val block = MultiFileReplace.plan(multi, "line1\nline2", "one\ntwo", "", dryRun = false)
        assertEquals(1, block.total)
        assertEquals("one\ntwo\n", block.changes.first().updated)
    }

    // 11: EDIT mode stages, Agent mode does not
    @Test
    fun editModeStagesTheThreeFileChangeInsteadOfWriting() {
        assertTrue(AgentToolWire.isStagedWrite(ChatMode.EDIT, "grep_replace_all"))
        assertFalse(AgentToolWire.isStagedWrite(ChatMode.AGENT, "grep_replace_all"))
        // the plan EDIT mode stages is the same one Agent mode would write
        val root = tree("a.kt" to "TODO\n", "b.kt" to "TODO\n", "c.kt" to "TODO\n")
        val plan = MultiFileReplace.plan(root, "TODO", "DONE", "", dryRun = true)
        assertEquals(3, plan.files)
        assertEquals(3, plan.total)
        // PendingChangesStore is keyed by session+path, so N proposals are representable
        assertTrue(plan.changes.all { it.updated == null })
    }

    // 12: a write tool is never auto-approved by AUTO_SAFE, and Ask cannot call it
    @Test
    fun theToolIsWriteOnlyAndNeverAutoApprovedAsSafe() {
        assertFalse(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_SAFE, "grep_replace_all", emptySet()))
        assertFalse(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.MANUAL, "grep_replace_all", emptySet()))
        assertTrue(ChatPermissionStore.decideAutoApproved(ChatFlowLevel.AUTO_ALL, "grep_replace_all", emptySet()))
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "grep_replace_all"))
        assertTrue(ToolRegistry.allows(ChatMode.EDIT, "grep_replace_all"))
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "grep_replace_all"))
    }

    // 13: registered AND executable - never spec-only
    @Test
    fun theRegistryEntryHasARealExecutor() {
        val spec = ToolRegistry.ALL.firstOrNull { it.name == "grep_replace_all" }
        assertNotNull("grep_replace_all must be registered", spec)
        assertTrue("it must be exposed in at least one mode", spec!!.modes.isNotEmpty())
        assertTrue("the dispatcher must know the name", AgentTools.toolNames().contains("grep_replace_all"))
    }

    // 14: metadata and discovery keywords are present
    @Test
    fun metadataAndDiscoveryKeywordsExist() {
        val spec = ToolRegistry.ALL.first { it.name == "grep_replace_all" }
        val meta = ToolMetaIndex.metaFor(spec)
        assertTrue("explicit metadata, not the derived fallback", meta.keywords.isNotEmpty())
        assertTrue(meta.destructive)
        val ranked = ToolRelevance.rank(ChatMode.EDIT, "replace this string everywhere in the project")
        assertTrue("relevance must be able to offer it", "grep_replace_all" in ranked)
        assertTrue(ToolMetaIndex.search("bulk replace", ChatMode.EDIT).any { it.spec.name == "grep_replace_all" })
    }

    // 15: the added doc stays inside the prompt budget
    @Test
    fun theDescriptionStaysShortEnoughForTheBudget() {
        val spec = ToolRegistry.ALL.first { it.name == "grep_replace_all" }
        assertTrue("one sentence, not a manual: " + spec.description.length, spec.description.length < 260)
    }

    /** Regression: the single-file paths this tool must not disturb. */
    @Test
    fun singleFileReplacementStillWorksAndStillRefusesAmbiguity() {
        val single = ReplaceText.apply("a TODO b TODO", "TODO", "DONE", replaceAll = false)
        assertTrue(single is ReplaceOutcome.Refused)
        val many = ReplaceText.apply("a TODO b TODO", "TODO", "DONE", replaceAll = true)
        assertTrue(many is ReplaceOutcome.Ok)
        assertEquals(2, (many as ReplaceOutcome.Ok).count)
        val root = tree("one.kt" to "TODO TODO\n")
        val filtered = MultiFileReplace.plan(root, "TODO", "DONE", "*.kt", dryRun = false)
        assertEquals(2, filtered.total)
        assertEquals(1, filtered.files)
    }
}
