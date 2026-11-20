package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P8.3: an architectural guard, not a behavioural one.
 *
 * `AgentTools.executeTool` is the only door a tool body may be entered through, because that door
 * owns the plan gate, the dispatch guard, idempotency, approval and the diagnostics. A second door
 * would silently skip all of it. This test reads the source tree and fails if anyone opens one.
 *
 * It is deliberately a source scan: a new execution path is a new file or a new call site, and both
 * are visible as text long before any test could observe them at runtime.
 */
class SingleExecutionGateGuardTest {

    private fun sources(): List<File> {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
        )
        val root = roots.firstOrNull { it.isDirectory } ?: error("no source root found from " + File(".").absolutePath)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun filesContaining(token: String): List<String> =
        sources().filter { it.readText().contains(token) }.map { it.name }.sorted()

    /** The class declaration names the type too; a guard cares about construction sites. */
    private fun constructionSites(token: String): List<String> =
        sources().filter { it.readText().contains(token) && it.name != "ToolDispatchGuard.kt" }
            .map { it.name }.sorted()

    @Test fun `tool bodies are entered only from AgentTools`() {
        val callers = filesContaining("executeToolBody(")
        assertEquals(
            "executeToolBody must only be called inside AgentTools.kt (one execution gate)",
            listOf("AgentTools.kt"),
            callers,
        )
    }

    @Test fun `the dispatch guard is constructed only by AgentTools`() {
        assertEquals(
            "ToolDispatchGuard must be built by AgentTools.dispatchGuard, nowhere else",
            listOf("AgentTools.kt"),
            constructionSites("ToolDispatchGuard("),
        )
    }

    @Test fun `gated execution is not called from outside AgentTools`() {
        assertEquals(
            "executeGated is the gate's body runner and must not be reachable elsewhere",
            listOf("AgentTools.kt"),
            filesContaining("executeGated("),
        )
    }

    @Test fun `the gate still reports its own lifecycle`() {
        val tools = sources().first { it.name == "AgentTools.kt" }.readText()
        assertTrue("executeTool must wrap the inner call in diagnostics", tools.contains("CopilotDiag.trace("))
        assertTrue(tools.contains("executeGated("))
        assertTrue(tools.contains("dispatchGuard("))
    }
}
