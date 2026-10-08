package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E17-c JVM suite (advisor ruling 2026-10-08: dialog state machine verified by
 * unit test instead of on-device induction — the rename/fake-backup induction
 * is banned because backup-at-start touches the folder during the window).
 *
 * Covers: grant path, grant timeout abort, dismiss abort, download-fresh choice,
 * the E17-c fail-dialog-retry-fail-dialog-download cycle, timeout and
 * outside-tap aborts for kind 1, and the state-machine edges (choose before
 * begin, double choose, begin resets a prior outcome). No sleeps — the clock
 * is injected.
 */
class InstallChoiceMachineTest {

    /** Fake clock the test advances by hand. */
    private class FakeClock(var now: Long = 1_000_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private fun machine(clock: FakeClock) = InstallChoiceMachine { clock.now }

    @Test
    fun e17bGrantChosen() {
        val c = FakeClock()
        val m = machine(c)
        m.begin(0)
        assertTrue(m.awaiting)
        assertEquals(0, m.kind)
        m.choose(1)
        assertTrue(m.resolved)
        assertEquals(InstallChoice.GRANT_OR_RETRY, m.outcomeOrNull())
    }

    @Test
    fun e17bDownloadFreshChosen() {
        val m = machine(FakeClock())
        m.begin(0)
        m.choose(2)
        assertEquals(InstallChoice.DOWNLOAD_FRESH, m.outcomeOrNull())
    }

    @Test
    fun e17bNoChoiceTimeoutSkipsInstall() {
        val c = FakeClock()
        val m = machine(c)
        m.begin(0, timeoutMs = 600_000)
        assertFalse(m.tick())
        c.advance(599_999)
        assertFalse(m.tick())
        c.advance(1)
        assertTrue(m.tick())
        assertEquals(InstallChoice.SKIPPED_TIMEOUT, m.outcomeOrNull())
    }

    @Test
    fun e17bOutsideTapDismissesAndSkips() {
        val m = machine(FakeClock())
        m.begin(0)
        m.dismiss()
        assertEquals(InstallChoice.SKIPPED_DISMISSED, m.outcomeOrNull())
    }

    @Test
    fun e17cRetryCycleFailDialogRetryFailDialogThenDownload() {
        val c = FakeClock()
        val m = machine(c)
        // First restore failure opens the kind-1 dialog with the reason.
        m.begin(1, reason = "corrupted archive")
        assertEquals(1, m.kind)
        assertEquals("corrupted archive", m.reason)
        m.choose(1) // "Try restore again"
        assertEquals(InstallChoice.GRANT_OR_RETRY, m.outcomeOrNull())
        // Driver retried restoreBackup; it failed again — dialog re-opens.
        c.advance(2_000)
        m.begin(1, reason = "corrupted archive", timeoutMs = 600_000)
        assertTrue(m.awaiting)
        assertNull(m.outcomeOrNull()) // begin reset the prior outcome
        m.choose(2) // "Download fresh" ends the cycle
        assertEquals(InstallChoice.DOWNLOAD_FRESH, m.outcomeOrNull())
    }

    @Test
    fun e17cRetryLoopKeepsFailingThenTimesOut() {
        val c = FakeClock()
        val m = machine(c)
        var rounds = 0
        while (rounds < 3) {
            m.begin(1, reason = "EACCES mid-copy", timeoutMs = 600_000)
            m.choose(1) // retry every time
            rounds++
        }
        m.begin(1, reason = "EACCES mid-copy", timeoutMs = 600_000)
        c.advance(600_001)
        assertTrue(m.tick())
        assertEquals(InstallChoice.SKIPPED_TIMEOUT, m.outcomeOrNull())
    }

    @Test
    fun e17cOutsideTapAbortsAndSkips() {
        val m = machine(FakeClock())
        m.begin(1, reason = "corrupted archive")
        m.dismiss()
        assertEquals(InstallChoice.SKIPPED_DISMISSED, m.outcomeOrNull())
    }

    @Test
    fun choiceBeforeBeginIsIgnored() {
        val m = machine(FakeClock())
        m.choose(1) // no dialog open — must not throw or resolve
        assertFalse(m.resolved)
        assertNull(m.outcomeOrNull())
        m.begin(0)
        m.choose(2)
        assertEquals(InstallChoice.DOWNLOAD_FRESH, m.outcomeOrNull())
    }

    @Test
    fun doubleChooseFirstWins() {
        val m = machine(FakeClock())
        m.begin(0)
        m.choose(1)
        m.choose(2) // ignored — already resolved
        assertEquals(InstallChoice.GRANT_OR_RETRY, m.outcomeOrNull())
    }

    @Test
    fun choiceAfterTimeoutIsIgnored() {
        val c = FakeClock()
        val m = machine(c)
        m.begin(0, timeoutMs = 1000)
        c.advance(1000)
        assertTrue(m.tick())
        assertEquals(InstallChoice.SKIPPED_TIMEOUT, m.outcomeOrNull())
        m.choose(1) // too late — must not resurrect the dialog outcome
        assertEquals(InstallChoice.SKIPPED_TIMEOUT, m.outcomeOrNull())
    }
}
