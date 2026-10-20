package com.codespace.ide.lsp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P1e (advisor 2026-10-20): decision-table tests for the Settings "Language
 * servers" status row — the pure half of the section (LspManager.statusRows
 * only assembles already-tracked state into these). The ROW rendering is
 * device-proven by the short sheet; the state labels, uptime math and
 * placeholder degradation are pinned here.
 */
class LspStatusTest {

    private val NOW = 1_700_000_000_000L

    // ── formatState: all nine states, plain words, one line each ────────────

    @Test fun stateLabels_allNine_arePlainWords() {
        assertEquals("Stopped", LspStatus.formatState(LspState.STOPPED))
        assertEquals("Starting…", LspStatus.formatState(LspState.STARTING))
        assertEquals("Initializing…", LspStatus.formatState(LspState.INITIALIZING))
        assertEquals("Running", LspStatus.formatState(LspState.READY))
        assertEquals("Unhealthy", LspStatus.formatState(LspState.UNHEALTHY))
        assertEquals("Restarting…", LspStatus.formatState(LspState.RESTARTING))
        assertEquals("Stopping…", LspStatus.formatState(LspState.STOPPING))
        assertEquals("Stopping (idle)", LspStatus.formatState(LspState.IDLE_CLOSE))
        assertEquals("Disabled — crash cap (tap Retry to re-enable)", LspStatus.formatState(LspState.FAILED_DISABLED))
    }

    // ── formatUptime boundaries ────────────────────────────────────────────

    @Test fun uptime_neverStarted_isPlaceholder() {
        assertEquals("—", LspStatus.formatUptime(0L, NOW))
        assertEquals("—", LspStatus.formatUptime(-1L, NOW))
        // A future start time is corrupt, not an uptime — degrade to the same placeholder.
        assertEquals("—", LspStatus.formatUptime(NOW + 5_000L, NOW))
    }

    @Test fun uptime_secondsMinutesHours() {
        assertEquals("45s", LspStatus.formatUptime(NOW - 45_000L, NOW))
        assertEquals("2m 5s", LspStatus.formatUptime(NOW - 125_000L, NOW))
        assertEquals("1h 2m", LspStatus.formatUptime(NOW - 3_720_000L, NOW))
        assertEquals("0s", LspStatus.formatUptime(NOW, NOW))
    }

    // ── buildRow: the decision table the Settings section renders ─────────

    @Test fun row_neverStartedLanguage_degradesToPlaceholders() {
        val row = LspStatus.buildRow(
            languageName = "Java",
            state = LspState.STOPPED,
            project = null,
            startedAtMs = 0L,
            nowMs = NOW,
            restartCount = 0,
            lastStopReason = null,
        )
        assertEquals(LspStatusRow("Java", "Stopped", "—", "—", 0, "—"), row)
    }

    @Test fun row_runningServer_showsProjectUptimeRestarts() {
        val row = LspStatus.buildRow(
            languageName = "TypeScript",
            state = LspState.READY,
            project = "/sdcard/My codespace app 3",
            startedAtMs = NOW - 600_000L,
            nowMs = NOW,
            restartCount = 2,
            lastStopReason = "project-switch",
        )
        assertEquals(LspStatusRow("TypeScript", "Running", "/sdcard/My codespace app 3", "10m 0s", 2, "project-switch"), row)
    }

    @Test fun row_failedDisabled_showsCrashCapReasonAndCount() {
        val row = LspStatus.buildRow(
            languageName = "Python",
            state = LspState.FAILED_DISABLED,
            project = "  ",
            startedAtMs = NOW - 30_000L,
            nowMs = NOW,
            restartCount = 4,
            lastStopReason = "crash (exit=137)",
        )
        // Blank project degrades to the placeholder, never to a blank line.
        assertEquals(LspStatusRow(
            "Python", "Disabled — crash cap (tap Retry to re-enable)", "—", "30s", 4, "crash (exit=137)"), row)
    }

    @Test fun row_idleStoppedServer_keepsLastStopReasonAndProject() {
        val row = LspStatus.buildRow(
            languageName = "Kotlin",
            state = LspState.STOPPED,
            project = "/sdcard/proj",
            startedAtMs = 0L,
            nowMs = NOW,
            restartCount = 0,
            lastStopReason = "background-idle-10",
        )
        // Stopped server: no uptime (process gone), but the last stop reason survives.
        assertEquals(LspStatusRow("Kotlin", "Stopped", "/sdcard/proj", "—", 0, "background-idle-10"), row)
    }

    @Test fun row_negativeRestartCount_isClamped() {
        val row = LspStatus.buildRow(
            languageName = "Java", state = LspState.STOPPED, project = null,
            startedAtMs = 0L, nowMs = NOW, restartCount = -3, lastStopReason = null,
        )
        assertEquals(0, row.restartCount)
    }
}
