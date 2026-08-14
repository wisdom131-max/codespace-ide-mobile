package com.codespace.ide.debug

import android.content.Context
import android.util.Log
import com.codespace.ide.domain.Language
import com.codespace.ide.editor.FileIndexer
import com.codespace.ide.diagnostics.AppOutputLog
import com.codespace.ide.data.NotificationStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 23-4: Universal Debug Manager — the shared backend for both the
 * Activity Bar Debugger and the Terminal Panel Debugger.
 *
 * Responsibilities:
 * - Detect language and runtime
 * - Select the appropriate debug provider
 * - Manage debug sessions
 * - Route requests (pause, resume, step, etc.)
 *
 * Debug Button -> UniversalDebugManager -> Provider Selection -> Launch Correct Debug Provider
 */

/** A debug session — one active debugging instance. */
data class DebugSession(
    val id: String,
    val language: Language,
    val filePath: String,
    val providerId: String,
    var state: DebugState = DebugState.IDLE,
    var pid: Int? = null,
)

enum class DebugState {
    IDLE, STARTING, RUNNING, PAUSED, STEPPING, STOPPING, STOPPED, CRASHED, FAILED, ERROR
}

/**
 * P27-3: State machine validator — enforces valid debug state transitions.
 * Rejects illegal transitions (e.g., STOPPED → STEP, IDLE → CONTINUE) with a log warning.
 */
private val validTransitions: Map<DebugState, Set<DebugState>> = mapOf(
    DebugState.IDLE      to setOf(DebugState.STARTING),
    DebugState.STARTING  to setOf(DebugState.RUNNING, DebugState.FAILED, DebugState.STOPPED, DebugState.ERROR),
    DebugState.RUNNING   to setOf(DebugState.PAUSED, DebugState.CRASHED, DebugState.STOPPING, DebugState.STEPPING, DebugState.STOPPED),
    DebugState.PAUSED    to setOf(DebugState.RUNNING, DebugState.STEPPING, DebugState.STOPPING, DebugState.STOPPED),
    DebugState.STEPPING  to setOf(DebugState.PAUSED, DebugState.RUNNING, DebugState.CRASHED, DebugState.STOPPING, DebugState.STOPPED),
    DebugState.STOPPING  to setOf(DebugState.STOPPED, DebugState.CRASHED),
    DebugState.STOPPED   to setOf(DebugState.IDLE),  // via restart
    DebugState.CRASHED   to setOf(DebugState.IDLE),  // via restart
    DebugState.FAILED    to setOf(DebugState.IDLE),  // via restart
    DebugState.ERROR     to setOf(DebugState.IDLE, DebugState.STARTING),
    // Any state can transition to STOPPING (from stop()) or STOPPED
)

/** P27-3: Validate and execute a state transition. Returns true if allowed. */
fun isValidTransition(from: DebugState, to: DebugState): Boolean {
    if (from == to) return true // same state is always ok
    // STOPPING and STOPPED are always reachable (force stop from any state)
    if (to == DebugState.STOPPING || to == DebugState.STOPPED) return true
    return validTransitions[from]?.contains(to) == true
}

/** A variable in the current debug scope. */
data class DebugVariable(
    val name: String,
    val type: String,
    val value: String,
    val depth: Int = 0,
    val expandable: Boolean = false,
)

/** A frame in the call stack. */
data class DebugStackFrame(
    val function: String,
    val file: String,
    val line: Int,
    val active: Boolean = false,
    val frameId: Int = 0,  // P27-2: DAP frame ID for evaluate operations
)

/** A breakpoint — line breakpoints, conditional, log points. */
data class DebugBreakpoint(
    val filePath: String,
    val line: Int,          // 0-based
    val condition: String? = null,
    val logMessage: String? = null,
    val enabled: Boolean = true,
    val hitCount: Int = 0,
)

/** A watch expression being evaluated during debugging. */
data class DebugWatch(
    val id: Int,
    val expression: String,
    val value: String = "—",
)

/** Provider interface — each language/runtime implements this. */
interface DebugProvider {
    val id: String
    val displayName: String
    val supportedLanguages: Set<Language>

    fun canDebug(language: Language, filePath: String): Boolean
    fun launch(session: DebugSession, breakpoints: List<DebugBreakpoint>, onOutput: (String) -> Unit, onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit): Boolean
    fun stop(session: DebugSession)
    fun pause(session: DebugSession)
    fun resume(session: DebugSession)
    fun stepOver(session: DebugSession)
    fun stepInto(session: DebugSession)
    fun stepOut(session: DebugSession)
    fun evaluate(session: DebugSession, expression: String): String?
    fun supportsHotReload(): Boolean = false
}

/**
 * P25-DEBUG: Providers that support interactive stdin (like pdb, node inspect).
 * The Debug Console input field sends text to these providers' running process.
 */
interface InteractiveDebugProvider : DebugProvider {
    fun sendInput(session: DebugSession, text: String)
}

/**
 * Universal Debug Manager — singleton that manages all debug sessions.
 * Both the Activity Bar Debugger and Terminal Panel Debugger use this.
 */
object UniversalDebugManager {

    private val TAG = "UDM"

    private val providers = mutableListOf<DebugProvider>()
    // P27-4: Thread-safe collections — DAP events arrive on background threads
    private val sessions = ConcurrentHashMap<String, DebugSession>()
    private val breakpoints = ConcurrentHashMap<String, MutableList<DebugBreakpoint>>() // filePath -> breakpoints
    private val sessionCounter = AtomicInteger(0)

