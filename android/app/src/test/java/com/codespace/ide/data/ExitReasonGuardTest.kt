package com.codespace.ide.data

import com.codespace.ide.data.ExitReasonClassifier.ExitKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exit-reason guard (owner/advisor order 2026-11-08, item 1).
 *
 * The class of death it exists for: the process dies AFTER the first successful frame in a way that
 * writes no crash log - an ANR the system kills, a low-memory reclaim, a native crash, a failed
 * initialization. Before this, those looked exactly like a clean exit, so the next launch reopened
 * the file that was on screen when the app died.
 */
class ExitReasonGuardTest {

    private val bad = listOf(
        ExitKind.ANR,
        ExitKind.CRASH,
        ExitKind.CRASH_NATIVE,
        ExitKind.LOW_MEMORY,
        ExitKind.SIGNALED,
        ExitKind.INITIALIZATION_FAILURE,
        ExitKind.EXCESSIVE_RESOURCE_USAGE,
    )

    private val notBad = listOf(
        ExitKind.USER_REQUESTED,
        ExitKind.EXIT_SELF,
        ExitKind.OTHER,
    )

    @Test
    fun everyAccidentCounts() {
        bad.forEach { kind ->
            assertTrue("$kind must count as a bad exit", ExitReasonClassifier.isBad(kind))
        }
    }

    @Test
    fun forceStopAndSwipeAwayAreNotAccidents() {
        notBad.forEach { kind ->
            assertFalse("$kind must NOT count as a bad exit", ExitReasonClassifier.isBad(kind))
        }
    }

    @Test
    fun anAnrWithAFileOpenDoesNotReopenIt() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Main.kt",
            fileExists = true,
            previousExitBad = ExitReasonClassifier.isBad(ExitKind.ANR),
            crashDuringRestore = false,
            consecutiveBadStarts = 0,
        )
        assertFalse(decision.restore)
        val note = decision.note ?: ""
        assertTrue(note.contains("Main.kt"))
        assertTrue(note, note.contains("closed unexpectedly"))
    }

    @Test
    fun aLowMemoryKillDoesNotReopenTheFileEither() {
        val decision = RestoreGuard.decide(
            path = "/root/project/big13k.js",
            fileExists = true,
            previousExitBad = ExitReasonClassifier.isBad(ExitKind.LOW_MEMORY),
            crashDuringRestore = false,
            consecutiveBadStarts = 0,
        )
        assertFalse(decision.restore)
        assertTrue((decision.note ?: "").contains("big13k.js"))
    }

    @Test
    fun aUserForceStopReopensNormally() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Main.kt",
            fileExists = true,
            previousExitBad = ExitReasonClassifier.isBad(ExitKind.USER_REQUESTED),
            crashDuringRestore = false,
            consecutiveBadStarts = 0,
        )
        assertTrue("a deliberate exit must not hide the user's files", decision.restore)
        assertEqualsNull(decision.note)
    }

    @Test
    fun aNormalSwipeAwayReopensNormally() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Main.kt",
            fileExists = true,
            previousExitBad = ExitReasonClassifier.isBad(ExitKind.EXIT_SELF),
            crashDuringRestore = false,
            consecutiveBadStarts = 0,
        )
        assertTrue(decision.restore)
        assertEqualsNull(decision.note)
    }

    @Test
    fun repeatedDeathsStopTheAttemptsAndSaySo() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Main.kt",
            fileExists = true,
            previousExitBad = ExitReasonClassifier.isBad(ExitKind.CRASH_NATIVE),
            crashDuringRestore = false,
            consecutiveBadStarts = 2,
        )
        assertFalse(decision.restore)
        assertTrue((decision.note ?: "").contains("keeps closing unexpectedly"))
    }

    @Test
    fun deathDuringRestoreHasItsOwnSentence() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Main.kt",
            fileExists = true,
            previousExitBad = true,
            crashDuringRestore = true,
            consecutiveBadStarts = 0,
        )
        assertFalse(decision.restore)
        assertTrue((decision.note ?: "").contains("closed while restoring"))
    }

    @Test
    fun aMissingFileIsDroppedSilentlyWhicheverWayTheLastLaunchEnded() {
        val decision = RestoreGuard.decide(
            path = "/root/project/Gone.kt",
            fileExists = false,
            previousExitBad = true,
            crashDuringRestore = true,
            consecutiveBadStarts = 3,
        )
        assertFalse(decision.restore)
        assertEqualsNull(decision.note)
    }

    @Test
    fun everyReasonSaysWhatHappenedInPlainWords() {
        assertTrue(ExitReasonClassifier.describe(ExitKind.ANR).contains("responding"))
        assertTrue(ExitReasonClassifier.describe(ExitKind.LOW_MEMORY).contains("memory"))
        assertTrue(ExitReasonClassifier.describe(ExitKind.CRASH_NATIVE).contains("native"))
    }

    private fun assertEqualsNull(note: String?) = assertTrue("expected no note, was: $note", note == null)
}
