package com.codespace.ide.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FSW-1(a) (BATCH4-P2) JVM tests for the Explorer mtime-fingerprint rescan:
 * change detection (add/rename/touch/collapsed-child), the never-scan-
 * unexpanded-children guarantee (node_modules/.git stay out unless expanded),
 * caps with change detection still holding through the dir-mtime header, and
 * the hard time budget.
 */
class ExplorerFingerprintTest {

    @After
    fun tearDown() {
        ExplorerFingerprintCache.clearForTests()
    }

    private fun tempProject(): File = java.nio.file.Files.createTempDirectory("fsw1a").toFile()

    private fun scan(root: File, expanded: Set<String> = emptySet()) =
        ExplorerFingerprint.scanVisibleTree(root, expanded)

    @Test
    fun stableTree_stableFingerprint() {
        val root = tempProject()
        File(root, "a.txt").writeText("hello")
        assertEquals("nothing changed between scans", scan(root).value, scan(root).value)
    }

    @Test
    fun addFile_changesFingerprint() {
        val root = tempProject()
        val s1 = scan(root)
        Thread.sleep(5) // ensure a distinguishable mtime on slow clocks
        File(root, "new.txt").writeText("x")
        assertNotEquals("a new file must change the fingerprint", s1.value, scan(root).value)
    }

    @Test
    fun renameFile_changesFingerprint() {
        val root = tempProject()
        File(root, "old.txt").writeText("x")
        val s1 = scan(root)
        assertTrue(File(root, "old.txt").renameTo(File(root, "new.txt")))
        assertNotEquals(s1.value, scan(root).value)
    }

    @Test
    fun unexpandedChildren_areNeverListed_butCollapsedChildChangeIsStillDetected() {
        val root = tempProject()
        val nested = File(root, "node_modules").apply { mkdirs() }
        val s1 = scan(root, expanded = setOf(nested.absolutePath))
        assertFalse("the COLLAPSED child's contents are never in the fingerprint",
            s1.value.contains("inside-collapsed"))
        // A file created inside an UNEXPANDED dir: the new name is not in the
        // fingerprint (we never list unexpanded contents), but the child's own
        // mtime entry changes, so the rescan still triggers a tree rebuild.
        val collapsed = File(root, "collapsed").apply { mkdirs() }
        val s2 = scan(root, expanded = setOf(nested.absolutePath))
        Thread.sleep(5)
        File(collapsed, "inside-collapsed.txt").writeText("x")
        val s3 = scan(root, expanded = setOf(nested.absolutePath))
        assertFalse("new file inside an unexpanded dir is never listed", s3.value.contains("inside-collapsed"))
        assertNotEquals("but the collapsed dir's mtime entry changes, so the rescan still fires", s2.value, s3.value)
    }

    @Test
    fun expandedDir_listsItsChildren() {
        val root = tempProject()
        val sub = File(root, "src").apply { mkdirs() }
        File(sub, "Main.kt").writeText("fun main() {}")
        val s = scan(root, expanded = setOf(sub.absolutePath))
        assertTrue("an expanded dir lists its children", s.value.contains("Main.kt"))
        assertEquals(2, s.dirsScanned) // root + src
    }

    @Test
    fun capsHit_changeDetectionStillHolds_viaDirMtimeHeader() {
        val root = tempProject()
        repeat(600) { File(root, "f$it.txt").writeText("x") } // over the 500/dir cap
        val s1 = scan(root)
        assertTrue("per-dir cap must be reported", s1.capped)
        assertEquals(s1.value, scan(root).value) // same tree, same capped fingerprint
        Thread.sleep(5)
        File(root, "beyond-the-cap.txt").writeText("x")
        assertNotEquals(
            "a change beyond the cap must still be detected via the dir mtime header",
            s1.value, scan(root).value,
        )
    }

    @Test
    fun exhaustedBudget_timesOut_withPartialComparableResult() {
        val root = tempProject()
        File(root, "a.txt").writeText("x")
        val s = ExplorerFingerprint.scanVisibleTree(root, emptySet(), timeBudgetMs = -1)
        assertTrue("the hard time budget must be reported", s.timedOut)
        assertEquals("a budget-exhausted scan is empty but comparable", "", s.value)
    }

    @Test
    fun cache_firstScanIsBaseline_secondScanReportsChange() {
        val root = tempProject()
        ExplorerFingerprintCache.clearForTests()
        assertTrue(ExplorerFingerprintCache.beginScan(root.absolutePath))
        assertFalse("first scan with no baseline reports NO change (stores the baseline)",
            ExplorerFingerprintCache.finishScan(root.absolutePath, "v1"))
        assertTrue(ExplorerFingerprintCache.beginScan(root.absolutePath))
        assertTrue("different fingerprint reports a change",
            ExplorerFingerprintCache.finishScan(root.absolutePath, "v2"))
        assertTrue(ExplorerFingerprintCache.beginScan(root.absolutePath))
        assertFalse("same fingerprint reports no change",
            ExplorerFingerprintCache.finishScan(root.absolutePath, "v2"))
    }

    @Test
    fun cache_oneScanInFlightPerRoot_secondStartIsRefused() {
        ExplorerFingerprintCache.clearForTests()
        val root = tempProject().absolutePath
        assertTrue(ExplorerFingerprintCache.beginScan(root))
        assertFalse("rate limit: an in-flight scan blocks a second", ExplorerFingerprintCache.beginScan(root))
        ExplorerFingerprintCache.abandonScan(root)
        assertTrue("after abandon, a new scan may start", ExplorerFingerprintCache.beginScan(root))
    }
}
