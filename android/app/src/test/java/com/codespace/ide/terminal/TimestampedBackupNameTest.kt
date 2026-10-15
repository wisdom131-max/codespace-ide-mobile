package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
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
}