    // P26-2d: DAP adapters — tried before legacy providers; prefer DAP when available.
    // Each real adapter (PythonDAPAdapter, etc.) wraps its own DAPClient lifecycle.
    // LegacyDebugAdapter wraps existing DebugProviders as fallback.
    private val adapters = mutableListOf<DebugAdapter>()  // P27-4: accessed in init only, no sync needed
    // Active adapter per session
    private val sessionAdapters = ConcurrentHashMap<String, DebugAdapter>()

    // P27-6: ProcessTracker — centralized process lifecycle tracking
    data class TrackedProcess(
        val pid: Int,
        val sessionId: String,
        val command: String,
        val workDir: String,
        val startTime: Long = System.currentTimeMillis(),
        var exitStatus: Int? = null,
    )
    private val processTracker = ConcurrentHashMap<String, TrackedProcess>()

    /** P27-6: Register a process for tracking. Called by adapters/providers on launch. */
    fun trackProcess(sessionId: String, pid: Int, command: String, workDir: String) {
        processTracker[sessionId] = TrackedProcess(pid, sessionId, command, workDir)
        Log.d(TAG, "trackProcess: pid=$pid session=$sessionId cmd=${command.take(80)}")
    }

    /** P27-6: Unregister a process. Called on stop/crash. */
    fun untrackProcess(sessionId: String) {
        processTracker.remove(sessionId)
    }

    /** P27-6: Get tracked process info for a session. */
    fun getTrackedProcess(sessionId: String): TrackedProcess? = processTracker[sessionId]

    /** P27-6: Clean up orphaned processes (called on app exit or periodic check). */
    fun cleanupOrphanedProcesses() {
        for ((sessionId, proc) in processTracker) {
            if (sessions[sessionId] == null) {
                Log.w(TAG, "cleanupOrphanedProcesses: orphaned process pid=${proc.pid} session=$sessionId — removing from tracker")
                processTracker.remove(sessionId)
            }
        }
    }

    /** Register a DAP adapter. Called in init/registerProviders. */
    fun registerAdapter(adapter: DebugAdapter) {
        adapters.removeAll { it.id == adapter.id }
        adapters.add(0, adapter) // prepend — higher priority than legacy
    }

    /** P26-2d: Resolve the best adapter for a session. DAP first, legacy fallback. */
    fun resolveAdapter(context: Context, session: DebugSession): DebugAdapter {
        // Try real DAP adapters first
        val dap = adapters.firstOrNull { it !is LegacyDebugAdapter && it.canDebug(session.language, session.filePath) }
        if (dap != null) {
            Log.d(TAG, "resolveAdapter: using DAP adapter '${dap.displayName}' for ${session.language}")
            return dap
        }
        // Fall back to a LegacyDebugAdapter wrapping the matching provider
        val provider = providers.firstOrNull { it.canDebug(session.language, session.filePath) }
        if (provider != null) {
            Log.d(TAG, "resolveAdapter: using legacy adapter '${provider.displayName}' for ${session.language}")
            return LegacyDebugAdapter(provider)
        }
        // Last resort: return a no-op legacy adapter
        Log.w(TAG, "resolveAdapter: no adapter found for ${session.language}")
        return LegacyDebugAdapter(providers.first())
    }

    /** Breakpoint persistence — stored per file path. */
    val allBreakpoints: Map<String, List<DebugBreakpoint>> get() = breakpoints.mapValues { it.value.toList() }

    /** Callbacks for UI updates. */
    // P26-1: Multi-listener support — prevents panels from overwriting each other
    private val breakpointListeners = mutableListOf<() -> Unit>()
    private val sessionStateListeners = mutableListOf<(DebugSession) -> Unit>()
    private val outputListeners = mutableListOf<(String) -> Unit>()
    private val pausedListeners = mutableListOf<(List<DebugStackFrame>, List<DebugVariable>) -> Unit>()
    
    fun addOnBreakpointsChangedListener(l: () -> Unit) { breakpointListeners.add(l) }
    fun removeOnBreakpointsChangedListener(l: () -> Unit) { breakpointListeners.remove(l) }
    fun addOnSessionStateChangedListener(l: (DebugSession) -> Unit) { sessionStateListeners.add(l) }
    fun removeOnSessionStateChangedListener(l: (DebugSession) -> Unit) { sessionStateListeners.remove(l) }
    fun addOnOutputListener(l: (String) -> Unit) { outputListeners.add(l) }
    fun removeOnOutputListener(l: (String) -> Unit) { outputListeners.remove(l) }
    fun addOnPausedListener(l: (List<DebugStackFrame>, List<DebugVariable>) -> Unit) { pausedListeners.add(l) }
    fun removeOnPausedListener(l: (List<DebugStackFrame>, List<DebugVariable>) -> Unit) { pausedListeners.remove(l) }
    
    // Backward-compatible single-callback setters (delegate to list)
    var onBreakpointsChanged: (() -> Unit)?
        get() = null
        set(value) { value?.let { breakpointListeners.add(it) } }
    var onSessionStateChanged: ((DebugSession) -> Unit)?
        get() = null
        set(value) { value?.let { sessionStateListeners.add(it) } }
    var onOutput: ((String) -> Unit)?
        get() = null
        set(value) { value?.let { outputListeners.add(it) } }
    var onPaused: ((List<DebugStackFrame>, List<DebugVariable>) -> Unit)?
        get() = null
        set(value) { value?.let { pausedListeners.add(it) } }
    
    private fun notifyBreakpointsChanged() = breakpointListeners.forEach { it() }

