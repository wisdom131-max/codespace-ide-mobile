package com.codespace.ide.debug

import android.content.Context
import android.util.Log

/**
 * P26-2b: DebugAdapter — abstraction over DAP-compatible debug adapters.
 *
 * Implementations:
 *   - PythonDAPAdapter — debugpy (Python DAP)
 *   - LegacyDebugAdapter — wraps existing DebugProvider (pdb, node inspect, etc.)
 *
 * UDM asks each adapter canDebug(), picks the best one, calls launch().
 */
interface DebugAdapter {
    val id: String
    val displayName: String

    /** Whether this adapter can handle the given language/file. */
    fun canDebug(language: com.codespace.ide.domain.Language, filePath: String): Boolean

    /**
     * Launch a debug session. Returns true if successfully started.
     * Implementations must call onOutput/onPaused/onStopped asynchronously as the session runs.
     */
    fun launch(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean

    fun stop(session: DebugSession)
    fun pause(session: DebugSession)
    fun resume(session: DebugSession)
    fun stepOver(session: DebugSession)
    fun stepInto(session: DebugSession)
    fun stepOut(session: DebugSession)
    fun evaluate(session: DebugSession, expression: String, frameId: Int = 0): String?

    /** Returns DAP capabilities negotiated during initialize. Null if not a real DAP adapter. */
    fun capabilities(): DAPCapabilities? = null

    /**
     * P32-BREAKPOINT-FIX: Send updated breakpoints to the adapter during a running session.
     * Called by UDM when breakpoints change (add/remove/toggle) while a debug session is active.
     * Legacy adapters (pdb, terminal) ignore this — they don't support live breakpoint updates.
     * Returns true if breakpoints were successfully sent.
     */
    fun sendBreakpoints(session: DebugSession, breakpoints: List<DebugBreakpoint>): Boolean = false

    // P27-AUDIT: Fetch child variables by DAP variablesReference (> 0 means expandable)
    fun getVariables(session: DebugSession, variablesReference: Int): List<DebugVariable> = emptyList()
}

// ── LegacyDebugAdapter ───────────────────────────────────────────────────────

/**
 * P26-2b: Wraps an existing DebugProvider as a DAP-compatible DebugAdapter.
 * Allows UDM to always use the DebugAdapter interface, regardless of whether
 * a real DAP adapter (debugpy, js-debug, etc.) is available.
 */
class LegacyDebugAdapter(private val provider: DebugProvider) : DebugAdapter {

    override val id = "legacy:${provider.id}"
    override val displayName = "${provider.displayName} (legacy)"

    override fun canDebug(language: com.codespace.ide.domain.Language, filePath: String) =
        provider.canDebug(language, filePath)

    override fun launch(
        context: Context,
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
        onStopped: (exitCode: Int) -> Unit,
    ): Boolean = provider.launch(session, breakpoints, onOutput, onPaused)

    override fun stop(session: DebugSession) = provider.stop(session)
    override fun pause(session: DebugSession) = provider.pause(session)
    override fun resume(session: DebugSession) = provider.resume(session)
    override fun stepOver(session: DebugSession) = provider.stepOver(session)
    override fun stepInto(session: DebugSession) = provider.stepInto(session)
    override fun stepOut(session: DebugSession) = provider.stepOut(session)
    override fun evaluate(session: DebugSession, expression: String, frameId: Int) =
        provider.evaluate(session, expression)

    override fun capabilities(): DAPCapabilities? = null

    // P32-BREAKPOINT-FIX: Legacy providers don't support live breakpoint updates.
    override fun sendBreakpoints(session: DebugSession, breakpoints: List<DebugBreakpoint>): Boolean = false

    // P27-AUDIT: Legacy providers don't support variable expansion.
    override fun getVariables(session: DebugSession, variablesReference: Int): List<DebugVariable> = emptyList()
}
