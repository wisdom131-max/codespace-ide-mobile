package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Advisor 4a (2026-10-15, approved): the timestamped pre-restore backup naming
 * contract. The "Back up current first" choice must NEVER overwrite
 * container-backup.tar.gz, its .prev, or the file being restored — the name
 * generator is the single place that guarantees it.
 */
class TimestampedBackupNameTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    private fun stampOf(s: String): Long =
        SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).parse(s)!!.time

    @Test
    fun nameMatchesAdvisorFormatForFixedStamp() {
        val stamp = stampOf("20261015-1605")
        val f = BackupManager.nextTimestampedBackupFile(tmp.root, stamp)
        assertEquals("container-backup-20261015-1605.tar.gz", f.name)
        assertEquals(File(tmp.root, "container-backup-20261015-1605.tar.gz"), f)
    }

    @Test
    fun existingFileBumpsToNumberedVariant() {
        val stamp = stampOf("20261015-1605")
        val taken = File(tmp.root, "container-backup-20261015-1605.tar.gz")
        assertTrue(taken.createNewFile())
        assertEquals(
            "container-backup-20261015-1605 (1).tar.gz",
            BackupManager.nextTimestampedBackupFile(tmp.root, stamp).name,
        )
        // Two taken names keep bumping.
        File(tmp.root, "container-backup-20261015-1605 (1).tar.gz").createNewFile()
        assertEquals(
            "container-backup-20261015-1605 (2).tar.gz",
            BackupManager.nextTimestampedBackupFile(tmp.root, stamp).name,
        )
    }

    @Test
    fun neverOverwriteProtectsTheExactPathEvenWithoutCollisionOnDisk() {
        // The picked restore source may itself live in the backup dir with the
        // name we would generate — the guard must bump WITHOUT needing the
        // file to exist on disk through the collision check alone.
        val stamp = stampOf("20261015-1605")
        val protected = File(tmp.root, "container-backup-20261015-1605.tar.gz")
        val f = BackupManager.nextTimestampedBackupFile(tmp.root, stamp, listOf(protected))
        assertNotEquals(protected, f)
        assertEquals("container-backup-20261015-1605 (1).tar.gz", f.name)
        // The protected path was never created or written.
        assertTrue(!protected.exists())
    }

    @Test
    fun defaultBackupAndPrevAreNeverCandidates() {
        // The DEFAULT backup and its .prev are protected as a matter of course —
        // the timestamped flow must not even consider their names, let alone
        // overwrite them.
        val stamp = stampOf("20261015-1605")
        val def = File(tmp.root, "container-backup.tar.gz")
        val prev = File(tmp.root, "container-backup.tar.gz.prev")
        assertTrue(def.createNewFile())
        assertTrue(prev.createNewFile())
        val f = BackupManager.nextTimestampedBackupFile(
            tmp.root, stamp, listOf(def, prev),
        )
        assertNotEquals(def, f)
        assertNotEquals(prev, f)
        assertEquals("container-backup-20261015-1605.tar.gz", f.name)
    }

    @Test
    fun exhaustionThrowsInsteadOfOverwriting() {
        val stamp = stampOf("20261015-1605")
        val base = "container-backup-20261015-1605"
        File(tmp.root, "$base.tar.gz").createNewFile()
        for (n in 1..100) {
            File(tmp.root, "$base ($n).tar.gz").createNewFile()
        }
        assertThrows(IllegalStateException::class.java) {
            BackupManager.nextTimestampedBackupFile(tmp.root, stamp)
        }
    }

    @Test
    fun retentionKeepsNewestThreeAndProtected() {
        // Advisor 2b: oldest-first input; decision = files to DELETE.
        val f = { n: String -> java.io.File(n) }
        val files = listOf(f("container-backup-20261010-0800.tar.gz"), f("container-backup-20261011-0900.tar.gz"),
            f("container-backup-20261012-1000.tar.gz"), f("container-backup-20261013-1100.tar.gz"), f("container-backup-20261014-1200.tar.gz"))
        // 5 files, keep 3 -> the 2 oldest are pruned.
        assertEquals(listOf("container-backup-20261010-0800.tar.gz", "container-backup-20261011-0900.tar.gz"),
            BackupManager.timestampedRetentionDecision(files, 3, emptySet()).map { it.name })
        // Protected names NEVER appear in the prune set. Real callers protect the
        // just-created NEWEST file; protecting the OLDEST exercises the exclusion
        // hardest: it survives even though it is the oldest.
        assertEquals(listOf("container-backup-20261011-0900.tar.gz"),
            BackupManager.timestampedRetentionDecision(files, 3, setOf("container-backup-20261010-0800.tar.gz")).map { it.name })
        // The just-created (newest) file protected: 4 unprotected remain, keep 3
        // -> only the SINGLE oldest is pruned (total kept = 4: protected + newest 3).
        assertEquals(listOf("container-backup-20261010-0800.tar.gz"),
            BackupManager.timestampedRetentionDecision(files, 3, setOf("container-backup-20261014-1200.tar.gz")).map { it.name })
        // Fewer unprotected than keep -> nothing pruned (never over-deletes).
        val files2 = files.take(4)
        assertEquals(emptyList<String>(),
            BackupManager.timestampedRetentionDecision(files2, 3, setOf("container-backup-20261011-0900.tar.gz")).map { it.name })
        // Fewer than keep+1 -> nothing pruned.
        assertEquals(emptyList<String>(), BackupManager.timestampedRetentionDecision(files.take(3), 3, emptySet()).map { it.name })
        assertEquals(emptyList<String>(), BackupManager.timestampedRetentionDecision(emptyList(), 3, emptySet()).map { it.name })
    }

    @Test
    fun allBackupFilesPatternMatchesAdvisor2b() {
        // Pure check of the listing regex: default, .prev, timestamped in; junk out.
        val rx = Regex("^container-backup(\\.tar\\.gz(\\.prev)?|-\\d{8}-\\d{4}( \\(\\d+\\))?\\.tar\\.gz)$")
        assertTrue(rx.matches("container-backup.tar.gz"))
        assertTrue(rx.matches("container-backup.tar.gz.prev"))
        assertTrue(rx.matches("container-backup-20261015-2200.tar.gz"))
        assertTrue(rx.matches("container-backup-20261015-2200 (2).tar.gz"))
        assertFalse(rx.matches("container-backup-20261015-2200.tar.gz.tmp"))
        assertFalse(rx.matches("other-file.txt"))
        assertFalse(rx.matches("container-backup.tar.gz.tmp"))
    }
}