    /**
     * P32-BREAKPOINT-FIX: Send updated breakpoints to the active DAP adapter.
     * Called by addBreakpoint/removeBreakpoint/toggleBreakpoint when a debug
     * session is running. If no session is active, this is a no-op (breakpoints
     * will be passed to launch() when a session starts).
     */
    private fun sendBreakpointsToActiveSession(filePath: String) {
        // Find any active session that matches this file
        val activeSession = sessions.values.firstOrNull {
            it.state == DebugState.RUNNING || it.state == DebugState.PAUSED
        } ?: return

        val adapter = sessionAdapters[activeSession.id] ?: return
        val fileBps = breakpoints[filePath] ?: emptyList()

        AppOutputLog.log("[DAP] Sending ${fileBps.size} breakpoint(s) for ${filePath.substringAfterLast("/")} to active session", "lsp")
        val sent = adapter.sendBreakpoints(activeSession, fileBps)
        if (!sent) {
            AppOutputLog.log("[DAP] WARNING: sendBreakpoints returned false — adapter may not support live updates", "lsp")
        }
    }
    private fun notifySessionStateChanged(s: DebugSession) = sessionStateListeners.forEach { it(s) }

    /**
     * P27-3: Validated state transition. Logs and rejects illegal transitions.
     * Use this instead of directly setting session.state = newState.
     */
    private fun transitionState(session: DebugSession, newState: DebugState) {
        if (!isValidTransition(session.state, newState)) {
            Log.w("UDM", "Invalid state transition: ${session.state} → $newState (rejected)")
            return
        }
        session.state = newState
        notifySessionStateChanged(session)
    }
    private fun notifyOutput(msg: String) = outputListeners.forEach { it(msg) }
    private fun notifyPaused(stack: List<DebugStackFrame>, vars: List<DebugVariable>) = pausedListeners.forEach { it(stack, vars) }

    init {
        // P26-2d/P26-3a: Register DAP adapters (tried before legacy providers)
        registerAdapter(PythonDAPAdapter())
        registerAdapter(NodeDAPAdapter())
        // Register built-in providers — P23-10: language providers registered eagerly
        // (lightweight objects, no processes started until launch() is called)
        registerProvider(TerminalDebugProvider())
        registerProvider(PythonDebugProvider())
        registerProvider(NodeJsDebugProvider())
        registerProvider(ShellDebugProvider())
        registerProvider(PhpDebugProvider())
        registerProvider(AndroidDebugProvider())
        registerProvider(ApkDebugProvider())
    }

    fun registerProvider(provider: DebugProvider) {
        if (providers.none { it.id == provider.id }) {
            providers.add(provider)
        }
    }

    /**
     * Selects the best provider for the given language and file.
     * Returns null if no provider supports it.
     */
    fun selectProvider(language: Language, filePath: String): DebugProvider? {
        return providers.firstOrNull { it.canDebug(language, filePath) }
    }

    /**
     * Checks if a language is debuggable.
     */
    fun isDebuggable(language: Language, filePath: String): Boolean {
        return selectProvider(language, filePath) != null
    }

    /**
     * Starts a debug session for the given file.
     * Returns the session ID, or null if no provider is available.
     */
    fun startDebug(
        language: Language,
        filePath: String,
        projectRoot: String? = null,
        context: Context? = null,
    ): String? {
        // P27-1: Route through DAP adapters when context is available.
        // When context is null (legacy callers), fall back to direct provider.launch().
        val sessionId = "session-${sessionCounter.getAndIncrement()}"

        // Resolve adapter if context is available
        val adapter: DebugAdapter? = if (context != null) {
            val tempSession = DebugSession(
                id = sessionId,
                language = language,
                filePath = filePath,
                providerId = "",
                state = DebugState.STARTING,
            )
            resolveAdapter(context, tempSession)
        } else {
            null
        }

        // For legacy path, still need a provider
        val provider: DebugProvider? = if (adapter == null || adapter is LegacyDebugAdapter) {
            selectProvider(language, filePath)
        } else {
            null
        }

        if (adapter == null && provider == null) return null

        val session = DebugSession(
            id = sessionId,
            language = language,
            filePath = filePath,
            providerId = adapter?.id ?: provider!!.id,
            state = DebugState.STARTING,
        )
        sessions[session.id] = session
        if (adapter != null) {
            sessionAdapters[session.id] = adapter
        }
        notifySessionStateChanged(session)

        val fileBreakpoints = breakpoints[filePath] ?: emptyList()

        val launched = if (adapter != null && context != null) {
            // P27-1: DAP adapter path (or LegacyDebugAdapter with context)
            adapter.launch(
                context, session, fileBreakpoints,
                onOutput = { msg -> notifyOutput(msg) },
                onPaused = { stack, vars ->
                    transitionState(session, DebugState.PAUSED)
                    notifyPaused(stack, vars)
                },
                onStopped = {
                    transitionState(session, DebugState.STOPPED)
                    sessions.remove(session.id)
                    sessionAdapters.remove(session.id)
                },
            )
        } else {
            // P27-1: Legacy path — no context, direct provider.launch()
            provider!!.launch(
                session, fileBreakpoints,
                onOutput = { msg -> notifyOutput(msg) },
                onPaused = { stack, vars ->
                    transitionState(session, DebugState.PAUSED)
                    notifyPaused(stack, vars)
                },
            )
        }

        if (launched) {
            transitionState(session, DebugState.RUNNING)
            activeSessionId = session.id  // P27-7: Track active session in UDM
        } else {
            transitionState(session, DebugState.FAILED)
            sessionAdapters.remove(session.id)
        }

        return if (launched) session.id else null
    }

