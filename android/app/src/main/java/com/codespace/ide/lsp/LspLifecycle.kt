package com.codespace.ide.lsp

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * BATCH9-P1a (advisor ruling 2026-10-18): PURE server-lifecycle state machine
 * and stop-request coordination.
 *
 * The advisor's addition 1: one owner per server; all start/stop/restart/repair
 * commands serialize through one queue; a generation counter alone does NOT
 * prevent overlapping instances. This file holds the DECISION logic only — no
 * Android imports, no I/O — so the transition matrix below is pinned by JVM
 * tests (LspLifecycleTest). LspManager stays the single runtime owner and maps
 * its LspState values onto these machine states.
 *
 * States (advisor list):
 *  - STOPPED          no process
 *  - STARTING         spawn/initialize in flight
 *  - RUNNING          process alive + initialized
 *  - STOPPING         a stop chain is in flight (shutdown -> exit -> kill)
 *  - RESTART_BACKOFF  crashed; auto-restart pending under backoff
 *  - FAILED_DISABLED  crash cap exceeded; auto-start blocked until MANUAL retry
 *
 * Transitions the advisor required to be DEFINED (all pinned in LspLifecycleTest):
 *  - start during stop            START_REQUESTED in STOPPING  -> stays STOPPING, start QUEUES
 *  - stop during initialize       STOP_REQUESTED in STARTING   -> STOPPING (init aborted by the stop chain)
 *  - project switch during init   same as stop-during-initialize; the switch's start queues behind
 *  - LSP disabled while starting  LSP_DISABLED in STARTING     -> STOPPING
 *  - repeated crashes            CRASH in RESTART_BACKOFF      -> stays RESTART_BACKOFF until the
 *                                  rolling-window cap trips (P1c policy), then CRASH_GIVE_UP -> FAILED_DISABLED
 *  - repair while running         REPAIR_REQUESTED in RUNNING  -> STOPPING (repair stops the server first)
 */
enum class LspLifecycleState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    RESTART_BACKOFF,
    FAILED_DISABLED,
}

enum class LspLifecycleEvent {
    START_REQUESTED,
    START_SUCCEEDED,
    START_FAILED,
    STOP_REQUESTED,
    STOP_COMPLETED,
    CRASH,
    CRASH_GIVE_UP,
    MANUAL_RETRY,
    REPAIR_REQUESTED,
    LSP_DISABLED,
}

/**
 * What a caller should do with a start command, given the machine state.
 */
sealed class LifecycleDecision {
    /** Run the start now (state allows it). */
    object Proceed : LifecycleDecision()

    /**
     * A stop is in flight — do NOT spawn now; queue behind it on the serialized
     * lifecycle executor so the pair runs stop-then-start in order.
     */
    object QueueAfterStop : LifecycleDecision()

    /** A start is already in flight or the server already runs — do nothing. */
    object Dedup : LifecycleDecision()

    /** Crash cap tripped and no manual retry — auto-start is BLOCKED. */
    object BlockedDisabled : LifecycleDecision()
}

object LspStateMachine {

    /** The full transition table. Unknown pairings fall through unchanged. */
    fun next(state: LspLifecycleState, event: LspLifecycleEvent): LspLifecycleState = when (event) {
        LspLifecycleEvent.START_REQUESTED -> when (state) {
            LspLifecycleState.STOPPED, LspLifecycleState.RESTART_BACKOFF -> LspLifecycleState.STARTING
            // start during stop: the state stays STOPPING; the command queues behind
            LspLifecycleState.STOPPING -> LspLifecycleState.STOPPING
            // start while starting / running: dedupe — a healthy instance already exists
            LspLifecycleState.STARTING, LspLifecycleState.RUNNING -> state
            // blocked until a MANUAL_RETRY clears it
            LspLifecycleState.FAILED_DISABLED -> LspLifecycleState.FAILED_DISABLED
        }
        LspLifecycleEvent.START_SUCCEEDED ->
            if (state == LspLifecycleState.STARTING) LspLifecycleState.RUNNING else state
        LspLifecycleEvent.START_FAILED ->
            if (state == LspLifecycleState.STARTING) LspLifecycleState.RESTART_BACKOFF else state
        LspLifecycleEvent.STOP_REQUESTED -> when (state) {
            // stop during initialize: STARTING -> STOPPING is a DEFINED transition
            LspLifecycleState.STOPPED -> LspLifecycleState.STOPPED
            else -> LspLifecycleState.STOPPING
        }
        LspLifecycleEvent.STOP_COMPLETED -> LspLifecycleState.STOPPED
        LspLifecycleEvent.CRASH -> when (state) {
            LspLifecycleState.RUNNING, LspLifecycleState.STARTING -> LspLifecycleState.RESTART_BACKOFF
            // death during a stop chain is the stop COMPLETING, not a crash
            LspLifecycleState.STOPPING -> LspLifecycleState.STOPPING
            else -> state
        }
        LspLifecycleEvent.CRASH_GIVE_UP -> when (state) {
            // the give-up verdict lands from RESTART_BACKOFF (cap tripped)
            LspLifecycleState.RESTART_BACKOFF, LspLifecycleState.RUNNING, LspLifecycleState.STARTING -> LspLifecycleState.FAILED_DISABLED
            else -> LspLifecycleState.FAILED_DISABLED
        }
        LspLifecycleEvent.MANUAL_RETRY -> when (state) {
            // manual retry is the ONLY exit from FAILED_DISABLED
            LspLifecycleState.FAILED_DISABLED, LspLifecycleState.RESTART_BACKOFF -> LspLifecycleState.STOPPED
            else -> state
        }
        LspLifecycleEvent.REPAIR_REQUESTED -> when (state) {
            // repair while running: stop first, then swap the install
            LspLifecycleState.RUNNING, LspLifecycleState.STARTING, LspLifecycleState.STOPPING -> LspLifecycleState.STOPPING
            LspLifecycleState.STOPPED, LspLifecycleState.FAILED_DISABLED, LspLifecycleState.RESTART_BACKOFF -> LspLifecycleState.STOPPED
        }
        // LSP disabled while starting: abort the start through the stop chain
        LspLifecycleEvent.LSP_DISABLED -> when (state) {
            LspLifecycleState.STOPPED, LspLifecycleState.FAILED_DISABLED -> state
            else -> LspLifecycleState.STOPPING
        }
    }

