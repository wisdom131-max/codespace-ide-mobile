package com.codespace.ide.security

import com.codespace.ide.agent.AgentTools
import com.codespace.ide.agent.ExecutionBoundaryScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.3 — the guard against a second tool-execution route, run against the REAL sources
 * and the REAL registry, and proved to fail when a bypass is introduced.
 */
class Stage83ExecutionGateTest {

    private val sourceRoot: File = run {
        val direct = File("src/main/java")
        if (direct.isDirectory) direct else File("app/src/main/java")
    }

    private fun productionSources(): Map<String, String> {
        assertTrue("production sources not found at " + sourceRoot.absolutePath, sourceRoot.isDirectory)
        return sourceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .associate { it.name to it.readText() }
    }

    @Test
    fun `every tool the registry advertises goes through the one gate`() {
        val report = ExecutionBoundaryScan.scan(productionSources(), AgentTools.toolNames())
        assertTrue(
            "the execution boundary is broken: " + report.violations.joinToString("; ") + " (" + report.summary() + ")",
            report.clean,
        )
        assertTrue("the gate body must stay private", report.bodyIsPrivate)
        assertTrue("the gate body should be called from the gate file", report.bodyCallSites >= 1)
        assertTrue("the registry must advertise tools", report.registryNames > 100)
    }

    @Test
    fun `a new file that calls a tool body directly is caught and named`() {
        val sources = productionSources().toMutableMap()
        sources["ToolShortcut.kt"] = """
            package com.codespace.ide.agent
            object ToolShortcut {
                suspend fun quickRead(path: String, ctx: android.content.Context): String =
                    AgentTools.executeToolBody(path, org.json.JSONObject(), ctx)
            }
        """.trimIndent()
        val report = ExecutionBoundaryScan.scan(sources, AgentTools.toolNames())
        assertFalse("a direct body call must not pass the guard", report.clean)
        assertTrue(
            "the violation must name the offending file, was: " + report.violations,
            report.violations.any { it.contains("ToolShortcut.kt") },
        )
    }

    @Test
    fun `a second executeTool declared elsewhere is caught`() {
        val sources = productionSources().toMutableMap()
        sources["ParallelGate.kt"] = """
            package com.codespace.ide.agent
            object ParallelGate {
                suspend fun executeTool(name: String): String = "ok"
            }
        """.trimIndent()
        val report = ExecutionBoundaryScan.scan(sources, AgentTools.toolNames())
        assertFalse(report.clean)
        assertTrue(report.violations.any { it.contains("ParallelGate.kt") && it.contains("second execution gate") })
    }

    @Test
    fun `a tool registered without a dispatch arm is caught`() {
        val names = AgentTools.toolNames() + listOf("not_wired_anywhere")
        val report = ExecutionBoundaryScan.scan(productionSources(), names)
        assertFalse("an advertised tool with no arm must fail the guard", report.clean)
        assertTrue(report.uncovered.contains("not_wired_anywhere"))
    }

    @Test
    fun `a dispatch arm nobody can reach is caught`() {
        val names = AgentTools.toolNames().filter { it != "read_file" }
        val report = ExecutionBoundaryScan.scan(productionSources(), names)
        assertFalse(report.clean)
        assertTrue("read_file still has an arm, so it is unadvertised", report.unadvertised.contains("read_file"))
    }

    @Test
    fun `the guard reads the real tool count rather than a hardcoded list`() {
        val report = ExecutionBoundaryScan.scan(productionSources(), AgentTools.toolNames())
        assertEquals(AgentTools.toolNames().size, report.registryNames)
        assertTrue("dispatch arms should be found in the gate file", report.dispatchArms > 100)
    }
}