    fun stopSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        transitionState(session, DebugState.STOPPING)
        // P27-1: Use adapter if available, fall back to legacy provider
        if (adapter != null) {
            adapter.stop(session)
        } else {
            providers.find { it.id == session.providerId }?.stop(session)
        }
        transitionState(session, DebugState.STOPPED)
        // P27-7: Clear active session if this was it
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
        sessions.remove(sessionId)
        sessionAdapters.remove(sessionId)
        // Phase N: Notify debug session stopped
        NotificationStore.notifyDebugEvent(
            title = "Debug session ended",
            body = "Session $sessionId (${session.language.displayName}) stopped",
        )
    }

    /**
     * P26-3b: Attach to a running process via DAP.
     * Works with any adapter that exposes an attach() function (currently NodeDAPAdapter).
     *
     * @param language  Language of the debug target.
     * @param filePath  Reference file path (used for breakpoints and scope).
     * @param port      Localhost port the target is listening on (--inspect). Default 9229.
     * @param pid       Process ID to attach to. Used when port == -1.
     */
    fun attachDebug(
        context: Context,
        language: Language,
        filePath: String,
        port: Int = 9229,
        pid: Int = -1,
    ): String? {
        val session = DebugSession(
            id = "attach-${sessionCounter.getAndIncrement()}",
            language = language,
            filePath = filePath,
            providerId = "node-dap",
            state = DebugState.STARTING,
        )
        sessions[session.id] = session
        notifySessionStateChanged(session)

        val nodeAdapter = adapters.filterIsInstance<NodeDAPAdapter>().firstOrNull()
            ?: run {
                Log.e(TAG, "attachDebug: NodeDAPAdapter not registered")
                session.state = DebugState.ERROR
                notifySessionStateChanged(session)
                return null
            }

        sessionAdapters[session.id] = nodeAdapter
        val fileBreakpoints = breakpoints[filePath] ?: emptyList()

        val launched = nodeAdapter.attach(
            context, session, port, pid,
            onOutput = { msg -> notifyOutput(msg) },
            onPaused = { stack, vars ->
                transitionState(session, DebugState.PAUSED)
                notifyPaused(stack, vars)
            },
            onStopped = {
                transitionState(session, DebugState.STOPPED)
                sessions.remove(session.id)
                sessionAdapters.remove(session.id)
            }
        )

        if (launched) {
            transitionState(session, DebugState.RUNNING)
        } else {
            transitionState(session, DebugState.FAILED)
        }
        return if (launched) session.id else null
    }

    /**
     * P26-3d: Multi-session — return all active sessions (any state except IDLE/STOPPED).
     */
    fun getActiveSessions(): List<DebugSession> =
        sessions.values.filter { it.state != DebugState.IDLE && it.state != DebugState.STOPPED }

    /**
     * P26-3d: Get a specific session by ID.
     */
    fun getSessionById(sessionId: String): DebugSession? = sessions[sessionId]

    /**
     * P27-5: Restart a debug session — stop current, preserve breakpoints, start new.
     * Returns the new session ID, or null on failure.
     */
    fun restartSession(
        sessionId: String,
        context: Context? = null,
    ): String? {
        val session = sessions[sessionId] ?: return null
        val language = session.language
        val filePath = session.filePath
        val breakpoints = getBreakpoints(filePath)

        // Stop current session
        stopSession(sessionId)

        // Wait briefly for clean state (max 2s)
        var waited = 0
        while (waited < 2000) {
            if (sessions[sessionId] == null) break
            Thread.sleep(100)
            waited += 100
        }

        // Start new session with same config — breakpoints preserved in BreakpointManager
        Log.d(TAG, "restartSession: restarting $language debug for ${filePath.substringAfterLast("/")}")
        return startDebug(language, filePath, null, context)
    }

    /**
     * P26-3c: Capability negotiation — returns the DAP capabilities for the active session's adapter.
     * The UI uses this to hide controls that the adapter doesn't support.
     * Returns null if the session uses a legacy adapter (no DAP capabilities).
     */
    fun getAdapterCapabilities(sessionId: String): DAPCapabilities? {
        val adapter = sessionAdapters[sessionId] ?: return null
        return adapter.capabilities()
    }

    /**
     * P26-3d: Switch the "active" session for single-pane UI views.
     * Multi-pane UIs should use getActiveSessions() and bind directly.
     */
    @Volatile var activeSessionId: String? = null
        private set

    fun setActiveSession(sessionId: String) {
        if (sessions.containsKey(sessionId)) {
            activeSessionId = sessionId
        }
    }

    fun pauseSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        // P27-1: Use adapter if available, fall back to legacy provider
        if (adapter != null) {
            adapter.pause(session)
        } else {
            providers.find { it.id == session.providerId }?.pause(session)
        }
    }

    fun resumeSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        // P27-1: Use adapter if available, fall back to legacy provider
        if (adapter != null) {
            adapter.resume(session)
        } else {
            providers.find { it.id == session.providerId }?.resume(session)
        }
        // P27-3: Validated transition back to RUNNING
        transitionState(session, DebugState.RUNNING)
    }

    fun stepOver(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        // P27-3: Transition to STEPPING state
        transitionState(session, DebugState.STEPPING)
        if (adapter != null) {
            adapter.stepOver(session)
        } else {
            providers.find { it.id == session.providerId }?.stepOver(session)
        }
    }

    fun stepInto(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        transitionState(session, DebugState.STEPPING)
        if (adapter != null) {
            adapter.stepInto(session)
        } else {
            providers.find { it.id == session.providerId }?.stepInto(session)
        }
    }

    fun stepOut(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        transitionState(session, DebugState.STEPPING)
        if (adapter != null) {
            adapter.stepOut(session)
        } else {
            providers.find { it.id == session.providerId }?.stepOut(session)
        }
    }

    fun evaluateExpression(sessionId: String, expression: String): String? {
        val session = sessions[sessionId] ?: return null
        val adapter = sessionAdapters[sessionId]
        // P27-1: Use adapter if available, fall back to legacy provider
        if (adapter != null) {
            return adapter.evaluate(session, expression)
        }
        return providers.find { it.id == session.providerId }?.evaluate(session, expression)
    }

    /**
     * P25-DEBUG: Send user input to the running debug session's stdin.
     * Used by the Debug Console to send commands to pdb, node inspect, etc.
     */
    fun sendInput(sessionId: String, text: String) {
        val session = sessions[sessionId] ?: return
        val adapter = sessionAdapters[sessionId]
        // P27-1: DAP adapter — evaluate expression; Legacy — write to stdin
        if (adapter != null) {
            val result = adapter.evaluate(session, text)
            if (result != null) notifyOutput(result)
        } else {
            val provider = providers.find { it.id == session.providerId }
            if (provider is InteractiveDebugProvider) {
                provider.sendInput(session, text)
            }
        }
    }

    /**
     * P25-DEBUG: Check if the active session supports interactive input.
     */
    fun sessionSupportsInput(sessionId: String?): Boolean {
        if (sessionId == null) return false
        val session = sessions[sessionId] ?: return false
        // P27-1: DAP adapters support evaluate as debug console input
        if (sessionAdapters[sessionId] != null) return true
        val provider = providers.find { it.id == session.providerId }
        return provider is InteractiveDebugProvider
    }

    fun getActiveSession(): DebugSession? = sessions.values.firstOrNull { it.state == DebugState.RUNNING || it.state == DebugState.PAUSED }

    // ── Breakpoint management ──────────────────────────────────────────

    fun addBreakpoint(filePath: String, line: Int, condition: String? = null, logMessage: String? = null) {
        val list = breakpoints.getOrPut(filePath) { mutableListOf() }
        if (list.none { it.filePath == filePath && it.line == line }) {
            list.add(DebugBreakpoint(filePath, line, condition, logMessage))
            notifyBreakpointsChanged()
            sendBreakpointsToActiveSession(filePath)
        }
    }

    fun removeBreakpoint(filePath: String, line: Int) {
        breakpoints[filePath]?.removeAll { it.line == line }
        if (breakpoints[filePath]?.isEmpty() == true) breakpoints.remove(filePath)
        notifyBreakpointsChanged()
        sendBreakpointsToActiveSession(filePath)
    }

    fun toggleBreakpoint(filePath: String, line: Int) {
        val list = breakpoints.getOrPut(filePath) { mutableListOf() }
        if (list.any { it.line == line }) {
            list.removeAll { it.line == line }
            if (list.isEmpty()) breakpoints.remove(filePath)
        } else {
            list.add(DebugBreakpoint(filePath, line))
        }
        notifyBreakpointsChanged()
        // P32-BREAKPOINT-FIX: If a debug session is active, send updated breakpoints
        // to the DAP adapter so it stops at the new breakpoint location.
        sendBreakpointsToActiveSession(filePath)
    }

    fun getBreakpoints(filePath: String): List<DebugBreakpoint> = breakpoints[filePath]?.toList() ?: emptyList()

    fun hasBreakpoint(filePath: String, line: Int): Boolean =
        breakpoints[filePath]?.any { it.line == line } == true

    fun setBreakpointEnabled(filePath: String, line: Int, enabled: Boolean) {
        breakpoints[filePath]?.find { it.line == line }?.let { bp ->
            val idx = breakpoints[filePath]!!.indexOf(bp)
            breakpoints[filePath]!![idx] = bp.copy(enabled = enabled)
            notifyBreakpointsChanged()
        }
    }

    /** Get all breakpoints across all files, flattened. */
    fun getAllBreakpoints(): List<DebugBreakpoint> = breakpoints.values.flatten()

    /** Total breakpoint count. */
    fun breakpointCount(): Int = breakpoints.values.sumOf { it.size }

    // ── Breakpoint persistence (P23-8) ────────────────────────────────
    // Saves/loads breakpoints to SharedPreferences so they survive app restarts.

    private const val PREFS_NAME = "debug_breakpoints"
    private const val KEY_BREAKPOINTS = "breakpoints_json"

    fun saveBreakpoints(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONArray()
        getAllBreakpoints().forEach { bp ->
            val obj = org.json.JSONObject()
            obj.put("filePath", bp.filePath)
            obj.put("line", bp.line)
            obj.put("condition", bp.condition ?: org.json.JSONObject.NULL)
            obj.put("logMessage", bp.logMessage ?: org.json.JSONObject.NULL)
            obj.put("enabled", bp.enabled)
            json.put(obj)
        }
        prefs.edit().putString(KEY_BREAKPOINTS, json.toString()).apply()
    }

    fun loadBreakpoints(context: android.content.Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_BREAKPOINTS, null) ?: return
        try {
            val arr = org.json.JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val filePath = obj.optString("filePath")
                val line = obj.optInt("line")
                val condition = obj.opt("condition") as? String
                val logMessage = obj.opt("logMessage") as? String
                val enabled = obj.optBoolean("enabled", true)
                if (filePath.isNotEmpty()) {
                    val list = breakpoints.getOrPut(filePath) { mutableListOf() }
                    if (list.none { it.line == line }) {
                        list.add(DebugBreakpoint(filePath, line, condition, logMessage, enabled))
                    }
                }
            }
            notifyBreakpointsChanged()
        } catch (_: Exception) {}
    }

    fun clearAllBreakpoints() {
        breakpoints.clear()
        notifyBreakpointsChanged()
    }
}

