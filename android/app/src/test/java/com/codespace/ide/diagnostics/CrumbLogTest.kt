package com.codespace.ide.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Advisor A.2 (2026-10-15): the persistent breadcrumb log contract — capped
 * rotation and secret redaction. The persistent log must NEVER fill the disk
 * and NEVER write a secret.
 */
class CrumbLogTest {

    @get:Rule
    val tmp: TemporaryFolder = TemporaryFolder()

    @Test
    fun crumbsAppendInOrderWithTimestampAndTag() {
        CrumbLog.writeCrumbed(tmp.root, "tap", "link text='spantest.txt:1'")
        CrumbLog.writeCrumbed(tmp.root, "open-file", "opening /x/spantest.txt:1")
        val lines = File(tmp.root, "crumb-log.txt").readLines()
        assertEquals(2, lines.size)
        // Timestamp shape is MM-dd HH:mm:ss.SSS (CI-proven: October lines start with "10").
        assertTrue(lines[0].matches(Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \[tap].*""")))
        assertTrue(lines[0].contains("[tap] link text='spantest.txt:1'"))
        assertTrue(lines[1].contains("[open-file] opening /x/spantest.txt:1"))
    }

    @Test
    fun rotationAtCapKeepsCurrentFileSmallAndOldGeneration() {
        // Small caps so the test is fast; same code path as production.
        val cap = 400
        repeat(60) { i ->
            CrumbLog.writeCrumbed(tmp.root, "loop", "filler line number $i with some padding text to grow the file", maxBytes = cap)
        }
        val f = File(tmp.root, "crumb-log.txt")
        assertTrue("current file stays under the cap", f.length() <= cap + 200)
        assertTrue("rotation happened", File(tmp.root, "crumb-log.txt.old").exists())
        // The newest content is still present in the current file.
        assertTrue(f.readText().contains("number 59"))
    }

    @Test
    fun oldGenerationIsAlsoCappedNewestFirst() {
        val keepOld = 300
        repeat(120) { i ->
            CrumbLog.writeCrumbed(
                tmp.root, "loop",
                "padding padding padding padding padding padding padding $i",
                maxBytes = 500, keepOldBytes = keepOld,
            )
        }
        val old = File(tmp.root, "crumb-log.txt.old")
        assertTrue("old generation capped", old.length() <= keepOld + 200)
        // The tail (newest) of the old generation survives, not the head.
        val text = old.readText()
        assertFalse("oldest content rotated away", text.startsWith("padding padding padding padding padding padding padding 0\n"))
    }

    @Test
    fun secretsNeverReachDisk() {
        CrumbLog.writeCrumbed(tmp.root, "env", "openai_api_key=sk-abc123def456ghi789xyz000 and api_key: hunter2secret")
        val onDisk = File(tmp.root, "crumb-log.txt").readText()
        assertFalse(onDisk.contains("sk-abc123"))
        assertFalse(onDisk.contains("hunter2secret"))
        assertTrue("the key NAME survives for debugging", onDisk.contains("openai_api_key=***"))
    }

    @Test
    fun redactCoversKeyAssignmentsAndTokenPrefixes() {
        val r = CrumbLog.redact("api_key=abc123 Bearer deadbeefcafe token=xyz sk-proj_abcdefghijklmnopqrstuv")
        assertFalse(r.contains("abc123"))
        assertFalse(r.contains("deadbeefcafe"))
        assertFalse(r.contains("xyz"))
        assertFalse(r.contains("sk-proj_abcdefghijklmnopqrstuv"))
        assertTrue(r.contains("api_key=***"))
        assertTrue(r.contains("Bearer ***"))
        assertTrue(r.contains("token=***"))
    }

    @Test
    fun normalPathsAndLinkTextAreNotRedacted() {
        val r = CrumbLog.redact("opening /storage/emulated/0/My Project/Main.kt:42 link text='Main.kt:42'")
        assertEquals("opening /storage/emulated/0/My Project/Main.kt:42 link text='Main.kt:42'", r)
    }

    @Test
    fun plainExitWordsMapsAdvisorPart4() {
        // SIGSYS 31 — the blocked-syscall verdict, in plain words.
        assertEquals("killed by signal 31 (SIGSYS — a blocked syscall)",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_SIGNALED, 31))
        assertEquals("killed by signal 9",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_SIGNALED, 9))
        assertEquals("the app stopped responding (ANR)",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_ANR, 0))
        assertEquals("the system killed the app to free memory (LOW MEMORY)",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, 0))
        assertEquals("a native crash inside the app's own code",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, 0))
        assertEquals("the app closed itself",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_EXIT_SELF, 0))
        assertEquals("you closed the app",
            CrumbLog.plainExitWords(android.app.ApplicationExitInfo.REASON_USER_REQUESTED, 0))
    }

    @Test
    fun crumbTailReturnsLastNLines() {
        val f = tmp.newFile("crumbs.txt")
        f.writeText("l1\nl2\nl3\nl4\n")
        assertEquals(listOf("l3", "l4"), CrumbLog.crumbTailFrom(f, 2))
        assertEquals(emptyList<String>(), CrumbLog.crumbTailFrom(java.io.File(f.parent, "missing.txt"), 5))
    }

    @Test
    fun crumbSyncWritesAndSurvives() {
        // The fsync variant must land in the file like the normal write.
        val d = tmp.newFolder("syncdir")
        CrumbLog.writeCrumbed(d, "tap", "stage 1", sync = true)
        CrumbLog.writeCrumbed(d, "tap", "stage 2", sync = true)
        val text = java.io.File(d, "crumb-log.txt").readText()
        assertTrue(text.contains("stage 1"))
        assertTrue(text.contains("stage 2"))
    }
}
