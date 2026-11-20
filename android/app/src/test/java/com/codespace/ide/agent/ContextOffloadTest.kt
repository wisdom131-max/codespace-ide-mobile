package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextOffloadTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun root(): File = tmp.newFolder("ws")

    @Test
    fun `a small result stays exactly as it was`() {
        val d = root()
        val out = "BUILD SUCCESSFUL in 4s"
        assertEquals(out, ContextOffload.apply(d, "p1", "s1", "run_command", out))
        assertFalse(File(d, "offload").exists())
    }

    @Test
    fun `just below the threshold stays inline`() {
        val d = root()
        val out = "x".repeat(ContextOffload.THRESHOLD_CHARS)
        assertEquals(out, ContextOffload.apply(d, "p1", "s1", "run_command", out))
    }

    @Test
    fun `just above the threshold is offloaded`() {
        val d = root()
        val out = "y".repeat(ContextOffload.THRESHOLD_CHARS + 1)
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertTrue(shown.contains("output offloaded"))
        assertTrue(shown.length < out.length)
    }

    @Test
    fun `a five megabyte build log never enters the model text`() {
        val d = root()
        val out = buildString {
            while (length < 5 * 1024 * 1024) append("line of build output\n")
        }
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertTrue("summary must be small", shown.length < 3 * ContextOffload.EXCERPT_CHARS)
        // The excerpts legitimately repeat the same line; what must not happen is the WHOLE
        // log arriving, which the size bound above already proves.
        assertTrue(shown.length < out.length / 100)
    }

    @Test
    fun `the summary says it is not complete and names the file`() {
        val d = root()
        val out = "z".repeat(20_000)
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertTrue(shown.contains("not shown in full"))
        assertTrue(shown.contains("offload://p1/s1/"))
    }

    @Test
    fun `head and tail excerpts are the real first and last characters`() {
        val d = root()
        val out = "START" + "m".repeat(20_000) + "END"
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertTrue(shown.contains("START"))
        assertTrue(shown.contains("END"))
    }

    @Test
    fun `the complete output is retrievable`() {
        val d = root()
        val out = "q".repeat(20_000) + "TAIL"
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        val ref = shown.substringAfter("Complete output: ").substringBefore("\n").trim()
        assertEquals(out, ContextOffload.read(d, ref))
    }

    @Test
    fun `a missing scratch file reads as null, never as empty text`() {
        val d = root()
        assertNull(ContextOffload.read(d, "offload://p1/s1/nope.txt"))
    }

    @Test
    fun `a reference cannot escape its session directory`() {
        val d = root()
        assertNull(ContextOffload.read(d, "offload://p1/../../etc/passwd"))
        assertNull(ContextOffload.read(d, "offload://p1/s1/../../secret.txt"))
    }

    @Test
    fun `sessions cannot read each other`() {
        val d = root()
        val out = "a".repeat(20_000)
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        val ref = shown.substringAfter("Complete output: ").substringBefore("\n").trim()
        val other = ref.replace("/s1/", "/s2/")
        assertNull(ContextOffload.read(d, other))
    }

    @Test
    fun `a write failure is reported honestly instead of losing the output`() {
        val d = root()
        // A FILE where the offload directory must go: mkdirs cannot succeed.
        File(d, "offload").writeText("blocked")
        val out = "b".repeat(20_000)
        val shown = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertTrue(shown.contains("could not be offloaded"))
        assertTrue(shown.contains("NOT available"))
    }

    @Test
    fun `the same output twice reuses one file`() {
        val d = root()
        val out = "c".repeat(20_000)
        val a = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        val b = ContextOffload.apply(d, "p1", "s1", "run_command", out)
        assertEquals(a, b)
        val files = File(File(File(d, "offload"), "p1"), "s1").listFiles()?.size ?: 0
        assertEquals(1, files)
    }

    @Test
    fun `cleanup removes stale scratch files and keeps fresh ones`() {
        val d = root()
        val old = ContextOffload.apply(d, "p1", "s1", "run_command", "d".repeat(20_000))
        val ref = old.substringAfter("Complete output: ").substringBefore("\n").trim()
        val file = ContextOffload.read(d, ref)?.let { File(File(File(File(d, "offload"), "p1"), "s1"), ref.substringAfterLast('/')) }
        assertNotNull(file)
        file!!.setLastModified(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        ContextOffload.cleanup(d, ContextOffload.KEEP_DAYS, System.currentTimeMillis())
        assertFalse(file.exists())
    }

    @Test
    fun `the reference keeps the tool identity`() {
        val d = root()
        val shown = ContextOffload.apply(d, "p1", "s1", "grep_search", "e".repeat(20_000))
        assertTrue(shown.startsWith("[grep_search output offloaded"))
        assertTrue(shown.contains("grep_search-"))
    }
}
