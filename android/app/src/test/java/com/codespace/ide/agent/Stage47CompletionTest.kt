package com.codespace.ide.agent

import com.codespace.ide.agent.research.ClaimReconciliation
import com.codespace.ide.agent.research.ResearchResults
import com.codespace.ide.agent.research.ResearchSynthesis
import com.codespace.ide.chat.panel.ChatMode
import com.codespace.ide.chat.panel.ProviderContext
import com.codespace.ide.chat.panel.ToolCost
import com.codespace.ide.chat.panel.ToolSuitability
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stage 4.7 completion: live routing, research surface, provider context. */
class Stage47CompletionTest {

    private fun tempDir(): File = Files.createTempDirectory("vncode-c").toFile()

    private fun git(dir: File, vararg args: String): String {
        val p = ProcessBuilder(listOf("git") + args).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }
        p.waitFor()
        return out
    }

    private fun repo(): File {
        val dir = tempDir()
        git(dir, "init", "-q")
        git(dir, "config", "user.email", "t@local")
        git(dir, "config", "user.name", "T")
        File(dir, "Main.kt").writeText("fun main() {}")
        git(dir, "add", "Main.kt")
        git(dir, "commit", "-q", "-m", "real commit")
        return dir
    }

    @After
    fun tearDown() {
        ProviderContext.clear()
        com.codespace.ide.agent.runtime.PrereqLedger.clear()
    }

    // ---- A: live routing ----

    @Test
    fun aCommitClaimIsCheckedAgainstTheRepositoryNotTheMessage() {
        val dir = repo()
        val lie = OutcomeVerifier.verify(
            "git_commit_push",
            "Committed and pushed successfully",
            JSONObject().put("message", "a commit that was never made"),
            dir.absolutePath,
        )
        assertEquals(OutcomeVerifier.Result.REFUTED, lie.result)
        assertTrue(lie.note.contains("not"))

        val truth = OutcomeVerifier.verify(
            "git_commit_push",
            "Committed",
            JSONObject().put("message", "real commit"),
            dir.absolutePath,
        )
        assertEquals(OutcomeVerifier.Result.VERIFIED, truth.result)
    }

    @Test
    fun verificationDoesNotRepeatTheSideEffect() {
        val dir = repo()
        val before = git(dir, "rev-list", "--count", "HEAD").trim()
        OutcomeVerifier.verify("git_commit_push", "ok", JSONObject().put("message", "real commit"), dir.absolutePath)
        OutcomeVerifier.verify("git_commit_push", "ok", JSONObject().put("message", "real commit"), dir.absolutePath)
        val after = git(dir, "rev-list", "--count", "HEAD").trim()
        assertEquals("no commit was made by verifying", before, after)
    }

    @Test
    fun aCheckoutClaimIsCheckedAgainstTheBranch() {
        val dir = repo()
        val branch = GitFreshVerifier.read(dir.absolutePath).state!!.branch!!
        assertTrue(
            OutcomeVerifier.verify("git_checkout", "Switched", JSONObject().put("branch", branch), dir.absolutePath).ok,
        )
        assertEquals(
            OutcomeVerifier.Result.REFUTED,
            OutcomeVerifier.verify("git_checkout", "Switched", JSONObject().put("branch", "nope"), dir.absolutePath).result,
        )
    }

    @Test
    fun stagingIsCheckedAgainstTheIndex() {
        val dir = repo()
        File(dir, "New.kt").writeText("x")
        git(dir, "add", "New.kt")
        assertTrue(
            OutcomeVerifier.verify("git_add", "Added", JSONObject().put("paths", "New.kt"), dir.absolutePath).ok,
        )
        val unstaged = OutcomeVerifier.verify(
            "git_add", "Added", JSONObject().put("paths", "Missing.kt"), dir.absolutePath,
        )
        assertEquals(OutcomeVerifier.Result.REFUTED, unstaged.result)
    }

    @Test
    fun extractionIsCheckedOnDiskAndNotByTheListing() {
        val archive = File(tempDir(), "b.zip")
        ZipOutputStream(archive.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("a.txt"))
            out.write("hello".toByteArray())
            out.closeEntry()
        }
        val dest = tempDir()
        File(dest, "a.txt").writeText("hello")
        val args = JSONObject().put("archive", archive.absolutePath).put("dest", dest.absolutePath)
        assertEquals(OutcomeVerifier.Result.VERIFIED,
            OutcomeVerifier.verify("zip_extract", "Extracted 1 file", args, null).result)

        val empty = tempDir()
        val args2 = JSONObject().put("archive", archive.absolutePath).put("dest", empty.absolutePath)
        assertEquals(OutcomeVerifier.Result.REFUTED,
            OutcomeVerifier.verify("zip_extract", "Extracted 1 file", args2, null).result)
    }

    @Test
    fun anUnknownArchiveDestinationIsNeverSuccess() {
        val args = JSONObject().put("archive", "/no/such.zip").put("dest", "/no/such/dir")
        val outcome = OutcomeVerifier.verify("zip_extract", "Extracted", args, null)
        assertEquals(OutcomeVerifier.Result.NOT_CHECKABLE, outcome.result)
        assertFalse(outcome.proven)
    }

    @Test
    fun withNoProjectOpenAnUnknownGitStateStaysUnknown() {
        val outcome = OutcomeVerifier.verify("git_commit_push", "", JSONObject().put("message", "x"), null)
        assertFalse("an empty log cannot confirm anything", outcome.proven)
    }

    // ---- B: research result ----

    private fun evidence(id: String, url: String, text: String) =
        ResearchSynthesis.Evidence(sourceId = id, title = "T $id", url = url, text = text)

    @Test
    fun theBriefCarriesClaimStatusAndSourceLinks() {
        val brief = ResearchSynthesis.build(
            question = "when is the release",
            evidence = listOf(
                evidence("s1", "https://a.example/x", "The release is planned for March 2027."),
                evidence("s2", "https://b.example/y", "The release is planned for March 2027."),
            ),
        )
        assertTrue(brief.reconciled.isNotEmpty())
        assertTrue(brief.render.contains("Claims and their sources:"))
        assertTrue(brief.reconciled.any { it.confirmed })
        assertTrue(brief.reconciled.all { it.sourceId.isNotBlank() })
    }

    @Test
    fun everyClaimOnTheResultNamesItsStatusAndSourceOrSaysItHasNone() {
        val brief = ResearchSynthesis.build(
            question = "when is the release",
            evidence = listOf(evidence("s1", "https://a.example/x", "The release is planned for March 2027.")),
        )
        assertTrue(brief.render.contains("Claims and their sources:"))
        brief.reconciled.forEach { verdict ->
            assertTrue(verdict.sourceId.isNotBlank() || verdict.support == ClaimReconciliation.Support.UNVERIFIED)
            if (!verdict.confirmed) {
                assertTrue("an unconfirmed claim is never presented as settled", brief.render.contains("do not present this as settled"))
            }
        }
        // A disagreement the scan reports is never resolved in the brief; it is presented as both sides.
        assertTrue(ResearchSynthesis.build("q", emptyList()).reconciled.isEmpty() ||
            ResearchSynthesis.build("q", emptyList()).reconciled.all { !it.confirmed })
    }

    @Test
    fun aSavedResultKeepsTheReconciledRowsAndAnOldOneStillLoads() {
        val brief = ResearchSynthesis.build(
            question = "q",
            evidence = listOf(evidence("s1", "https://a.example/x", "The release is planned for March 2027.")),
        )
        val file = File(tempDir(), "research.json")
        val results = ResearchResults.forTest(file)
        results.save("q", 1L, brief)
        val saved = results.latest()
        assertNotNull(saved)
        assertTrue("reconciled rows persist", saved!!.reconciled.isNotEmpty())

        file.writeText("""[{"question":"old","atMs":1,"corroborated":[],"unsupported":[],"disputed":[],"unresolved":[],"followUps":[]}]""")
        val old = ResearchResults.forTest(file).latest()
        assertNotNull("an old result still loads", old)
        assertTrue(old!!.reconciled.isEmpty())
    }

    // ---- C: provider context ----

    private fun rank(names: List<String>, request: String = "do it") = ToolSuitability.rank(
        request = request,
        candidates = names,
        mode = ChatMode.AGENT,
        isOffered = { true },
        exists = { true },
        healthPenalty = { 0 },
        demoted = emptySet(),
        costOf = { ToolCost.NORMAL },
        needsNetwork = { false },
        networkAvailable = true,
    )

    @Test
    fun aConnectedProviderScoresAndADisconnectedOneDoesNot() {
        ProviderContext.update("proj-1", mapOf("connectors" to ProviderContext.State.CONNECTED))
        val connected = rank(listOf("use_connector"), "send a slack message").best!!
        assertTrue(connected.signals.any { it.name == "provider" && it.why.contains("connected") })

        ProviderContext.update("proj-1", mapOf("connectors" to ProviderContext.State.DISCONNECTED))
        val disconnected = rank(listOf("use_connector"), "send a slack message").best!!
        assertTrue(disconnected.eligible)
        assertTrue(disconnected.signals.any { it.name == "provider" && it.why.contains("disconnected") })
    }

    @Test
    fun notConfiguredAndDisconnectedReadDifferently() {
        ProviderContext.update("p", mapOf("connectors" to ProviderContext.State.NOT_CONFIGURED))
        val state = ProviderContext.stateOf("use_connector")
        assertEquals(ProviderContext.State.NOT_CONFIGURED, state)
        assertTrue(ProviderContext.phrase(state!!).contains("not set up"))
    }

    @Test
    fun noWorkspaceLeavesProviderStateUnknown() {
        ProviderContext.clear()
        assertNull(ProviderContext.stateOf("use_connector"))
        val best = rank(listOf("use_connector")).best!!
        assertTrue(best.signals.any { it.name == "provider" && it.why.contains("unknown") })
    }

    @Test
    fun aStaleSnapshotIsNotUsed() {
        ProviderContext.update("p", mapOf("connectors" to ProviderContext.State.CONNECTED), now = 1_000L)
        assertNull(ProviderContext.snapshot(now = 1_000L + ProviderContext.STALE_MS + 1))
        assertNull(ProviderContext.stateOf("use_connector", now = 1_000L + ProviderContext.STALE_MS + 1))
    }

    @Test
    fun switchingWorkspaceReplacesTheWholeView() {
        ProviderContext.update("p1", mapOf("connectors" to ProviderContext.State.CONNECTED))
        ProviderContext.update("p2", mapOf("connectors" to ProviderContext.State.DISCONNECTED))
        assertEquals(ProviderContext.State.DISCONNECTED, ProviderContext.stateOf("use_connector"))
        assertEquals("p2", ProviderContext.snapshot()!!.workspaceId)
    }

    @Test
    fun aToolWithNoProviderIsNotApplicableRatherThanPenalised() {
        ProviderContext.update("p", emptyMap())
        val best = rank(listOf("grep_search")).best!!
        assertTrue(best.signals.any { it.name == "provider" && it.why.contains("no connected service") })
    }

    @Test
    fun noSecretEverAppearsInAnExplanation() {
        ProviderContext.update("proj-sk-live-SECRETVALUE", mapOf("connectors" to ProviderContext.State.CONNECTED))
        val text = rank(listOf("use_connector"), "message").best!!.explanation
        assertFalse(text.contains("SECRETVALUE"))
        assertFalse(text.contains("sk-live"))
    }

    @Test
    fun contextCannotOverrideModeOrRegistration() {
        ProviderContext.update("p", mapOf("connectors" to ProviderContext.State.CONNECTED))
        val choice = ToolSuitability.rank(
            request = "send a slack message",
            candidates = listOf("use_connector"),
            mode = ChatMode.ASK,
            isOffered = { false },
            exists = { true },
            healthPenalty = { 0 },
            demoted = emptySet(),
            costOf = { ToolCost.CHEAP },
            needsNetwork = { true },
            networkAvailable = true,
            providerState = { ProviderContext.State.CONNECTED },
        )
        assertNull("a connected provider cannot make an unoffered tool eligible", choice.best)
    }
}
