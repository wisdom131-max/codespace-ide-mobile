package com.codespace.ide.chat.panel

import com.codespace.ide.agent.runtime.PrereqLedger
import com.codespace.ide.agent.runtime.RuntimeBridge
import com.codespace.ide.agent.runtime.RuntimeSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT FOUR STAGE 4.2: prerequisites, dependency order, coverage and the gate that enforces them.
 *
 * The gate is exercised through [RuntimeBridge.prerequisiteRefusal] and
 * [PrereqLedger], because those are what the live dispatch path calls. Candidates are checked
 * against the real registry and its real metadata, not fixtures, wherever the point is permissions.
 */
class ToolPrerequisiteTest {

    @After
    fun clean() {
        PrereqLedger.clear()
        RuntimeSession.claimMode(RuntimeSession.MODE_ASK)
    }

    // ── Phase 2: metadata ─────────────────────────────────────────────────

    @Test
    fun everyDeclaredPrerequisiteNamesAKnownCapability() {
        val known = ToolPrerequisites.knownCapabilities()
        ToolPrerequisites.knownCapabilities().forEach { cap ->
            ToolPrerequisites.prereqsOf(cap).forEach { edge ->
                assertTrue("'" + cap + "' waits on unknown '" + edge.of + "'", edge.of in known)
            }
        }
    }

    @Test
    fun replacingTextCannotBeDeclaredOptional() {
        // A replace that never found its match is a silent no-op, so this edge has to block.
        val required = ToolPrerequisites.requiredOf("replace").map { it.of }
        assertEquals(listOf("find"), required)
    }

    @Test
    fun optionalPrerequisitesNeverBlock() {
        assertTrue(ToolPrerequisites.requiredOf("edit").isEmpty())
        assertEquals(listOf("read"), ToolPrerequisites.optionalOf("edit").map { it.of })
    }

    @Test
    fun toolsMapToTheCapabilityTheyServe() {
        assertEquals("find", ToolPrerequisites.capabilityOf("grep_search"))
        assertEquals("replace", ToolPrerequisites.capabilityOf("grep_replace_all"))
        assertNull(ToolPrerequisites.capabilityOf("zip_list_telemetry"))
    }

    @Test
    fun independentCapabilitiesHaveNoEdges() {
        assertTrue(ToolPrerequisites.prereqsOf("web").isEmpty())
        assertTrue(ToolPrerequisites.prereqsOf("diff").isEmpty())
    }

    // ── Phase 3: dependency-aware planning ────────────────────────────────

    private fun planOf(vararg caps: String): DependencyPlanner.Plan =
        DependencyPlanner.plan(caps.toList())

    @Test
    fun onePrerequisiteOrdersBeforeItsDependent() {
        val plan = planOf("replace")
        assertEquals(listOf(listOf("find"), listOf("replace")), plan.waves)
        assertTrue(plan.executable)
    }

    @Test
    fun multipleLevelsOrderInWaves() {
        // unzip requires archive, which is a real level. Its own link back to find is OPTIONAL, so
        // find is not scheduled as a wave: an optional step never becomes a mandatory call.
        val plan = planOf("unzip")
        assertEquals(listOf(listOf("archive"), listOf("unzip")), plan.waves)
        assertTrue(plan.node("archive")?.optional?.contains("find") == true)
    }

    @Test
    fun independentOperationsShareAWave() {
        val plan = planOf("web", "diff")
        assertEquals(listOf(listOf("web", "diff")), plan.waves)
    }

    @Test
    fun anAlreadySatisfiedPrerequisiteIsNotRepeated() {
        val plan = DependencyPlanner.plan(
            listOf("replace"),
            satisfied = setOf("find"),
            available = setOf("find", "replace"),
        )
        assertEquals(listOf("replace"), plan.waves.flatten())
        assertTrue(plan.executable)
    }

    @Test
    fun aMissingPrerequisiteMakesItsDependentImpossible() {
        val plan = DependencyPlanner.plan(listOf("replace"), available = setOf("replace"))
        val node = plan.node("replace")
        assertNotNull(node)
        assertFalse(plan.executable)
        assertTrue(plan.notes.any { it.contains("not available") })
    }

    @Test
    fun aFailedPrerequisiteBlocksItsDependent() {
        val plan = DependencyPlanner.plan(
            listOf("replace"),
            available = setOf("find", "replace"),
            failed = setOf("find"),
        )
        assertEquals(DependencyPlanner.State.BLOCKED, plan.node("replace")?.state)
        assertTrue(plan.waves.flatten().isEmpty())
    }

    @Test
    fun aCycleIsRefusedWithItsPath() {
        // Two capabilities that each need the other cannot be ordered; the planner says which.
        val cyclic: (String) -> List<ToolPrerequisites.Prereq> = { cap ->
            when (cap) {
                "a" -> listOf(ToolPrerequisites.Prereq("b"))
                "b" -> listOf(ToolPrerequisites.Prereq("a"))
                else -> emptyList()
            }
        }
        val plan = DependencyPlanner.plan(listOf("a"), prereqs = cyclic)
        assertFalse(plan.executable)
        assertTrue(plan.cycle.contains("a") && plan.cycle.contains("b"))
        assertTrue(plan.waves.isEmpty())
    }

    @Test
    fun somethingUnavailableIsNotReportedAsReady() {
        val plan = DependencyPlanner.plan(listOf("web"), available = emptySet())
        assertEquals(DependencyPlanner.State.IMPOSSIBLE, plan.node("web")?.state)
    }

