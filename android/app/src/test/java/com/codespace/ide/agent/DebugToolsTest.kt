package com.codespace.ide.agent

import com.codespace.ide.debug.DebugBreakpoint
import com.codespace.ide.debug.DebugSession
import com.codespace.ide.debug.DebugState
import com.codespace.ide.debug.DebugStackFrame
import com.codespace.ide.debug.DebugVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Debugger tools, at the level that can be asserted without a live adapter: the renderers, the
 * bounds, and the honest answers when there is no session. The adapter calls themselves are
 * injected, so nothing here needs a debuggee.
 */
class DebugToolsTest {

    private fun session(id: String = "s1", state: DebugState = DebugState.PAUSED) = DebugSession(
        id = id, language = com.codespace.ide.domain.Language.PYTHON, filePath = "main.py",
        providerId = "python-dap", state = state, pid = 4242,
    )

    private fun frame(i: Int, active: Boolean = false) =
        DebugStackFrame(function = "fn$i", file = "/p/main.py", line = i + 1, active = active, frameId = i)

    // --- sessions ---------------------------------------------------------------

    @Test
    fun noSessionsSaysSoInsteadOfPretending() {
        val out = DebugTools.renderSessions(emptyList())
        assertTrue(out, out.contains("No debugging session"))
        assertFalse(out, out.contains("RUNNING"))
    }

    @Test
    fun sessionsCarryLanguageStateAndPid() {
        val out = DebugTools.renderSessions(listOf(session()))
        assertTrue(out, out.contains("s1"))
        assertTrue(out, out.contains("PAUSED"))
        assertTrue(out, out.contains("4242"))
        assertTrue(out, out.contains("main.py"))
    }

    @Test
    fun theSessionLimitIsBoundedAndSaysWhatItHid() {
        val many = (0 until 12).map { session("s$it") }
        val out = DebugTools.renderSessions(many)
        assertTrue(out.length < 40000)
    }

    // --- stack ------------------------------------------------------------------

    @Test
    fun theActiveFrameIsMarked() {
        val out = DebugTools.renderStack(session(), listOf(frame(0, active = true), frame(1)))
        assertTrue(out, out.contains("fn0"))
        assertTrue(out, out.contains("main.py"))
    }

    @Test
    fun anEmptyStackExplainsThatItIsNotRunningOrNotPaused() {
        val out = DebugTools.renderStack(session(), emptyList())
        assertTrue(out, out.lowercase().contains("paused"))
    }

    @Test
    fun stackOutputIsBounded() {
        val out = DebugTools.renderStack(session(), (0 until 80).map { frame(it) })
        assertTrue("bounded: " + out.length, out.length < 40000)
        assertTrue(out, out.length < 4000)
    }

    // --- variables --------------------------------------------------------------

    @Test
    fun variablesRenderNameTypeAndValue() {
        val v = DebugVariable(name = "total", type = "int", value = "42")
        val out = DebugTools.renderVariables(session(), listOf(v), null)
        assertTrue(out, out.contains("total"))
        assertTrue(out, out.contains("int"))
        assertTrue(out, out.contains("42"))
    }

    @Test
    fun aNameFilterNarrowsTheList() {
        val vars = listOf(
            DebugVariable("total", "int", "42"),
            DebugVariable("label", "str", "hi"),
        )
        val out = DebugTools.renderVariables(session(), vars, "tot")
        assertTrue(out, out.contains("total"))
        assertFalse(out, out.contains("label"))
    }

    @Test
    fun aFilterThatMatchesNothingSaysSo() {
        val out = DebugTools.renderVariables(session(), listOf(DebugVariable("a", "int", "1")), "zzz")
        assertTrue(out, out.contains("No variable"))
    }

    @Test
    fun variableOutputIsBounded() {
        val vars = (0 until 200).map { DebugVariable("v$it", "int", it.toString()) }
        val out = DebugTools.renderVariables(session(), vars, null)
        assertTrue("bounded: " + out.length, out.length < 40000)
        assertTrue(out, out.length < 5000)
    }

    // --- breakpoints ------------------------------------------------------------

    @Test
    fun breakpointsGroupByFileWithLineNumbers() {
        val map = mapOf(
            "main.py" to mutableListOf(DebugBreakpoint(filePath = "main.py", line = 3, enabled = true), DebugBreakpoint(filePath = "main.py", line = 9, enabled = false)),
        )
        val out = DebugTools.renderBreakpoints(map)
        assertTrue(out, out.contains("Breakpoints"))
        assertTrue(out, out.contains("main.py:4") && out.contains("main.py:10"))
    }

    @Test
    fun noBreakpointsIsAnAnswerNotAnError() {
        val out = DebugTools.renderBreakpoints(emptyMap())
        assertTrue(out, out.contains("No breakpoint"))
    }

    // --- actions that need a session ---------------------------------------------

    @Test
    fun steppingWithoutASessionExplainsTheMissingSession() {
        val out = DebugTools.step("over", "nope")
        assertTrue(out, out.contains("No debugging session"))
    }

    @Test
    fun evaluatingWithoutASessionExplainsTheMissingSession() {
        val out = DebugTools.evaluate("1 + 1", "nope")
        assertTrue(out, out.contains("No debugging session"))
    }

    @Test
    fun steppingRejectsAnUnknownAction() {
        val out = DebugTools.step("sideways", "s1")
        assertTrue(out, out.isNotBlank())
    }

    @Test
    fun anUnknownSessionIsNamedRatherThanSilentlyEmpty() {
        val out = DebugTools.stack("ghost")
        assertTrue(out, out.contains("No debugging session"))
    }

    @Test
    fun everyDebugToolHasARealExecutorBranch() {
        val names = listOf(
            "debug_sessions", "debug_stack", "debug_variables", "debug_attach",
            "debug_breakpoints", "debug_step", "debug_evaluate",
        )
        val registered = AgentTools.toolNames()
        names.forEach { assertEquals("$it must be executable", true, registered.contains(it)) }
    }
}
