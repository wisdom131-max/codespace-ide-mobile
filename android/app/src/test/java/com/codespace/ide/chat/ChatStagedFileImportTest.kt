package com.codespace.ide.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * A3 (advisor ruling 2026-10-24): system-picker file import — pure-JVM tests for
 * the staging core (no Context needed): name sanitization, the streaming intake
 * cap, and the no-partial-file guarantee on failure.
 */
class ChatStagedFileImportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test fun sanitizeName_stripsSeparatorsAndTraversal() {
        assertEquals("evil.txt", ChatStagedFileImport.sanitizeName("../../etc/evil.txt"))
        assertEquals("report.docx", ChatStagedFileImport.sanitizeName("/sdcard/Download/report.docx"))
        assertEquals("a b.zip", ChatStagedFileImport.sanitizeName("C:\\Users\\x\\a b.zip"))
        assertEquals("imported-file", ChatStagedFileImport.sanitizeName("///"))
        assertEquals("imported-file", ChatStagedFileImport.sanitizeName(""))
        val withNull = "bad" + 3.toChar() + "name.txt" // control char removed
        assertEquals("badname.txt", ChatStagedFileImport.sanitizeName(withNull))
        assertEquals(120.toLong(), ChatStagedFileImport.sanitizeName("x".repeat(500)).length.toLong())
    }

    @Test fun copyCapped_underCap_copiesAll() {
        val data = ByteArray(1000) { (it % 251).toByte() }
        val out = java.io.ByteArrayOutputStream()
        val n = ChatStagedFileImport.copyCapped(ByteArrayInputStream(data), out, 10L * 1024 * 1024)
        assertEquals(1000L, n)
        assertTrue(out.toByteArray().contentEquals(data))
    }

    @Test fun copyCapped_overCap_throwsMidStream() {
        val data = ByteArray(3000) { 7 }
        val out = java.io.ByteArrayOutputStream()
        try {
            ChatStagedFileImport.copyCapped(ByteArrayInputStream(data), out, 2048L)
            throw AssertionError("expected over-cap failure")
        } catch (e: Exception) {
            // The honest refusal, not a truncated silent copy.
            assertTrue(e.message!!.contains("intake cap"))
        }
        assertTrue("no unbounded read", out.size() <= 2048 + 32 * 1024)
    }

    @Test fun stagedCopy_success_landsStagedFile() {
        val dir = tmp.newFolder()
        val data = "hello staged".toByteArray()
        val f = ChatStagedFileImport.stagedCopy(ByteArrayInputStream(data), dir, "note.txt")
        assertTrue(f.isFile)
        assertEquals("note.txt", f.name)
        assertEquals("hello staged", f.readText())
    }

    @Test fun stagedCopy_overCap_leavesNoPartialFile() {
        val dir = tmp.newFolder()
        val data = ByteArray(3000) { 9 }
        try {
            ChatStagedFileImport.stagedCopy(ByteArrayInputStream(data), dir, "big.bin")
            throw AssertionError("expected over-cap failure")
        } catch (_: Exception) { }
        assertEquals(0, dir.listFiles()?.size ?: 0)
        assertFalse(File(dir, "big.bin").exists())
    }

    @Test fun stagedCopy_nameCollision_getsUniqueName() {
        val dir = tmp.newFolder()
        File(dir, "a.txt").writeText("first")
        val f = ChatStagedFileImport.stagedCopy(ByteArrayInputStream("second".toByteArray()), dir, "a.txt")
        assertEquals("a-1.txt", f.name)
        assertEquals("first", File(dir, "a.txt").readText())
        assertEquals("second", f.readText())
    }
}
