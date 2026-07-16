package com.codespace.ide.debug

import com.codespace.ide.domain.Language
import com.codespace.ide.editor.FileIndexer
import java.io.File

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
    IDLE, STARTING, RUNNING, PAUSED, STOPPING, STOPPED, ERROR
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
 * Universal Debug Manager — singleton that manages all debug sessions.
 * Both the Activity Bar Debugger and Terminal Panel Debugger use this.
 */
object UniversalDebugManager {

    private val providers = mutableListOf<DebugProvider>()
    private val sessions = mutableMapOf<String, DebugSession>()
    private val breakpoints = mutableMapOf<String, MutableList<DebugBreakpoint>>() // filePath -> breakpoints
    private var sessionCounter = 0

    /** Breakpoint persistence — stored per file path. */
    val allBreakpoints: Map<String, List<DebugBreakpoint>> get() = breakpoints.mapValues { it.value.toList() }

    /** Callbacks for UI updates. */
    var onBreakpointsChanged: (() -> Unit)? = null
    var onSessionStateChanged: ((DebugSession) -> Unit)? = null
    var onOutput: ((String) -> Unit)? = null
    var onPaused: ((List<DebugStackFrame>, List<DebugVariable>) -> Unit)? = null

    init {
        // Register built-in providers
        registerProvider(TerminalDebugProvider())
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
    ): String? {
        val provider = selectProvider(language, filePath)
            ?: return null

        val session = DebugSession(
            id = "session-${sessionCounter++}",
            language = language,
            filePath = filePath,
            providerId = provider.id,
            state = DebugState.STARTING,
        )
        sessions[session.id] = session
        onSessionStateChanged?.invoke(session)

        val fileBreakpoints = breakpoints[filePath] ?: emptyList()
        val launched = provider.launch(
            session,
            fileBreakpoints,
            onOutput = { msg ->
                onOutput?.invoke(msg)
            },
            onPaused = { stack, vars ->
                session.state = DebugState.PAUSED
                onSessionStateChanged?.invoke(session)
                onPaused?.invoke(stack, vars)
            }
        )

        if (launched) {
            session.state = DebugState.RUNNING
        } else {
            session.state = DebugState.ERROR
        }
        onSessionStateChanged?.invoke(session)

        return if (launched) session.id else null
    }

    fun stopSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        session.state = DebugState.STOPPING
        onSessionStateChanged?.invoke(session)
        provider?.stop(session)
        session.state = DebugState.STOPPED
        onSessionStateChanged?.invoke(session)
        sessions.remove(sessionId)
    }

    fun pauseSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        provider?.pause(session)
    }

    fun resumeSession(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        provider?.resume(session)
        session.state = DebugState.RUNNING
        onSessionStateChanged?.invoke(session)
    }

    fun stepOver(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        provider?.stepOver(session)
    }

    fun stepInto(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        provider?.stepInto(session)
    }

    fun stepOut(sessionId: String) {
        val session = sessions[sessionId] ?: return
        val provider = providers.find { it.id == session.providerId }
        provider?.stepOut(session)
    }

    fun evaluateExpression(sessionId: String, expression: String): String? {
        val session = sessions[sessionId] ?: return null
        val provider = providers.find { it.id == session.providerId }
        return provider?.evaluate(session, expression)
    }

    fun getActiveSession(): DebugSession? = sessions.values.firstOrNull { it.state == DebugState.RUNNING || it.state == DebugState.PAUSED }

    // ── Breakpoint management ──────────────────────────────────────────

    fun addBreakpoint(filePath: String, line: Int, condition: String? = null, logMessage: String? = null) {
        val list = breakpoints.getOrPut(filePath) { mutableListOf() }
        if (list.none { it.filePath == filePath && it.line == line }) {
            list.add(DebugBreakpoint(filePath, line, condition, logMessage))
            onBreakpointsChanged?.invoke()
        }
    }

    fun removeBreakpoint(filePath: String, line: Int) {
        breakpoints[filePath]?.removeAll { it.line == line }
        if (breakpoints[filePath]?.isEmpty() == true) breakpoints.remove(filePath)
        onBreakpointsChanged?.invoke()
    }

    fun toggleBreakpoint(filePath: String, line: Int) {
        val list = breakpoints.getOrPut(filePath) { mutableListOf() }
        if (list.any { it.line == line }) {
            list.removeAll { it.line == line }
            if (list.isEmpty()) breakpoints.remove(filePath)
        } else {
            list.add(DebugBreakpoint(filePath, line))
        }
        onBreakpointsChanged?.invoke()
    }

    fun getBreakpoints(filePath: String): List<DebugBreakpoint> = breakpoints[filePath]?.toList() ?: emptyList()

    fun hasBreakpoint(filePath: String, line: Int): Boolean =
        breakpoints[filePath]?.any { it.line == line } == true

    fun setBreakpointEnabled(filePath: String, line: Int, enabled: Boolean) {
        breakpoints[filePath]?.find { it.line == line }?.let { bp ->
            val idx = breakpoints[filePath]!!.indexOf(bp)
            breakpoints[filePath]!![idx] = bp.copy(enabled = enabled)
            onBreakpointsChanged?.invoke()
        }
    }

    /** Get all breakpoints across all files, flattened. */
    fun getAllBreakpoints(): List<DebugBreakpoint> = breakpoints.values.flatten()

    /** Total breakpoint count. */
    fun breakpointCount(): Int = breakpoints.values.sumOf { it.size }
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
