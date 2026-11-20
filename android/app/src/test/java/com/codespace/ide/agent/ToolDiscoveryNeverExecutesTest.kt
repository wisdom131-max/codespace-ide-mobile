package com.codespace.ide.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PROJECT 5 (advanced tool intelligence): discovery must never execute.
 *
 * `tool_search` and the `/tools` catalogue exist so the model can find a tool without paying for its
 * schema. If discovery ever reached an execution path it would run a body the user never asked for,
 * so this guard reads the dispatch source and fails the build if the discovery branch mentions any
 * execution entry point.
 *
 * This is a source-level architectural guard, deliberately: the failure it prevents is a future edit
 * that looks harmless, and a runtime test of today's behaviour would not catch that edit.
 */
class ToolDiscoveryNeverExecutesTest {

    private fun sourceFile(): File = listOf(
        File("src/main/java/com/codespace/ide/agent/AgentTools.kt"),
        File("app/src/main/java/com/codespace/ide/agent/AgentTools.kt"),
    ).firstOrNull { it.isFile }
        ?: error("AgentTools.kt not found from " + File(".").absolutePath)

    private fun block(toolName: String): String {
        val text = sourceFile().readText()
        val start = text.indexOf("\"" + toolName + "\" ->")
        assertTrue("dispatch branch for $toolName exists", start >= 0)
        val rest = text.substring(start + toolName.length + 5)
        val next = Regex("\\n\\s{16}\"[a-z0-9_]+\" ->").find(rest)
        return if (next == null) rest else rest.substring(0, next.range.first)
    }

    @Test fun `tool_search never reaches an execution path`() {
        val body = block("tool_search")
        for (forbidden in listOf("executeToolBody(", "executeGated(", "dispatchGuard.run", "ToolDispatchGuard.run")) {
            assertFalse("tool_search must not call $forbidden", body.contains(forbidden))
        }
    }

    @Test fun `the tools catalogue never reaches an execution path`() {
        for (name in listOf("list_tools", "tool_catalogue")) {
            val text = sourceFile().readText()
            if (!text.contains("\"" + name + "\" ->")) continue
            val body = block(name)
            for (forbidden in listOf("executeToolBody(", "executeGated(", "dispatchGuard.run")) {
                assertFalse("$name must not call $forbidden", body.contains(forbidden))
            }
        }
    }

    @Test fun `the discovery branch delegates instead of growing a body`() {
        // Discovery belongs in a helper. If this branch ever grows past a few lines it is a sign
        // someone is building results inline, which is how an execution path creeps back in.
        val body = block("tool_search")
        val lines = body.lines().count { it.isNotBlank() }
        assertTrue("tool_search branch stays a delegation (" + lines + " lines)", lines <= 10)
    }
}
