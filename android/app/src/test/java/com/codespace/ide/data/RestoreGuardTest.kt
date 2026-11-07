package com.codespace.ide.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore crash-loop decision table (owner order 2026-11-07).
 *
 * The failure this pins: a file that crashed the app was reopened on the next launch, so the app
 * crashed at every start and the only recovery was deleting the file from the phone.
 */
class RestoreGuardTest {

    private val path = "/storage/emulated/0/proj/big13k.js"

    private fun decide(
        exists: Boolean = true,
        previousExitBad: Boolean = false,
        crashDuringRestore: Boolean = false,
        badStarts: Int = 0,
    ) = RestoreGuard.decide(path, exists, previousExitBad, crashDuringRestore, badStarts)

    @Test
    fun aNormalExitRestoresEveryFileWithNoNote() {
        val d = decide()
        assertTrue(d.restore)
        assertNull("nothing to tell the user", d.note)
    }

    @Test
    fun aCrashWithTheFileOpenDoesNotReopenItAndSaysSo() {
        val d = decide(previousExitBad = true)
        assertFalse(d.restore)
        val note = d.note ?: error("a skip that hides a file must explain itself")
        assertTrue(note, note.contains("big13k.js"))
        assertTrue(note, note.contains("was not reopened"))
    }

    @Test
    fun aCrashDuringRestoreIsDetectedByTheMarkerEvenWithoutACrashLog() {
        val d = decide(crashDuringRestore = true)
        assertFalse("the marker alone is enough", d.restore)
        assertTrue(d.note!!.contains("closed while restoring"))
    }

    @Test
    fun twoBadStartsInARowSkipEverythingUntilACleanLaunch() {
        val first = decide(previousExitBad = true, badStarts = 1)
        val second = decide(previousExitBad = true, badStarts = 2)
        assertFalse(first.restore)
        assertFalse(second.restore)
        assertTrue("the second note is the firmer one", second.note!!.contains("keeps closing"))
        // A clean start resets the counter, so the next launch restores again.
        val afterClean = decide(previousExitBad = false, badStarts = 0)
        assertTrue(afterClean.restore)
    }

    @Test
    fun aDeletedFileIsDroppedSilently() {
        val d = decide(exists = false, previousExitBad = true)
        assertFalse(d.restore)
        assertNull("a file that is gone needs no apology", d.note)
    }

    @Test
    fun aHugeFileIsNotSkippedByTheRestoreGuard() {
        // Size is not this guard's business: the height guard opens it as a preview instead, so a
        // 13,000-line file comes back (read-only) rather than disappearing from the tab strip.
        val d = decide(exists = true)
        assertTrue("restore decides about reopening, not about size", d.restore)
    }

    @Test
    fun theDecisionIsIndependentOfPathShape() {
        for (p in listOf("/a/b.js", "relative/x.js", "/storage/emulated/0/Android/data/p/files/t.js")) {
            assertTrue(RestoreGuard.decide(p, true, false, false, 0).restore)
            assertFalse(RestoreGuard.decide(p, true, true, false, 0).restore)
        }
    }
}
