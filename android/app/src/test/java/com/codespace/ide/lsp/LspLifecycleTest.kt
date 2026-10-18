package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BATCH9-P1a (advisor ruling 2026-10-18): the server-lifecycle contract, pinned
 * in pure JVM. LspManager stays the runtime owner; these tests pin the DECISIONS
 * it delegates to LspStateMachine / StopCoordinator / didOpenDecision — the
 * advisor's required matrix:
 *
 *  - a stop NEVER blocks its caller (MAIN thread included)
 *  - in-flight stop dedupe
 *  - start during stop (queues behind it)
 *  - stale-generation stop-result discard
 *  - duplicate-didOpen guard (hoisted open-doc registry)
 *  - repair ordering (stop runs to completion BEFORE the install begins)
 *  - transition definitions: stop during initialize, LSP disabled while starting,
 *    repeated crashes, repair while running, manual retry exits FAILED_DISABLED
 */
class LspLifecycleTest {

    // ── Transition matrix ─────────────────────────────────────────────────────

    @Test fun startDuringStop_queuesBehindTheStop() {
        // start during stop: state stays STOPPING — the start command QUEUES on the
        // single lifecycle executor and runs after the stop completes.
        assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.STOPPING, LspLifecycleEvent.START_REQUESTED))
        assertEquals(LifecycleDecision.QueueAfterStop, LspStateMachine.startDecision(LspLifecycleState.STOPPING))
        // and after the stop completes, the queued start proceeds
        assertEquals(LspLifecycleState.STARTING, LspStateMachine.next(LspLifecycleState.STOPPED, LspLifecycleEvent.START_REQUESTED))
    }

    @Test fun stopDuringInitialize_isADefinedTransition() {
        // project switch during initialize / explicit stop during initialize:
        // STARTING -> STOPPING (the stop chain aborts the init), then STOPPED.
        assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.STARTING, LspLifecycleEvent.STOP_REQUESTED))
        assertEquals(LspLifecycleState.STOPPED, LspStateMachine.next(LspLifecycleState.STOPPING, LspLifecycleEvent.STOP_COMPLETED))
    }

    @Test fun lspDisabledWhileStarting_routesThroughTheStopChain() {
        assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.STARTING, LspLifecycleEvent.LSP_DISABLED))
        // a STARTING server that succeeds while the disable-stop queues does not resurrect the machine
        assertEquals(LspLifecycleState.RUNNING, LspStateMachine.next(LspLifecycleState.STARTING, LspLifecycleEvent.START_SUCCEEDED))
    }

    @Test fun repeatedCrashes_backoffThenGiveUp_thenManualRetryOnly() {
        // repeated crashes: RUNNING -> RESTART_BACKOFF and stays there
        assertEquals(LspLifecycleState.RESTART_BACKOFF, LspStateMachine.next(LspLifecycleState.RUNNING, LspLifecycleEvent.CRASH))
        assertEquals(LspLifecycleState.RESTART_BACKOFF, LspStateMachine.next(LspLifecycleState.RESTART_BACKOFF, LspLifecycleEvent.CRASH))
        // the cap trips -> FAILED_DISABLED; auto-start is BLOCKED...
        assertEquals(LspLifecycleState.FAILED_DISABLED, LspStateMachine.next(LspLifecycleState.RESTART_BACKOFF, LspLifecycleEvent.CRASH_GIVE_UP))
        assertEquals(LifecycleDecision.BlockedDisabled, LspStateMachine.startDecision(LspLifecycleState.FAILED_DISABLED))
        assertEquals(LspLifecycleState.FAILED_DISABLED, LspStateMachine.next(LspLifecycleState.FAILED_DISABLED, LspLifecycleEvent.START_REQUESTED))
        // ...and MANUAL retry is the ONLY exit
        assertEquals(LspLifecycleState.STOPPED, LspStateMachine.next(LspLifecycleState.FAILED_DISABLED, LspLifecycleEvent.MANUAL_RETRY))
        assertEquals(LifecycleDecision.Proceed, LspStateMachine.startDecision(LspLifecycleState.STOPPED))
    }

    @Test fun repairWhileRunning_stopsFirst() {
        assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.RUNNING, LspLifecycleEvent.REPAIR_REQUESTED))
        assertEquals(LspLifecycleState.STOPPED, LspStateMachine.next(LspLifecycleState.STOPPED, LspLifecycleEvent.REPAIR_REQUESTED))
        assertEquals(LspLifecycleState.STOPPED, LspStateMachine.next(LspLifecycleState.FAILED_DISABLED, LspLifecycleEvent.REPAIR_REQUESTED))
    }

    @Test fun healthyStatesDedupeStarts() {
        // start while starting / running: a live instance exists — dedupe
        assertEquals(LifecycleDecision.Dedup, LspStateMachine.startDecision(LspLifecycleState.STARTING))
        assertEquals(LifecycleDecision.Dedup, LspStateMachine.startDecision(LspLifecycleState.RUNNING))
        // START_REQUESTED in RUNNING leaves the state untouched
        assertEquals(LspLifecycleState.RUNNING, LspStateMachine.next(LspLifecycleState.RUNNING, LspLifecycleEvent.START_REQUESTED))
    }

    @Test fun crashDuringStopIsTheStopCompleting_notACrash() {
        assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.STOPPING, LspLifecycleEvent.CRASH))
        assertEquals(LspLifecycleState.STOPPED, LspStateMachine.next(LspLifecycleState.STOPPING, LspLifecycleEvent.STOP_COMPLETED))
    }

    // ── Stale-generation discard ─────────────────────────────────────────────

    @Test fun staleStopRequestsAreDiscarded() {
        // the registered server was REPLACED between request and run: the per-language
        // stop must NOT kill the replacement
        assertFalse(LspStateMachine.shouldExecuteStop(currentServerIsCapturedInstance = false, stopAll = false))
        // identity match: execute
        assertTrue(LspStateMachine.shouldExecuteStop(currentServerIsCapturedInstance = true, stopAll = false))
        // stopAll (toggle off, teardown) stops whatever is running — no discard
        assertTrue(LspStateMachine.shouldExecuteStop(currentServerIsCapturedInstance = false, stopAll = true))
    }

    @Test fun staleStopResultsDoNotClobberNewerState() {
        // a stop that finishes while a NEWER generation already started must not
        // mark the language STOPPED and clobber the live server's state
        assertFalse(LspStateMachine.shouldMarkStopped(capturedGeneration = 3, currentGeneration = 4))
        assertTrue(LspStateMachine.shouldMarkStopped(capturedGeneration = 3, currentGeneration = 3))
    }

    // ── StopCoordinator: never blocks the caller, in-flight dedupe, ordering ──

    @Test fun stopRequestNeverBlocksItsCaller() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val actionStarted = CountDownLatch(1)
            val releaseAction = CountDownLatch(1)
            val coordinator = StopCoordinator(executor)
            val callerThread = Thread {
                coordinator.requestStop("JAVA") {
                    actionStarted.countDown()
                    releaseAction.await()
                }
            }
            callerThread.start()
            // the caller thread finishes while the stop chain is still running
            callerThread.join(2000)
            assertFalse("requestStop must return before the action completes — the caller never blocks", callerThread.isAlive)
            assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun inFlightStopRequestsShareOneStopChain() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val runs = AtomicInteger()
            val releaseAction = CountDownLatch(1)
            val coordinator = StopCoordinator(executor)
            val first = coordinator.requestStop("JAVA") { runs.incrementAndGet(); releaseAction.await() }
            val second = coordinator.requestStop("JAVA") { runs.incrementAndGet() }
            assertSame("a second request while one is in flight must return the SAME future", first, second)
            releaseAction.countDown()
            first.get(2, TimeUnit.SECONDS)
            assertEquals("the stop chain ran exactly once despite two requests", 1, runs.get())
            // after completion a NEW stop request queues a fresh chain (not deduped forever)
            val third = coordinator.requestStop("JAVA") { runs.incrementAndGet() }
            third.get(2, TimeUnit.SECONDS)
            assertEquals(2, runs.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun stopThenStart_serializeInOrder() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val order = CopyOnWriteArrayList<String>()
            val coordinator = StopCoordinator(executor)
            coordinator.requestStop("JAVA") { order.add("stop") }
            // a start submitted after the stop runs AFTER it — stop-before-start
            val startFuture = java.util.concurrent.CompletableFuture<Unit>()
            executor.execute {
                order.add("start")
                startFuture.complete(Unit)
            }
            startFuture.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("stop", "start"), order)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun repairRunsOnlyAfterTheStopCompleted() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val order = CopyOnWriteArrayList<String>()
            val coordinator = StopCoordinator(executor)
            // repair while running: stop first (machine transition), the swap queues behind
            assertEquals(LspLifecycleState.STOPPING, LspStateMachine.next(LspLifecycleState.RUNNING, LspLifecycleEvent.REPAIR_REQUESTED))
            coordinator.requestStop("JAVA") { order.add("stop:begin"); Thread.sleep(100); order.add("stop:end") }
            val installFuture = java.util.concurrent.CompletableFuture<Unit>()
            executor.execute {
                order.add("install")
                installFuture.complete(Unit)
            }
            installFuture.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("stop:begin", "stop:end", "install"), order)
        } finally {
            executor.shutdownNow()
        }
    }

    // ── Hoisted open-doc registry (didOpen dedupe) ────────────────────────────

    @Test fun firstOpenSendsDidOpen() {
        assertEquals(DidOpenAction.SendDidOpen, didOpenDecision(trackedVersion = null, trackedContent = null, text = "abc", nextVersionCounter = 1))
    }

    @Test fun identicalReOpenIsANoop() {
        // pane switch re-fires Effect-A with unchanged content — no duplicate didOpen
        assertEquals(DidOpenAction.DedupeNoop, didOpenDecision(trackedVersion = 4, trackedContent = "abc", text = "abc", nextVersionCounter = 9))
    }

    @Test fun driftedReOpenSyncsViaDidChangeWithMonotonicVersion() {
        // content drifted while the pane was away: didChange, never a second didOpen
        val decision = didOpenDecision(trackedVersion = 4, trackedContent = "abc", text = "abcd", nextVersionCounter = 9)
        assertTrue(decision is DidOpenAction.DedupeSyncViaDidChange)
        assertEquals(9, (decision as DidOpenAction.DedupeSyncViaDidChange).version)
        // the global counter must never move BACKWARDS: at least tracked+1
        val behind = didOpenDecision(trackedVersion = 4, trackedContent = "abc", text = "abcd", nextVersionCounter = 2)
        assertEquals(5, (behind as DidOpenAction.DedupeSyncViaDidChange).version)
    }
}
