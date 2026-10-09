package com.codespace.ide.environment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * F1-1b (owner condition, E17 lesson): the host-side pre-launch validation of the
 * directory proot will be told to start in. exists() alone LIES on FUSE storage —
 * the real probe is a directory LISTING. Pure function, no Android Context, so
 * the JVM suite covers every outcome class directly.
 */
class GuestCwdHostCheckTest {

    @Test
    fun `null host path is rejected with a reason`() {
        val reason = IdeEnvironment.checkGuestDirHostSide(null)
        assertNotNull(reason)
    }

    @Test
    fun `nonexistent directory is rejected`() {
        val missing = File("/nonexistent-path-that-should-not-exist-7f3a")
        val reason = IdeEnvironment.checkGuestDirHostSide(missing)
        assertNotNull(reason)
    }

    @Test
    fun `regular file is rejected as not a directory`() {
        val f = kotlin.io.path.createTempDirectory("f1check").toFile()
        val regular = File(f, "notadir.txt").apply { writeText("x") }
        val reason = IdeEnvironment.checkGuestDirHostSide(regular)
        assertNotNull("a regular file must never be accepted as cwd", reason)
        f.deleteRecursively()
    }

    @Test
    fun `valid readable directory with a working listing passes`() {
        val dir = kotlin.io.path.createTempDirectory("f1ok").toFile()
        File(dir, "entry.txt").writeText("x")
        val reason = IdeEnvironment.checkGuestDirHostSide(dir)
        assertNull("expected OK, got reason: $reason", reason)
        dir.deleteRecursively()
    }

    @Test
    fun `reason strings name the failure class`() {
        assertEquals("host path is null", IdeEnvironment.checkGuestDirHostSide(null))
    }
}
