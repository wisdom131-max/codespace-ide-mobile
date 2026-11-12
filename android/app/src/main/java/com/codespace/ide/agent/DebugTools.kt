package com.codespace.ide.agent

import com.codespace.ide.debug.DebugBreakpoint
import com.codespace.ide.debug.DebugSession
import com.codespace.ide.debug.DebugStackFrame
import com.codespace.ide.debug.DebugVariable
import com.codespace.ide.debug.UniversalDebugManager

/**
 * COPILOT DEBUGGER TOOLS (2026-11-12).
 *
 * These are the READ/STEP surface of the existing debugger, not a second debugger: every call goes
 * through [UniversalDebugManager], which already owns the sessions, the adapters, the breakpoint
 * store and the paused state. Nothing here spawns a process or speaks DAP itself.
 *
 * The formatting half is deliberately pure (`render*`) so the bounds, the ordering and the wording
 * are asserted by tests without an Android device or a live adapter.
 */
object DebugTools {

    private const val MAX_FRAMES = 40
    private const val MAX_VARS = 60
    private const val MAX_BREAKPOINTS = 200

    // ---------------------------------------------------------------- sessions

    fun sessions(): String {
        val list = UniversalDebugManager.sessionsSnapshot()
        return renderSessions(list)
    }

    fun renderSessions(list: List<DebugSession>): String {
        if (list.isEmpty()) {
            return "No debugging session is running. Start one with the debug button (F5) or " +
                "debug_attach for a process that already started its debug listener."
        }
        val sb = StringBuilder("Debug sessions (" + list.size + "):\n")
        for (s in list) {
            val f = s.filePath.substringAfterLast('/')
            sb.append("  ").append(s.id).append("  ")
                .append(s.language.name.lowercase()).append("  ")
                .append(s.state.name).append("  ")
                .append(f)
            s.pid?.let { sb.append("  pid=").append(it) }
            sb.append('\n')
        }
        sb.append("Use debug_stack and debug_variables with the id shown above.")
        return sb.toString()
    }

    // ------------------------------------------------------------------- stack

    fun stack(sessionId: String?): String {
        val session = session(sessionId) ?: return noSession(sessionId)
        return renderStack(session, UniversalDebugManager.stackFor(session.id))
    }

    fun renderStack(session: DebugSession, frames: List<DebugStackFrame>): String {
        if (frames.isEmpty()) {
            return "Session " + session.id + " is " + session.state.name +
                " and has no paused frames. A stack exists only while execution is paused " +
                "(a breakpoint hit, or debug_step paused it)."
        }
        val sb = StringBuilder("Stack for " + session.id + " (" + session.state.name + "):\n")
        for ((i, f) in frames.take(MAX_FRAMES).withIndex()) {
            sb.append("  #").append(i).append(' ').append(f.function)
                .append("  ").append(f.file.substringAfterLast('/')).append(':').append(f.line)
            if (f.active) sb.append("  <- current")
            sb.append('\n')
        }
        if (frames.size > MAX_FRAMES) {
            sb.append("  ... ").append(frames.size - MAX_FRAMES).append(" deeper frames not shown.")
        }
        return sb.toString().trimEnd()
    }

    // --------------------------------------------------------------- variables

    fun variables(sessionId: String?, nameFilter: String?): String {
        val session = session(sessionId) ?: return noSession(sessionId)
        return renderVariables(session, UniversalDebugManager.varsFor(session.id), nameFilter)
    }