/**
 * Terminal-based debug provider — the default/fallback provider.
 * Runs the file in a terminal session and captures output.
 * This is the lightweight provider used by the Terminal Panel Debugger.
 */
class TerminalDebugProvider : DebugProvider {
    override val id = "terminal"
    override val displayName = "Terminal Run"
    override val supportedLanguages = Language.values().toSet()

    override fun canDebug(language: Language, filePath: String): Boolean {
        // Terminal provider can "debug" any runnable file by running it
        return when (language) {
            Language.PYTHON, Language.JAVASCRIPT, Language.TYPESCRIPT,
            Language.SHELL, Language.GO, Language.RUST, Language.C, Language.CPP,
            Language.JAVA, Language.KOTLIN, Language.PHP -> true
            else -> false
        }
    }

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        // The actual execution is dispatched to the terminal pane
        // This just signals that the provider is ready
        onOutput("[terminal] Ready to run ${File(session.filePath).name}")
        return true
    }

    override fun stop(session: DebugSession) {
        // Terminal handles stop via its own process management
    }

    override fun pause(session: DebugSession) { /* Terminal: Ctrl+C */ }
    override fun resume(session: DebugSession) { /* Terminal: re-run */ }
    override fun stepOver(session: DebugSession) { /* Not supported in terminal mode */ }
    override fun stepInto(session: DebugSession) { /* Not supported in terminal mode */ }
    override fun stepOut(session: DebugSession) { /* Not supported in terminal mode */ }

    override fun evaluate(session: DebugSession, expression: String): String? {
        // Terminal mode: can't evaluate expressions mid-run
        return null
    }
}


