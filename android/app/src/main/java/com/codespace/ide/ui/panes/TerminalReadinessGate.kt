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
    pollMs: Long = 250L,
    maxWaitMs: Long = 30_000L,
): Boolean {
    val start = android.os.SystemClock.elapsedRealtime()
    while (true) {
        val installBusy = com.codespace.ide.terminal.ProotInstaller.installingTabId != null
        if (activityResumed() && serviceBound() && !installBusy) return true
        if (android.os.SystemClock.elapsedRealtime() - start >= maxWaitMs) return false
        delay(pollMs)
    }
}