    fun renderVariables(session: DebugSession, vars: List<DebugVariable>, nameFilter: String?): String {
        if (vars.isEmpty()) {
            return "Session " + session.id + " is " + session.state.name +
                ". No variables are available: they are captured when execution pauses."
        }
        val needle = nameFilter?.trim()?.lowercase().orEmpty()
        val shown = if (needle.isEmpty()) vars else vars.filter { it.name.lowercase().contains(needle) }
        if (shown.isEmpty()) {
            return "No variable in " + session.id + " matches \"" + nameFilter + "\". " +
                vars.size + " variable(s) exist; call debug_variables without a filter to see them."
        }
        val sb = StringBuilder("Variables for " + session.id + " (" + shown.size +
            if (needle.isEmpty()) "):\n" else " of " + vars.size + ", filtered):\n")
        for (v in shown.take(MAX_VARS)) {
            sb.append("  ").append(v.name)
            if (v.type.isNotBlank()) sb.append(": ").append(v.type)
            sb.append(" = ").append(v.value.take(160))
            if (v.expandable) sb.append("  [+]")
            sb.append('\n')
        }
        if (shown.size > MAX_VARS) {
            sb.append("  ... ").append(shown.size - MAX_VARS).append(" more. Narrow with filter=.")
        }
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------------ attach

    fun attach(sessionId: String): String {
        val session = UniversalDebugManager.sessionById(sessionId)
            ?: return "No session '" + sessionId + "'. Call debug_sessions for the live ids."
        val adapter = UniversalDebugManager.adapterFor(session.id)
            ?: return "Session " + session.id + " has no debug adapter attached, so it cannot be " +
                "stepped or inspected. Start the session again from the debug panel."
        return "Attached view of " + session.id + ": " + session.language.name.lowercase() +
            ", state " + session.state.name + ", adapter " + adapter.javaClass.simpleName +
            ", file " + session.filePath.substringAfterLast('/') +
            ". Use debug_stack / debug_variables / debug_step."
    }

    // ------------------------------------------------------------- breakpoints

    fun breakpoints(action: String, filePath: String?, line: Int): String {
        when (action.lowercase()) {
            "list" -> return renderBreakpoints(UniversalDebugManager.breakpointsSnapshot())
            "toggle" -> {
                if (filePath.isNullOrBlank()) {
                    return "breakpoints toggle needs file= (the file to place or remove the breakpoint in)."
                }
                if (line <= 0) return "breakpoints toggle needs line= (a 1-based line number)."
                UniversalDebugManager.toggleBreakpoint(filePath, line - 1)
                return "Toggled the breakpoint at " + filePath.substringAfterLast('/') + ":" + line +
                    ".\n" + renderBreakpoints(UniversalDebugManager.breakpointsSnapshot())
            }
            else -> return "action must be 'list' or 'toggle'."
        }
    }

    fun renderBreakpoints(map: Map<String, List<DebugBreakpoint>>): String {
        val all = map.entries.sortedBy { it.key }.flatMap { (file, list) -> list.map { file to it } }
        if (all.isEmpty()) return "No breakpoints are set."
        val sb = StringBuilder("Breakpoints (" + all.size + "):\n")
        for ((file, bp) in all.take(MAX_BREAKPOINTS)) {
            sb.append("  ").append(file.substringAfterLast('/')).append(':').append(bp.line + 1)
            if (!bp.enabled) sb.append(" (disabled)")
            if (!bp.verified) sb.append(" (unverified)")
            bp.condition?.takeIf { it.isNotBlank() }?.let { sb.append(" if ").append(it.take(80)) }
            bp.logMessage?.takeIf { it.isNotBlank() }?.let { sb.append(" log \"").append(it.take(80)).append('"') }
            sb.append('\n')
        }
        if (all.size > MAX_BREAKPOINTS) {
            sb.append("  ... ").append(all.size - MAX_BREAKPOINTS).append(" more not shown.")
        }
        return sb.toString().trimEnd()
    }

    // -------------------------------------------------------------------- step

    fun step(action: String, sessionId: String?): String {
        val session = session(sessionId) ?: return noSession(sessionId)
        val a = action.lowercase()
        val paused = session.state == com.codespace.ide.debug.DebugState.PAUSED ||
            session.state == com.codespace.ide.debug.DebugState.STEPPING
        if (!paused) {
            return "Session " + session.id + " is " + session.state.name +
                ". Stepping needs a paused session: hit a breakpoint first, or use pause."
        }
        return when (a) {
            "over" -> { UniversalDebugManager.stepOver(session.id); "Stepped over in " + session.id + "." }
            "into" -> { UniversalDebugManager.stepInto(session.id); "Stepped into " + session.id + "." }
            "out" -> { UniversalDebugManager.stepOut(session.id); "Stepped out in " + session.id + "." }
            "pause" -> { UniversalDebugManager.pauseSession(session.id); "Pause requested for " + session.id + "." }
            "continue", "resume" -> {
                UniversalDebugManager.resumeSession(session.id); "Resumed " + session.id + "."
            }
            else -> "action must be one of over, into, out, pause, continue."
        }
    }

    // ---------------------------------------------------------------- evaluate

    fun evaluate(expression: String, sessionId: String?): String {
        if (expression.isBlank()) return "evaluate needs expression= (e.g. 'user.name' or 'items.size()')."
        val session = session(sessionId) ?: return noSession(sessionId)
        if (session.state != com.codespace.ide.debug.DebugState.PAUSED) {
            return "Session " + session.id + " is " + session.state.name +
                ". Expression evaluation needs a paused session."
        }
        val result = UniversalDebugManager.evaluateExpression(session.id, expression)
            ?: return "The adapter for " + session.id + " did not return a value for \"" +
                expression.take(120) + "\". Not every adapter supports evaluation."
        // Evaluator output is program output, not instructions: label it and bound it.
        return "Result of \"" + expression.take(120) + "\" in " + session.id + ":\n" +
            "[untrusted debug value] " + result.take(2000)
    }

    // ------------------------------------------------------------------ shared

    private fun session(sessionId: String?): DebugSession? {
        val id = sessionId?.trim().orEmpty()
        if (id.isNotEmpty()) return UniversalDebugManager.sessionById(id)
        // No id given: the only sensible default is the single live session.
        val all = UniversalDebugManager.sessionsSnapshot()
        return if (all.size == 1) all.first() else null
    }

    private fun noSession(sessionId: String?): String {
        val all = UniversalDebugManager.sessionsSnapshot()
        if (all.isEmpty()) {
            return "No debugging session is running, so there is nothing to inspect. " +
                "Call debug_sessions after starting a debug run."
        }
        return "Several sessions are live (" + all.joinToString(", ") { it.id } +
            "). Pass session= with one of those ids" +
            if (sessionId.isNullOrBlank()) "." else " ('" + sessionId + "' is not one of them)."
    }
}