// ─────────────────────────────────────────────────────────────────────────────
// P23-5: Language-specific debug providers
// P23-7: Android & APK debug providers
// P23-10: Providers are registered lazily (only when first needed)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Python debug provider — uses `python3 -u` for unbuffered output.
 * On device this runs inside the proot Ubuntu environment.
 * Full debugpy integration is a future enhancement once DAP is wired;
 * for now it gives real process output + exit-code reporting.
 */
class PythonDebugProvider : InteractiveDebugProvider {
    override val id = "python"
    override val displayName = "Python (pdb)"
    override val supportedLanguages = setOf(Language.PYTHON)
    private var process: Process? = null
    private var stdinWriter: java.io.PrintWriter? = null
    // P26-1: Pending evaluation result capture
    @Volatile private var pendingEvalResult: String? = null
    @Volatile private var capturingEval: Boolean = false

    override fun canDebug(language: Language, filePath: String) =
        language == Language.PYTHON && filePath.endsWith(".py")

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        return try {
            val workDir = File(session.filePath).parentFile ?: File("/")
            val pb = ProcessBuilder("python3", "-m", "pdb", session.filePath)
                .directory(workDir)
                .redirectErrorStream(true)
            process = pb.start()
            val proc = process!!
            stdinWriter = java.io.PrintWriter(proc.outputStream, true)
            // Inject breakpoints before running
            if (breakpoints.isNotEmpty()) {
                Thread.sleep(200)
                for (bp in breakpoints) {
                    stdinWriter?.println("break ${session.filePath}:${bp.line + 1}")
                    stdinWriter?.flush()
                }
            }
            stdinWriter?.println("continue")
            stdinWriter?.flush()
            // P26-1: Parse pdb output to detect pause, extract variables + call stack
            Thread {
                try {
                    val reader = proc.inputStream.bufferedReader()
                    var currentFile: String? = null
                    var currentLine: Int = -1
                    var currentFunc: String = ""
                    var lastPromptWasPause = false
                    while (true) {
                        val line = reader.readLine() ?: break
                        onOutput(line)
                        // Detect pdb prompt → execution paused
                        if (line.trim() == "(Pdb)" || line.trim() == "(Pdb+)") {
                            lastPromptWasPause = true
                            // Parse stack: send 'where' and 'a' (args) + 'p' for locals
                            if (currentFile != null && currentLine >= 0) {
                                // Build a basic stack frame from the current location
                                val stack = listOf(DebugStackFrame(
                                    function = currentFunc,
                                    file = currentFile ?: session.filePath,
                                    line = currentLine,
                                    active = true
                                ))
                                // Request local variables: send 'p dir()' then parse on next pause
                                // For now, send 'a' (args) + 'p locals()' to get variables
                                stdinWriter?.println("a")
                                stdinWriter?.flush()
                                Thread.sleep(100)
                                stdinWriter?.println("p list(locals().items())")
                                stdinWriter?.flush()
                                Thread.sleep(150)
                                // Read the variable output (lines before next prompt)
                                val vars = mutableListOf<DebugVariable>()
                                while (true) {
                                    val vline = reader.readLine() ?: break
                                    onOutput(vline)
                                    if (vline.trim() == "(Pdb)" || vline.trim() == "(Pdb+)" || vline.trim().isEmpty() && vars.isNotEmpty()) {
                                        break
                                    }
                                    // Parse variable lines: "name = value" or "('name', value)"
                                    if ("=" in vline && !vline.startsWith(">") && !vline.startsWith("-")) {
                                        val parts = vline.split("=", limit = 2)
                                        if (parts.size == 2) {
                                            val name = parts[0].trim()
                                            val value = parts[1].trim()
                                            if (name.isNotEmpty() && name.isNotBlank()) {
                                                val type = when {
                                                    value.startsWith("'") || value.startsWith("\"") -> "str"
                                                    value.startsWith("[") -> "list"
                                                    value.startsWith("{") -> "dict"
                                                    value.startsWith("(") -> "tuple"
                                                    value == "True" || value == "False" -> "bool"
                                                    value.toIntOrNull() != null -> "int"
                                                    value.toDoubleOrNull() != null -> "float"
                                                    value == "None" -> "NoneType"
                                                    else -> "obj"
                                                }
                                                vars.add(DebugVariable(name = name, type = type, value = value.take(200), depth = 0, expandable = value.startsWith("[") || value.startsWith("{")))
                                            }
                                        }
                                    }
                                }
                                onPaused(stack, vars)
                            }
                        }
                        // Parse "> file(line)function()" to track current location
                        if (line.startsWith(">") && "(" in line && ")" in line) {
                            // Pattern: > /path/to/file.py(10)function()
                            val match = Regex(""">(.*)\((\d+)\)(.*)""").find(line)
                            if (match != null) {
                                currentFile = match.groupValues[1].trim()
                                currentLine = match.groupValues[2].toIntOrNull()?.minus(1) ?: -1
                                currentFunc = match.groupValues[3].trim()
                            }
                        }
                        // Also capture "break in file.js:line" for breakpoint hits
                        if (line.contains("Breakpoint") && line.contains("at")) {
                            // e.g. "Breakpoint 1 at /path/file.py:10"
                        }
                    }
                    val exit = proc.waitFor()
                    onOutput("[python] Process exited with code $exit")
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) {
            onOutput("[python] Launch failed: ${e.message}")
            false
        }
    }

    override fun sendInput(session: DebugSession, text: String) {
        stdinWriter?.println(text)
        stdinWriter?.flush()
    }

    override fun stop(session: DebugSession) {
        stdinWriter?.close()
        process?.destroyForcibly()
        process = null
        stdinWriter = null
    }
    override fun pause(session: DebugSession) { sendInput(session, "!import signal; signal.raise_signal(signal.SIGINT)") }
    override fun resume(session: DebugSession) { sendInput(session, "continue") }
    override fun stepOver(session: DebugSession) { sendInput(session, "next") }
    override fun stepInto(session: DebugSession) { sendInput(session, "step") }
    override fun stepOut(session: DebugSession) { sendInput(session, "return") }
    override fun evaluate(session: DebugSession, expression: String): String? {
        sendInput(session, "p $expression")
        return "(sent to pdb — see console)"
    }
}

/**
 * JavaScript / Node.js debug provider — runs `node` directly.
 */
class NodeJsDebugProvider : InteractiveDebugProvider {
    override val id = "nodejs"
    override val displayName = "Node.js (inspect)"
    override val supportedLanguages = setOf(Language.JAVASCRIPT, Language.TYPESCRIPT)
    private var process: Process? = null
    private var stdinWriter: java.io.PrintWriter? = null

