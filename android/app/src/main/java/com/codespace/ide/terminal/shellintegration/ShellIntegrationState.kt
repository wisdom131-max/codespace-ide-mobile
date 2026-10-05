package com.codespace.ide.terminal.shellintegration

import com.termux.terminal.TerminalSession
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * D15-a: per-session shell-integration state. ONE instance per terminal
 * session, attached at session creation — never a singleton, never keyed by
 * anything but the session (this is the duplicated-UDM-listener /
 * lspSquiggles-not-keyed lesson applied at design time: cross-tab state leaks
 * are impossible because there is no shared table to leak through).
 *
 * SECURITY MODEL (approved point 7, IG02/TP02/CH05 discipline):
 * The app injects a per-session random nonce into the shell environment via
 * [NONCE_ENV]; the emitter captures it once, UNSETS it (children cannot read
 * it) and echoes it back on every `E` (command line) mark. This state compares
 * the echoed nonce against the expected one:
 *   - match            → nonceValidated = true
 *   - mismatch/absent  → parsed + logged but flagged UNVALIDATED, fail-closed.
 *
 * Honest limitation (stated in the approved doc, identical to VS Code's own
 * model): the nonce lives in the shell's own environment, so it defends
 * against cross-session injection, spoofed marks from OUTPUT (e.g. `echo`
 * of a fake sequence by a malicious program's stdout), and stale sessions —
 * NOT against code running inside the same shell. D15-a has NO consumers;
 * when D15-c wires command metadata into AI/automation behavior, it must
 * consume nonceValidated pairs only.
 *
 * Threading: [onPayload] is called from the emulator READER thread. All state
 * mutation is synchronized. AppOutputLog is safe to call from any thread
 * (the OSC 7777 bridge already logs from this thread).
 */
// D15-b: constructor is internal (not private) so the JVM test suite can drive
// onPayload directly without a live TerminalSession — production code still
// goes through attach(); nothing outside the module can construct this.
class ShellIntegrationState internal constructor(
    private val sessionId: Long,
    private val expectedNonce: String?,
) {
    companion object {
        /** Environment variable the app injects per session; the emitter unsets it after capture. */
        const val NONCE_ENV = "CODESPACE_SHELL_NONCE"

        /** Max command records retained per session (ring, oldest dropped). */
        private const val MAX_RECORDS = 50

        /** Max chars of a raw payload echoed into the debug log. */
        private const val LOG_RAW_LIMIT = 120

        private val sessionCounter = AtomicLong(0)

        /** New 32-hex-char per-session nonce. */
        fun newNonce(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            val sb = StringBuilder(32)
            for (b in bytes) sb.append(String.format("%02x", b))
            return sb.toString()
        }

        /**
         * Attach per-session state to a freshly created session. Call at BOTH
         * session factory sites (TerminalService.createSession and
         * TerminalPane.createTerminalSession) right after TerminalSession
         * construction — the dual-factory drift (TP14) means wiring only one
         * site would leave half the tabs unintegrated.
         *
         * [expectedNonce] must be the SAME value that was injected into the
         * session env via [NONCE_ENV]; pass null when injection was skipped
         * (then E events are parsed but always flagged UNVALIDATED).
         */
        fun attach(session: TerminalSession, expectedNonce: String?): ShellIntegrationState {
            val state = ShellIntegrationState(sessionCounter.incrementAndGet(), expectedNonce)
            session.setOsc633Listener { payload -> state.onPayload(payload) }
            registry[session] = state
            state.log("listener attached — session #${state.sessionId}, nonce=${if (expectedNonce != null) "present" else "NOT INJECTED (E events will be UNVALIDATED)"}")
            return state
        }

        // D15-b B0 — SESSION REGISTRY: both factory sites discard attach()'s return
        // value, so pane-side consumers need a lookup. WeakHashMap: a finished
        // session's entry is collectable even if a detach call is missed; detach()
        // (closeTab / service onDestroy) removes promptly. Synchronized wrapper:
        // attach/detach/forSession race safely across threads.
        private val registry = java.util.Collections.synchronizedMap(
            java.util.WeakHashMap<TerminalSession, ShellIntegrationState>(),
        )

        /** The per-session state attached to [session], or null when the session has no OSC 633 integration. */
        fun forSession(session: TerminalSession): ShellIntegrationState? = registry[session]

        /** Remove [session]'s registry entry (call when the session is finished). */
        fun detach(session: TerminalSession) {
            registry.remove(session)
        }

        /**
         * D15-b: injectable log sink. Production leaves this null (debug channel via
         * AppOutputLog); JVM tests replace it because AppOutputLog is Android-bound
         * (Handler/Looper) and this class must stay runnable host-side.
         */
        internal var logSink: ((String) -> Unit)? = null
    }

    enum class Phase { PROMPT, COMMAND_INPUT, COMMAND_OUTPUT }

    /**
     * D15-b B0 — REACTIVE SURFACE: what changed. Callbacks fire on the READER
     * thread (from onPayload); consumers must hop to the main thread themselves.
     * Pure Kotlin (CopyOnWriteArrayList) so the JVM suite can test this class.
     */
    enum class ChangeKind { CWD, OUTPUT_START, COMMAND_COMPLETE }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ChangeKind) -> Unit>()

    /**
     * Observe state changes. Returns a removal handle. Callbacks fire on the
     * reader thread; never blocks onPayload (each callback is isolated, a
     * throwing listener cannot kill the others or the reader thread).
     */
    fun addListener(listener: (ChangeKind) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun fire(kind: ChangeKind) {
        for (l in listeners) runCatching { l(kind) }
    }

    data class CommandRecord(
        val seq: Long,
        val command: String,
        val exitCode: Int?,
        val nonceValidated: Boolean,
        val cwd: String?,
        val commandStartMs: Long,
    )

    // ── mutable state (all access synchronized on this) ──────────────────
    private var phase: Phase = Phase.PROMPT
    private var firstPromptSeen: Boolean = false
    private var currentCommand: String = ""
    private var currentCommandValidated: Boolean = false
    private var cwd: String? = null
    private var lastExitCode: Int? = null
    private var pendingSeq: Long = 0
    private val records = ArrayDeque<CommandRecord>()
    private var eventsTotal: Long = 0
    private var unknownTotal: Long = 0
    private var unvalidatedTotal: Long = 0

    /** Entry point from the emulator reader thread. Never throws. */
    fun onPayload(payload: String) {
        val event = try {
            ShellIntegrationParser.parse(payload)
        } catch (t: Throwable) {
            // Parser is specified never-throw, but the reader thread must survive anything.
            synchronized(this) { unknownTotal++ }
            log("PARSE-THREW ${t.javaClass.simpleName}: ${t.message} raw=${trunc(payload)}")
            return
        }
        synchronized(this) {
            eventsTotal++
            when (event) {
                is ShellIntegrationEvent.PromptStart -> phase = Phase.PROMPT
                is ShellIntegrationEvent.PromptEnd -> { /* stay: prompt painted, awaiting input */ }
                is ShellIntegrationEvent.CommandOutputStart -> {
                    phase = Phase.COMMAND_OUTPUT
                    fire(ChangeKind.OUTPUT_START)
                }
                is ShellIntegrationEvent.CommandComplete -> {
                    phase = Phase.PROMPT
                    lastExitCode = event.exitCode
                    records.addLast(
                        CommandRecord(
                            seq = pendingSeq,
                            command = currentCommand,
                            exitCode = event.exitCode,
                            nonceValidated = currentCommandValidated,
                            cwd = cwd,
                            commandStartMs = System.currentTimeMillis(),
                        ),
                    )
                    if (records.size > MAX_RECORDS) records.removeFirst()
                    pendingSeq++
                    currentCommand = ""
                    currentCommandValidated = false
                    fire(ChangeKind.COMMAND_COMPLETE)
                }
                is ShellIntegrationEvent.CommandLine -> {
                    phase = Phase.COMMAND_INPUT
                    currentCommand = event.command
                    val validated = expectedNonce != null && event.nonce == expectedNonce
                    currentCommandValidated = validated
                    if (!validated) unvalidatedTotal++
                }
                is ShellIntegrationEvent.ContinuationStart -> { /* multi-line command continuation */ }
                is ShellIntegrationEvent.ContinuationEnd -> { /* continuation ended */ }
                is ShellIntegrationEvent.Property -> {
                    if (event.name == "Cwd") {
                        val changed = cwd != event.value
                        cwd = event.value
                        if (changed) fire(ChangeKind.CWD)
                        log("P;Cwd=${trunc(event.value)}${if (changed) "" else " (unchanged)"}")
                        return
                    }
                }
                is ShellIntegrationEvent.Unknown -> {
                    unknownTotal++
                    log("UNKNOWN subcommand='${trunc(event.subcommand)}' raw=${trunc(event.raw)}")
                    return
                }
            }
            when (event) {
                is ShellIntegrationEvent.PromptStart -> log("A prompt-start")
                is ShellIntegrationEvent.PromptEnd -> { firstPromptSeen = true; log("B prompt-end (first=$firstPromptSeen)") }
                is ShellIntegrationEvent.CommandOutputStart -> log("C output-start")
                is ShellIntegrationEvent.CommandComplete -> log("D complete exit=${event.exitCode ?: "-"}")
                is ShellIntegrationEvent.CommandLine -> log(
                    "E cmd='${trunc(event.command)}' nonce=${if (event.nonce == null) "ABSENT" else if (currentCommandValidated) "validated" else "UNVALIDATED"}",
                )
                is ShellIntegrationEvent.ContinuationStart -> log("F continuation-start")
                is ShellIntegrationEvent.ContinuationEnd -> log("G continuation-end")
                else -> {}
            }
        }
    }

    // ── read accessors (for D15-b consumers; display-only data) ──────────
    @get:Synchronized
    val currentPhase: Phase get() = phase
    @get:Synchronized
    val currentCwd: String? get() = cwd
    @get:Synchronized
    val lastCommandExitCode: Int? get() = lastExitCode
    fun recentCommands(limit: Int = MAX_RECORDS): List<CommandRecord> = synchronized(this) { records.toList().takeLast(limit) }

    private fun log(msg: String) {
        val line = "[633] #$sessionId $msg"
        val sink = logSink
        if (sink != null) sink(line)
        else com.codespace.ide.diagnostics.AppOutputLog.log(line, "terminal")
    }

    private fun trunc(s: String): String = if (s.length <= LOG_RAW_LIMIT) s else s.take(LOG_RAW_LIMIT) + "…(${s.length})"
}
