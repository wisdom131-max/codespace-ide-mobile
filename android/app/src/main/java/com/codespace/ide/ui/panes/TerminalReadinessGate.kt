package com.codespace.ide.ui.panes

import kotlinx.coroutines.delay

/**
 * D13 (2026-10-02): readiness gate for the terminal auto-start. Replaces the
 * fixed 8-second delay — a race-condition mitigation that GUESSED how long
 * Activity recreation + service bind take. Polls real readiness signals:
 *  - Activity resumed (TerminalPane's isActivityVisible, driven by its
 *    LifecycleEventObserver ON_RESUME/ON_PAUSE)
 *  - terminal service bound (TerminalPane's boundService != null)
 *  - no install in flight (ProotInstaller.installingTabId == null)
 *
 * Falls through after [maxWaitMs] and returns false so the caller can log it —
 * starting anyway matches the existing Back-handler path (boundService ?:
 * createTerminalSession fallback), so a stalled bind can't wedge the pane.
 *
 * Own file per the 64KB bytecode rule: never inline new effect logic into
 * TerminalPane's composable body.
 */
internal suspend fun awaitTerminalReadiness(
    activityResumed: () -> Boolean,
    serviceBound: () -> Boolean,
    // Owner-approved 2026-10-03: 50ms poll — worst-case gate overhead one
    // poll interval (was 250ms; the gate is condition-based so common-case
    // latency is still one iteration when the signals already hold).
    pollMs: Long = 50L,
    maxWaitMs: Long = 30_000L,
): Boolean {
    val start = android.os.SystemClock.elapsedRealtime()
    // F1 item 3 (advisor, 2026-10-09): a session #2-style silent 30s stall (ok=false,
    // gate-pass logs nothing until the cap) left no evidence of WHICH condition was
    // stuck. Rate-limited diagnostics: exactly one line at 2s naming every false
    // condition, one more near the cap — never per-poll spam.
    var warned2s = false
    var warnedCap = false
    while (true) {
        val activityOk = activityResumed()
        val serviceOk = serviceBound()
        val installBusy = com.codespace.ide.terminal.ProotInstaller.installingTabId != null
        if (activityOk && serviceOk && !installBusy) return true
        val elapsed = android.os.SystemClock.elapsedRealtime() - start
        if (!warned2s && elapsed >= 2_000L) {
            warned2s = true
            val stuck = buildList {
                if (!activityOk) add("activity-not-resumed")
                if (!serviceOk) add("service-not-bound")
                if (installBusy) add("install-busy (tab=" + com.codespace.ide.terminal.ProotInstaller.installingTabId + ")")
            }.joinToString(" + ")
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "terminal-readiness gate: waited ${elapsed}ms, still waiting on: $stuck", "terminal")
        }
        if (!warnedCap && elapsed >= maxWaitMs - 5_000L) {
            warnedCap = true
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "terminal-readiness gate: ${elapsed}ms — approaching the ${maxWaitMs}ms cap, starting anyway (safety)", "terminal")
        }
        if (elapsed >= maxWaitMs) return false
        delay(pollMs)
    }
}
