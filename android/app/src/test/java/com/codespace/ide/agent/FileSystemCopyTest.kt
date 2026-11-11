package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Wave 1 Group B — copy_path.
 *
 * The contract: a copy either happens completely or leaves nothing behind, never overwrites, never
 * follows a link out of the workspace, and never turns into an infinite recursion.
 */
class FileSystemCopyTest {

    @get:Rule val tmp = TemporaryFolder()

    private val workspace: File by lazy { tmp.newFolder("ws").apply { mkdirs() } }

    private fun ws(): File = workspace

    private fun file(path: String, content: String): File {
        val f = File(ws(), path)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    @Test fun `a file copy preserves content and reports bytes`() {
        val src = file("a.txt", "hello")
        val dst = File(ws(), "b.txt")
        val r = FileSystemTools.copy(src.path, dst.path, ws().path)
        assertTrue(r.complete)
        assertEquals(1, r.filesCopied)
        assertEquals(5L, r.bytesCopied)
        assertEquals("hello", dst.readText())
        assertTrue(r.render().startsWith("Copied"))
    }

    @Test fun `a directory copy reproduces the tree`() {
        file("src/a.txt", "1")
        file("src/sub/b.txt", "22")
        file("src/sub/deep/c.txt", "333")
        val r = FileSystemTools.copy(File(ws(), "src").path, File(ws(), "out").path, ws().path)
        assertTrue(r.complete)
        assertEquals(3, r.filesCopied)
        assertEquals(6L, r.bytesCopied)
        assertEquals("1", File(ws(), "out/a.txt").readText())
        assertEquals("22", File(ws(), "out/sub/b.txt").readText())
        assertEquals("333", File(ws(), "out/sub/deep/c.txt").readText())
    }

    @Test fun `an existing destination is never overwritten`() {
        val src = file("a.txt", "new")
        val dst = file("b.txt", "original")
        val r = FileSystemTools.copy(src.path, dst.path, ws().path)
        assertFalse(r.complete)
        assertEquals("the destination must be untouched", "original", dst.readText())
        assertTrue(r.errors.first().contains("never overwrites"))
    }

    @Test fun `a missing source is reported`() {
        val r = FileSystemTools.copy(File(ws(), "ghost.txt").path, File(ws(), "out.txt").path, ws().path)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("no such path"))
        assertFalse(File(ws(), "out.txt").exists())
    }

    @Test fun `a missing destination parent is created`() {
        val src = file("a.txt", "x")
        val dst = File(ws(), "deep/nested/b.txt")
        val r = FileSystemTools.copy(src.path, dst.path, ws().path)
        assertTrue(r.complete)
        assertTrue(dst.exists())
    }

    @Test fun `a source outside the root is refused`() {
        val outside = tmp.newFolder("outside")
        val src = File(outside, "x.txt").apply { writeText("x") }
        val r = FileSystemTools.copy(src.path, File(ws(), "in.txt").path, ws().path)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("source is outside"))
        assertFalse(File(ws(), "in.txt").exists())
    }

    @Test fun `a destination outside the root is refused`() {
        val src = file("a.txt", "x")
        val outside = tmp.newFolder("target")
        val r = FileSystemTools.copy(src.path, File(outside, "x.txt").path, ws().path)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("destination is outside"))
        assertFalse(File(outside, "x.txt").exists())
    }

    @Test fun `a destination inside the source is refused as recursion`() {
        file("src/a.txt", "x")
        val src = File(ws(), "src")
        val r = FileSystemTools.copy(src.path, File(src, "inner").path, ws().path)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("inside the source"))
        assertFalse(File(src, "inner").exists())
    }

    @Test fun `a symlink out of the root is skipped, never materialised`() {
        val outside = tmp.newFolder("secret")
        File(outside, "key.txt").writeText("topsecret")
        val src = File(ws(), "src").apply { mkdirs() }
        File(src, "own.txt").writeText("mine")
        try {
            java.nio.file.Files.createSymbolicLink(File(src, "leak").toPath(), File(outside, "key.txt").toPath())
        } catch (_: Exception) {
            return
        }
        val r = FileSystemTools.copy(src.path, File(ws(), "out").path, ws().path)
        assertTrue(r.errors.any { it.contains("symlink out of the workspace") })
        assertFalse(File(ws(), "out/leak").exists())
        assertEquals("mine", File(ws(), "out/own.txt").readText())
    }

    @Test fun `an entry limit aborts and cleans up the partial destination`() {
        val src = File(ws(), "many").apply { mkdirs() }
        for (i in 0 until 20) File(src, "f$i.txt").writeText("x")
        val dst = File(ws(), "partial")
        val r = FileSystemTools.copy(src.path, dst.path, ws().path, maxEntries = 5)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("entry limit"))
        assertTrue("an incomplete copy must not be left behind", r.cleanedUp)
        assertFalse(dst.exists())
    }

    @Test fun `a byte limit stops the copy instead of filling the disk`() {
        val src = File(ws(), "big").apply { mkdirs() }
        File(src, "big.bin").writeText("0123456789")
        val r = FileSystemTools.copy(src.path, File(ws(), "out").path, ws().path, maxBytes = 4)
        assertFalse(r.complete)
        assertTrue(r.errors.first().contains("byte limit"))
        assertFalse(File(ws(), "out").exists())
    }

    @Test fun `a large file is streamed in chunks rather than read whole`() {
        val big = ByteArray(FileSystemTools.COPY_CHUNK * 3 + 7) { (it % 251).toByte() }
        val src = File(ws(), "stream.bin").apply { writeBytes(big) }
        val dst = File(ws(), "copy.bin")
        val r = FileSystemTools.copy(src.path, dst.path, ws().path)
        assertTrue(r.complete)
        assertEquals(big.size.toLong(), r.bytesCopied)
        assertTrue("streamed bytes must match exactly", src.readBytes().contentEquals(dst.readBytes()))
    }

    @Test fun `binary detection distinguishes text from binary`() {
        val text = file("t.txt", "plain text")
        val bin = File(ws(), "b.bin").apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }
        assertFalse(FileSystemTools.looksBinary(text))
        assertTrue(FileSystemTools.looksBinary(bin))
    }
}