    override fun canDebug(language: Language, filePath: String) =
        language in supportedLanguages && (filePath.endsWith(".js") || filePath.endsWith(".mjs") || filePath.endsWith(".cjs") || filePath.endsWith(".ts"))

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        return try {
            val workDir = File(session.filePath).parentFile ?: File("/")
            // P25-DEBUG: Use node inspect for real debugging
            // TypeScript: try ts-node, fall back to node
            val cmd = if (session.filePath.endsWith(".ts")) {
                listOf("node", "--inspect-brk", "-r", "ts-node/register", session.filePath)
            } else {
                listOf("node", "inspect", session.filePath)
            }
            val pb = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true)
            process = pb.start()
            val proc = process!!
            stdinWriter = java.io.PrintWriter(proc.outputStream, true)
            // Stream output on background thread
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        onOutput(line)
                    }
                    val exit = proc.waitFor()
                    onOutput("[node] Process exited with code $exit")
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) {
            onOutput("[node] Launch failed: ${e.message}")
            false
        }
    }

    override fun sendInput(session: DebugSession, text: String) {
        stdinWriter?.println(text)
        stdinWriter?.flush()
    }

    override fun stop(session: DebugSession) {
        stdinWriter?.close()
        process?.destroyForcibly()
        process = null
        stdinWriter = null
    }
    override fun pause(session: DebugSession) { sendInput(session, "pause") }
    override fun resume(session: DebugSession) { sendInput(session, "cont") }
    override fun stepOver(session: DebugSession) { sendInput(session, "next") }
    override fun stepInto(session: DebugSession) { sendInput(session, "step") }
    override fun stepOut(session: DebugSession) { sendInput(session, "out") }
    override fun evaluate(session: DebugSession, expression: String): String? {
        sendInput(session, "repl")
        sendInput(session, expression)
        return "(sent to node inspect — see console output)"
    }
}

/**
 * Shell / Bash debug provider — runs shell scripts via bash.
 */
class ShellDebugProvider : InteractiveDebugProvider {
    override val id = "shell"
    override val displayName = "Shell (bash)"
    override val supportedLanguages = setOf(Language.SHELL)
    private var process: Process? = null