    /** Decision for an incoming start command. */
    fun startDecision(state: LspLifecycleState): LifecycleDecision = when (state) {
        LspLifecycleState.STOPPED, LspLifecycleState.RESTART_BACKOFF -> LifecycleDecision.Proceed
        LspLifecycleState.STARTING, LspLifecycleState.RUNNING -> LifecycleDecision.Dedup
        LspLifecycleState.STOPPING -> LifecycleDecision.QueueAfterStop
        LspLifecycleState.FAILED_DISABLED -> LifecycleDecision.BlockedDisabled
    }

    /**
     * P1a: whether a queued PER-LANGUAGE stop should still execute when it finally
     * runs. The stop captured the server INSTANCE it was aimed at; if the registered
     * server was replaced (crash + auto-restart, or another project's start) between
     * request and run, the stop is STALE and must be discarded — killing the
     * replacement would tear down a server its requester never knew about.
     * stopAll (toggle-off, teardown) is exempt: it stops whatever is registered.
     */
    fun shouldExecuteStop(currentServerIsCapturedInstance: Boolean, stopAll: Boolean): Boolean =
        stopAll || currentServerIsCapturedInstance

    /**
     * P1a: after a stop chain completes, whether the shared state may be marked
     * STOPPED. If the language's generation moved past the captured one, a newer
     * start completed in between and the STOPPED mark would clobber a live server.
     */
    fun shouldMarkStopped(capturedGeneration: Int, currentGeneration: Int): Boolean =
        capturedGeneration == currentGeneration
}

/**
 * P1a: stop-request coordination with IN-FLIGHT DEDUPE.
 *
 * Every stop goes through here: requestStop() returns immediately (callers on ANY
 * thread — including MAIN — never block) and identical concurrent requests share
 * one future. The action itself runs on the single-thread lifecycle executor, so
 * stops serialize with starts and with each other.
 */
class StopCoordinator(private val executor: Executor) {

    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /**
     * Queue a stop for [key]. Returns a future that completes when the stop chain
     * finishes. If a stop for the same key is already queued or running, the SAME
     * future is returned — one stop chain, however many callers asked.
     */
    fun requestStop(key: String, action: () -> Unit): CompletableFuture<Unit> {
        val existing = inFlight[key]
        if (existing != null) return existing
        val created = CompletableFuture<Unit>()
        val winner = inFlight.putIfAbsent(key, created)
        if (winner != null) return winner
        executor.execute {
            try {
                action()
                created.complete(Unit)
            } catch (t: Throwable) {
                created.completeExceptionally(t)
            } finally {
                // remove only OUR entry — a newer request may already be queued
                inFlight.remove(key, created)
            }
        }
        return created
    }

    fun isStopInFlight(key: String): Boolean = inFlight.containsKey(key)
}

/**
 * P1a: the hoisted open-doc registry decision (didOpen dedupe), extracted pure
 * so the registry contract is JVM-pinned. Inputs are the manager-side registry
 * state (trackedDocuments) — the per-pane lspOpenedFiles mirror is NOT consulted;
 * the SERVER's knowledge is the single source of truth.
 */
sealed class DidOpenAction {
    /** Server never saw this document — send textDocument/didOpen. */
    object SendDidOpen : DidOpenAction()

    /** Server already holds exactly this content — NO duplicate didOpen (protocol-illegal). */
    object DedupeNoop : DidOpenAction()

    /** Server holds the document but content drifted while the pane was away —
     *  sync via didChange with a monotonically higher version, never a second didOpen. */
    data class DedupeSyncViaDidChange(val version: Int) : DidOpenAction()
}

fun didOpenDecision(
    trackedVersion: Int?,
    trackedContent: String?,
    text: String,
    nextVersionCounter: Int,
): DidOpenAction = when {
    trackedVersion == null || trackedContent == null -> DidOpenAction.SendDidOpen
    trackedContent == text -> DidOpenAction.DedupeNoop
    else -> DidOpenAction.DedupeSyncViaDidChange(
        // versions stay MONOTONIC: at least tracked+1, and never below the global counter
        maxOf(nextVersionCounter, trackedVersion + 1),
    )
}
