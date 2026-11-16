package com.codespace.ide.chat.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT 4.1: coverage of a request, and memory of a tool that went bad.
 *
 * These run against the real registry rather than a fixture, because the claim being tested is about
 * the tools the product actually ships.
 */
class ToolCapabilityPlanTest {

    @Test
    fun aRenameNeedsTwoCapabilitiesAndBothAreNamed() {
        val a = ToolCapabilityPlan.assess(
            "rename this class everywhere",
            ChatMode.AGENT,
            offered = ToolRegistry.exposedNames(ChatMode.AGENT).toSet(),
        )
        val ids = a.needs.map { it.id }
        assertTrue("a project-wide rename needs find and replace, got $ids", ids.containsAll(listOf("find", "replace")))
        assertTrue("everything needed is offered in Agent", a.covered)
        assertEquals("", ToolCapabilityPlan.note(a))
    }

    @Test
    fun theAskModeGapIsNamedAsAModeGapNotAMissingTool() {
        // Ask cannot write. The same request must say so, and say where it CAN be done.
        val a = ToolCapabilityPlan.assess(
            "rename this class everywhere",
            ChatMode.ASK,
            offered = ToolRegistry.exposedNames(ChatMode.ASK).toSet(),
        )
        val replace = a.findings.first { it.need.id == "replace" }
        assertTrue(
            "the gap must name the mode, not deny the tool exists: ${replace.state}",
            replace.state == ToolCapabilityPlan.State.OTHER_MODE,
        )
        assertNotNull(replace.mode)
        assertFalse(a.covered)
        assertTrue(ToolCapabilityPlan.note(a).contains("rename", ignoreCase = false) || ToolCapabilityPlan.note(a).contains("changing"))
    }

    @Test
    fun aCapabilityNobodyImplementsIsReportedAsMissing() {
        val a = ToolCapabilityPlan.assess(
            "search the web for the latest release notes",
            ChatMode.ASK,
            // Nothing offered, and this fake registry says a tool exists but no mode exposes it.
            offered = emptySet(),
            modesWith = { emptyList() },
            exists = { true },
        )
        assertTrue(
            "a tool no mode exposes is reported as a mode gap before it is called impossible",
            a.findings.all { it.state == ToolCapabilityPlan.State.OTHER_MODE },
        )
        val missing = ToolCapabilityPlan.assess(
            "run the tests please",
            ChatMode.ASK,
            offered = emptySet(),
            modesWith = { emptyList() },
            exists = { false },
        )
        assertTrue(missing.findings.any { it.state == ToolCapabilityPlan.State.MISSING })
        assertTrue(ToolCapabilityPlan.note(missing).contains("nothing here can do"))
    }

    @Test
    fun aToolThatExistsButWasNotOfferedIsLoadableNotMissing() {
        val all = ToolRegistry.exposedNames(ChatMode.AGENT).toSet()
        val a = ToolCapabilityPlan.assess("fix the failing tests", ChatMode.AGENT, offered = all - "runTests")
        val test = a.findings.first { it.need.id == "test" }
        assertEquals(ToolCapabilityPlan.State.LOADABLE, test.state)
        assertTrue(ToolCapabilityPlan.note(a).contains("load"))
    }

    @Test
    fun healthDemotesOnlyAfterTheSecondConsecutiveFailure() {
        ToolHealth.clear()
        assertEquals(0, ToolHealth.adjustment("read_file"))
        ToolHealth.recordFailure("read_file")
        assertEquals("one failure is a coincidence", 0, ToolHealth.adjustment("read_file"))
        ToolHealth.recordFailure("read_file")
        assertEquals(-ToolHealth.DEMOTION, ToolHealth.adjustment("read_file"))
        ToolHealth.recordFailure("read_file")
        assertEquals("a third failure does not demote further",
            -ToolHealth.DEMOTION, ToolHealth.adjustment("read_file"))
        ToolHealth.recordSuccess("read_file")
        assertEquals("one success clears the streak", 0, ToolHealth.adjustment("read_file"))
        ToolHealth.clear()
    }

    @Test
    fun aDemotedToolStillExistsAndIsStillAllowed() {
        ToolHealth.clear()
        repeat(3) { ToolHealth.recordFailure("grep_search") }
        assertTrue(ToolHealth.demoted().contains("grep_search"))
        // Demotion reorders proposals. It must not remove the tool or change a permission decision.
        assertNotNull(ToolRegistry.spec("grep_search"))
        assertTrue(ToolRegistry.allows(ChatMode.AGENT, "grep_search"))
        ToolHealth.clear()
    }

    @Test
    fun healthIsBoundedSoALongSessionCannotGrowIt() {
        ToolHealth.clear()
        repeat(200) { ToolHealth.recordFailure("tool_$it") }
        assertTrue("the health map must stay small: ${ToolHealth.demoted().size}", ToolHealth.demoted().size <= 40)
        ToolHealth.clear()
    }

    @Test
    fun aBrokenToolFallsBehindAWorkingOneInTheRealRanking() {
        ToolHealth.clear()
        val fresh = ToolRelevance.rank(ChatMode.AGENT, "find every use of login")
        assertTrue(fresh.contains("grep_search"))
        repeat(2) { ToolHealth.recordFailure("grep_search") }
        val after = ToolRelevance.rank(ChatMode.AGENT, "find every use of login")
        val before = fresh.indexOf("grep_search")
        val now = after.indexOf("grep_search")
        assertTrue("a demoted tool must not rank better than it did ($before -> $now)", now == -1 || now >= before)
        ToolHealth.clear()
    }
}