    override fun canDebug(language: Language, filePath: String) =
        language == Language.SHELL

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        return try {
            val workDir = File(session.filePath).parentFile ?: File("/")
            val pb = ProcessBuilder("bash", "-x", session.filePath)
                .directory(workDir)
                .redirectErrorStream(true)
            process = pb.start()
            val proc = process!!
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        onOutput("[shell] $line")
                    }
                    val exit = proc.waitFor()
                    onOutput("[shell] Process exited with code $exit")
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) {
            onOutput("[shell] Launch failed: ${e.message}")
            false
        }
    }

    override fun stop(session: DebugSession) { process?.destroyForcibly(); process = null }
    override fun pause(session: DebugSession) {}
    override fun resume(session: DebugSession) {}
    override fun stepOver(session: DebugSession) {}
    override fun stepInto(session: DebugSession) {}
    override fun stepOut(session: DebugSession) {}
    override fun sendInput(session: DebugSession, text: String) {
        process?.outputStream?.write("$text\n".toByteArray())
        process?.outputStream?.flush()
    }
    override fun evaluate(session: DebugSession, expression: String): String? = null
}

/**
 * PHP debug provider — runs `php` interpreter.
 */
class PhpDebugProvider : DebugProvider {
    override val id = "php"
    override val displayName = "PHP"
    override val supportedLanguages = setOf(Language.PHP)
    private var process: Process? = null

    override fun canDebug(language: Language, filePath: String) =
        language == Language.PHP

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        return try {
            val workDir = File(session.filePath).parentFile ?: File("/")
            val pb = ProcessBuilder("php", session.filePath)
                .directory(workDir).redirectErrorStream(true)
            process = pb.start()
            val proc = process!!
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { onOutput("[php] $it") }
                    val exit = proc.waitFor()
                    onOutput("[php] Process exited with code $exit")
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) {
            onOutput("[php] Launch failed: ${e.message}")
            false
        }
    }

    override fun stop(session: DebugSession) { process?.destroyForcibly(); process = null }
    override fun pause(session: DebugSession) {}
    override fun resume(session: DebugSession) {}
    override fun stepOver(session: DebugSession) {}
    override fun stepInto(session: DebugSession) {}
    override fun stepOut(session: DebugSession) {}
    override fun evaluate(session: DebugSession, expression: String): String? = null
}

/**
 * P23-7: Android/APK debug provider.
 * Detects .kt/.java project files and routes to ADB-based runtime inspection.
 * On a mobile device without ADB over USB, this provides logcat streaming
 * and basic process attach guidance.
 */
class AndroidDebugProvider : DebugProvider {
    override val id = "android"
    override val displayName = "Android (ADB)"
    override val supportedLanguages = setOf(Language.KOTLIN, Language.JAVA)
    private var process: Process? = null

    override fun canDebug(language: Language, filePath: String): Boolean {
        // Only activate for Kotlin/Java files inside an Android project
        return (language == Language.KOTLIN || language == Language.JAVA) &&
            (filePath.contains("/android/") || filePath.contains("/src/main/java/") ||
             filePath.contains("/src/main/kotlin/"))
    }

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        onOutput("[android] Android Debug Provider — ADB logcat streaming")
        onOutput("[android] File: ${File(session.filePath).name}")
        onOutput("[android] Breakpoints set: ${breakpoints.size}")
        onOutput("[android] To attach: connect via USB debugging or wireless ADB")
        onOutput("[android] Streaming logcat from process...")
        return try {
            val pb = ProcessBuilder("logcat", "-v", "time", "*:D")
                .redirectErrorStream(true)
            process = pb.start()
            val proc = process!!
            Thread {
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        onOutput("[logcat] $line")
                    }
                } catch (_: Exception) {}
            }.also { it.isDaemon = true }.start()
            true
        } catch (e: Exception) {
            onOutput("[android] ADB not available: ${e.message}")
            onOutput("[android] Use Logcat tab for runtime output instead")
            // Return true anyway — guidance was delivered
            true
        }
    }

    override fun stop(session: DebugSession) { process?.destroyForcibly(); process = null }
    override fun pause(session: DebugSession) {}
    override fun resume(session: DebugSession) {}
    override fun stepOver(session: DebugSession) {}
    override fun stepInto(session: DebugSession) {}
    override fun stepOut(session: DebugSession) {}
    override fun evaluate(session: DebugSession, expression: String): String? = null
}

/**
 * P23-7: APK debug provider — metadata + manifest inspection.
 */
class ApkDebugProvider : DebugProvider {
    override val id = "apk"
    override val displayName = "APK Inspector"
    override val supportedLanguages = emptySet<Language>()

    override fun canDebug(language: Language, filePath: String) =
        filePath.endsWith(".apk", ignoreCase = true)

    override fun launch(
        session: DebugSession,
        breakpoints: List<DebugBreakpoint>,
        onOutput: (String) -> Unit,
        onPaused: (List<DebugStackFrame>, List<DebugVariable>) -> Unit,
    ): Boolean {
        val file = File(session.filePath)
        onOutput("[apk] APK Debug Provider")
        onOutput("[apk] File: ${file.name} (${file.length() / 1024}KB)")
        onOutput("[apk] Use APK Viewer for manifest, DEX, and resource inspection")
        onOutput("[apk] To install: adb install ${file.absolutePath}")
        onOutput("[apk] To launch after install: adb shell monkey -p <package> 1")
        return true
    }

    override fun stop(session: DebugSession) {}
    override fun pause(session: DebugSession) {}
    override fun resume(session: DebugSession) {}
    override fun stepOver(session: DebugSession) {}
    override fun stepInto(session: DebugSession) {}
    override fun stepOut(session: DebugSession) {}
    override fun evaluate(session: DebugSession, expression: String): String? = null
}