    // ── Phase 4: whole-request coverage ───────────────────────────────────

    @Test
    fun aSingleCapabilityRequestIsCoveredInAgentMode() {
        val offered = ToolRegistry.exposedNames(ChatMode.AGENT).toSet()
        val report = ToolTaskCoverage.report("find where the token is used", ChatMode.AGENT, offered)
        assertTrue(report.assessment.needs.map { it.id }.contains("find"))
        assertTrue(report.canDo.contains("find"))
    }

    @Test
    fun aMultiCapabilityRequestReportsEveryCapability() {
        val offered = ToolRegistry.exposedNames(ChatMode.AGENT).toSet()
        val report = ToolTaskCoverage.report(
            "find the token and replace it everywhere, then run the tests",
            ChatMode.AGENT,
            offered,
        )
        val ids = report.assessment.needs.map { it.id }
        assertTrue(ids.containsAll(listOf("find", "replace", "test")))
        assertTrue(report.order.isNotEmpty())
    }

    @Test
    fun partialCoverageIsNeverReportedAsComplete() {
        // Ask cannot replace text, so a replace request is only partly doable there.
        val offered = ToolRegistry.exposedNames(ChatMode.ASK).toSet()
        val report = ToolTaskCoverage.report("replace the old token", ChatMode.ASK, offered)
        assertFalse("a half-supported request must not be called executable", report.executable)
        assertFalse(report.cannotDo.isEmpty() && report.reasons.isEmpty())
    }

    @Test
    fun aLoadableCapabilityIsNamedRatherThanTreatedAsMissing() {
        // A tool that exists and is exposed in this mode but is not offered can be loaded.
        val report = ToolTaskCoverage.report("read the file", ChatMode.AGENT, emptySet())
        val finding = report.assessment.findings.first { it.need.id == "read" }
        assertEquals(ToolCapabilityPlan.State.LOADABLE, finding.state)
        assertTrue(report.reasons.any { it.contains("loaded") })
    }

    @Test
    fun aCapabilityOnlyAnotherModeExposesIsReportedThatWay() {
        val offered = ToolRegistry.exposedNames(ChatMode.ASK).toSet()
        val report = ToolTaskCoverage.report("replace the old token", ChatMode.ASK, offered)
        val finding = report.assessment.findings.first { it.need.id == "replace" }
        assertEquals(ToolCapabilityPlan.State.OTHER_MODE, finding.state)
    }

    // ── Phase 5: the gate ────────────────────────────────────────────────

    @Test
    fun aReplaceBeforeTheFindIsRefusedByTheGate() {
        val refusal = RuntimeBridge.prerequisiteRefusal("grep_replace_all")
        assertNotNull("a replace with nothing found must not run", refusal)
        assertTrue(refusal!!.contains("find"))
    }

    @Test
    fun afterARealFindTheReplaceIsAllowed() {
        PrereqLedger.record("find", "grep_search", "3 matches in Main.kt")
        assertNull(RuntimeBridge.prerequisiteRefusal("grep_replace_all"))
    }

    @Test
    fun aFailedFindBlocksTheReplaceAndSaysSo() {
        PrereqLedger.recordFailure("find", "grep_search", "no such directory")
        val refusal = RuntimeBridge.prerequisiteRefusal("grep_replace_all")
        assertNotNull(refusal)
        assertTrue(refusal!!.contains("failed"))
        assertTrue(refusal.contains("no such directory"))
    }

    @Test
    fun toolsWithoutPrerequisitesAreNeverGated() {
        assertNull(RuntimeBridge.prerequisiteRefusal("grep_search"))
        assertNull(RuntimeBridge.prerequisiteRefusal("read_file"))
        assertNull(RuntimeBridge.prerequisiteRefusal("web_search"))
    }

    @Test
    fun unzippingRequiresTheArchiveToBeInspected() {
        assertNotNull(RuntimeBridge.prerequisiteRefusal("zip_extract"))
        PrereqLedger.record("archive", "zip_list", "12 entries, 3MB, safe")
        assertNull(RuntimeBridge.prerequisiteRefusal("zip_extract"))
    }

    // ── Phase 7: health is a tie-break, not a decision ───────────────────

    @Test
    fun aHealthyCandidateIsPreferredWhenBothAreSuitable() {
        PrereqLedger.clear()
        repeat(2) { ToolHealth.recordFailure("grep_replace_all") }
        val chosen = ToolTaskCoverage.choose(
            listOf("grep_replace_all", "multi_replace_string_in_file"),
            offered = setOf("grep_replace_all", "multi_replace_string_in_file"),
        )
        assertEquals("multi_replace_string_in_file", chosen)
        ToolHealth.clear()
    }

    @Test
    fun healthNeverRemovesACandidate() {
        repeat(3) { ToolHealth.recordFailure("grep_search") }
        val chosen = ToolTaskCoverage.choose(listOf("grep_search"), offered = setOf("grep_search"))
        assertEquals("grep_search", chosen)
        ToolHealth.clear()
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "grep_search"))
    }

    @Test
    fun healthCannotMakeAnUnauthorisedToolAvailable() {
        // Health is a scoring signal; the registry still decides what a mode exposes.
        assertFalse(ToolRegistry.allows(ChatMode.ASK, "grep_replace_all"))
        val offered = ToolRegistry.exposedNames(ChatMode.ASK).toSet()
        val report = ToolTaskCoverage.report("replace the token", ChatMode.ASK, offered)
        assertFalse(report.executable)
    }
}
