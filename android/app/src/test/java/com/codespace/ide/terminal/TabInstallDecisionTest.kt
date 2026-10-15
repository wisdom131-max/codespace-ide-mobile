package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Owner round 2026-10-14 (bug 1): the pure state machine behind tab setup.
 *
 * The device bug: a restore never wrote the .ubuntu_version install marker, so
 * isInstalled() stayed false forever — EVERY new tab re-entered the install flow
 * and restored again, replacing the rootfs (changes lost, running shells
 * broken) and leaving the restore row impossible to dismiss.
 *
 * The contract now pinned here (decideTabInstall is the ONE decision every
 * pane consults, and restore success writes the marker — see
 * ProotInstaller.writeVersionMarker):
 *
 *   isInstalled = healthy rootfs (usr/bin/bash) + marker present
 *   -> ATTACH_INSTALLED: spawn a shell only (bug 1d / F3)
 *   restore running    -> WAIT_FOR_RESTORE: wait and re-decide, NEVER a second
 *      restore (single-flight, bug 1b)
 *   no container + a backup exists -> RESTORE_BACKUP (the 1c guard in
 *      restoreBackup still refuses to run over a healthy installed container)
 *   no container, no backup -> FRESH_INSTALL
 *
 * CI-proven here: the DECISION table. The marker WRITE itself touches
 * Context.filesDir (Android) and is proven by the device round trip.
 */
class TabInstallDecisionTest {

    // ── Two tabs opened SEQUENTIALLY after a restore (bug 1 regression) ──────

    @Test
    fun tab2AfterSuccessfulRestoreAttachesShellOnly() {
        // Restore succeeded -> marker written -> isInstalled == true.
        // The second tab MUST attach, never restore again (the old bug:
        // this returned RESTORE_BACKUP and replaced the freshly-restored rootfs).
        val action = BackupManager.decideTabInstall(
            hasBackup = true,
            isInstalled = true,           // marker + bash — the restored container
            restoreRunning = false,
        )
        assertEquals(BackupManager.TabInstallAction.ATTACH_INSTALLED, action)
    }

    @Test
    fun thirdAndFourthTabsAlsoAttachWithoutRestoring() {
        for (i in 3..4) {
            assertEquals(
                BackupManager.TabInstallAction.ATTACH_INSTALLED,
                BackupManager.decideTabInstall(hasBackup = true, isInstalled = true, restoreRunning = false),
            )
        }
    }

    // ── Two tabs opened CONCURRENTLY during a restore (single-flight, bug 1b) ─

    @Test
    fun tab2OpenedWhileRestoreRunsWaitsNeverStartsASecondRestore() {
        // The restore is in flight in tab 1 (marker not yet written).
        val during = BackupManager.decideTabInstall(
            hasBackup = true,
            isInstalled = false,
            restoreRunning = true,
        )
        assertEquals(BackupManager.TabInstallAction.WAIT_FOR_RESTORE, during)
        // The waiting tab must NEVER be handed a restore action while one runs.
        assertFalse(during == BackupManager.TabInstallAction.RESTORE_BACKUP)
    }

    @Test
    fun waitingTabReDecidesToAttachAfterTheRestoreFinishes() {
        // awaitRestoreDone() returned: the marker is written now.
        assertEquals(
            BackupManager.TabInstallAction.ATTACH_INSTALLED,
            BackupManager.decideTabInstall(hasBackup = true, isInstalled = true, restoreRunning = false),
        )
    }

    // ── A restore that FAILS midway (marker never written) ────────────────────

    @Test
    fun restoreFailsMidway_nextTabMayRetryRestore() {
        // The failed restore left the live container untouched and the marker
        // absent — the honest next action for a new tab is a retry (which the
        // restoreBackup guards re-verify from scratch).
        assertEquals(
            BackupManager.TabInstallAction.RESTORE_BACKUP,
            BackupManager.decideTabInstall(hasBackup = true, isInstalled = false, restoreRunning = false),
        )
    }

    // ── The marker contract + fresh-device cases ──────────────────────────────

    @Test
    fun markerWrittenMeansAttachEvenWithBackupPresent() {
        // marker=true && bash healthy == isInstalled; a backup existing on
        // storage must NOT trigger a restore over the installed container.
        assertEquals(
            BackupManager.TabInstallAction.ATTACH_INSTALLED,
            BackupManager.decideTabInstall(hasBackup = true, isInstalled = true, restoreRunning = false),
        )
    }

    @Test
    fun freshDeviceNoBackupInstallsFromScratch() {
        assertEquals(
            BackupManager.TabInstallAction.FRESH_INSTALL,
            BackupManager.decideTabInstall(hasBackup = false, isInstalled = false, restoreRunning = false),
        )
    }

    @Test
    fun noBackupButInstalledStillAttaches() {
        assertEquals(
            BackupManager.TabInstallAction.ATTACH_INSTALLED,
            BackupManager.decideTabInstall(hasBackup = false, isInstalled = true, restoreRunning = false),
        )
    }

    // ── Bug 3: the auto-restore notice machine (kind 3, injectable clock) ─────

    @Test
    fun autoRestoreCountdownDefaultsToRestoreAfterFiveSeconds() {
        var now = 0L
        val m = com.codespace.ide.terminal.InstallChoiceMachine(clock = { now })
        m.begin(3, timeoutMs = 5_000)
        now = 4_000
        assertFalse("one second left on the countdown — still awaiting", m.tick())
        now = 5_000
        assertTrue(m.tick())
        // SKIPPED_TIMEOUT maps to "restore automatically" on the Ubuntu tab.
        assertEquals(InstallChoice.SKIPPED_TIMEOUT, m.outcomeOrNull())
    }

    @Test
    fun restoreNowButtonResolvesImmediately() {
        val m = InstallChoiceMachine(clock = { 0L })
        m.begin(3, timeoutMs = 5_000)
        m.choose(1)
        assertEquals(InstallChoice.GRANT_OR_RETRY, m.outcomeOrNull()) // "Restore now"
    }

    @Test
    fun startFreshInsteadSkipsTheRestore() {
        val m = InstallChoiceMachine(clock = { 0L })
        m.begin(3, timeoutMs = 5_000)
        m.choose(2)
        assertEquals(InstallChoice.DOWNLOAD_FRESH, m.outcomeOrNull()) // "Start fresh instead"
    }

    @Test
    fun dismissedNoticeSkipsTheInstallNotRestores() {
        val m = InstallChoiceMachine(clock = { 0L })
        m.begin(3, timeoutMs = 5_000)
        m.dismiss()
        assertEquals(InstallChoice.SKIPPED_DISMISSED, m.outcomeOrNull())
    }
}
