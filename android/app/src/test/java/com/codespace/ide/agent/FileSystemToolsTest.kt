package com.codespace.ide.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Wave 1 Group B — file_stat and disk_usage.
 *
 * These are the properties that make a filesystem tool safe, so each test asserts a BEHAVIOUR a
 * user would notice: the size is the real size, a `..` path is refused, a link out of the root is
 * flagged rather than followed, and a bounded walk says it was bounded.
 */
class FileSystemToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    /** The boundary under test. tmp.root itself is the PARENT, so its other folders are outside. */
    private val workspace: File by lazy { tmp.newFolder("ws").apply { mkdirs() } }

    private fun ws(): File = workspace

    private fun file(name: String, content: String): File {
        val f = File(ws(), name)
        f.parentFile?.mkdirs()
        f.writeText(content)
        return f
    }

    // ── file_stat ──────────────────────────────────────────────────────────

    @Test fun `existing file reports type size and metadata`() {
        val f = file("a.txt", "hello world")
        val stat = FileSystemTools.stat(f.path, ws().path)
        assertTrue(stat.exists)
        assertTrue(stat.isFile)
        assertFalse(stat.isDirectory)
        assertEquals(11L, stat.sizeBytes)
        assertTrue("lastModified should be a real timestamp", stat.lastModifiedMs > 0L)
        assertTrue("a readable file reports readable", stat.readable)
    }

    @Test fun `existing directory lists children directories first`() {
        file("dir/b.txt", "b")
        file("dir/sub/c.txt", "c")
        val dir = File(ws(), "dir")
        val stat = FileSystemTools.stat(dir.path, ws().path)
        assertTrue(stat.isDirectory)
        assertEquals(2, stat.childCount)
        assertEquals("sub/", stat.children.first())
        assertTrue(stat.children.contains("b.txt"))
    }

    @Test fun `missing path reports not found without inventing metadata`() {
        val stat = FileSystemTools.stat(File(ws(), "nope.txt").path, ws().path)
        assertFalse(stat.exists)
        assertEquals(0L, stat.sizeBytes)
        assertTrue(stat.render("nope.txt", ws().path).startsWith("No such path"))
    }

    @Test fun `large child lists are truncated and say so`() {
        val dir = File(ws(), "many").apply { mkdirs() }
        for (i in 0 until FileSystemTools.MAX_STAT_CHILDREN + 20) File(dir, "f$i.txt").writeText("x")
        val stat = FileSystemTools.stat(dir.path, ws().path)
        assertEquals(FileSystemTools.MAX_STAT_CHILDREN + 20, stat.childCount)
        assertEquals(FileSystemTools.MAX_STAT_CHILDREN, stat.children.size)
        assertTrue(stat.childrenTruncated)
        assertTrue(stat.render("many", ws().path).contains("showing first"))
    }

    @Test fun `symlink inside the root is reported with its target`() {
        val target = file("real.txt", "0123456789")
        val link = File(ws(), "link.txt")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            return   // filesystem without symlink support: nothing to assert
        }
        val stat = FileSystemTools.stat(link.path, ws().path)
        assertTrue(stat.isSymlink)
        assertEquals(target.path, stat.symlinkTarget)
        assertFalse(stat.symlinkEscapesRoot)
        assertEquals(10L, stat.sizeBytes)
    }

    @Test fun `symlink leaving the root is flagged and its target is not measured`() {
        val outside = tmp.newFolder("outside-parent")
        val secret = File(outside, "secret.txt").apply { writeText("secret") }
        val link = File(ws(), "escape.txt")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Exception) {
            return
        }
        val stat = FileSystemTools.stat(link.path, ws().path)
        assertTrue(stat.isSymlink)
        assertTrue("a link out of the root must be flagged", stat.symlinkEscapesRoot)
        assertEquals("an escaping link's target size must not be reported", 0L, stat.sizeBytes)
    }

    @Test fun `a link inside the root is described even when it points outside`() {
        val outside = tmp.newFolder("out2")
        val secret = File(outside, "s.txt").apply { writeText("data") }
        val link = File(ws(), "alias.txt")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Exception) {
            return
        }
        assertTrue("the link's own location is inside", FileSystemTools.isInsideEntry(ws().path, link.path))
        assertFalse("its resolved target is not", FileSystemTools.isInside(ws().path, link.path))
        val stat = FileSystemTools.stat(link.path, ws().path)
        assertTrue(stat.isSymlink)
        assertTrue(stat.symlinkEscapesRoot)
        assertTrue(stat.render("alias.txt", ws().path).contains("target leaves the workspace root"))
    }

    @Test fun `entry containment still refuses a path that escapes`() {
        assertFalse(FileSystemTools.isInsideEntry(ws().path, File("/etc/passwd").path))
        assertFalse(FileSystemTools.isInsideEntry(ws().path, "../outside.txt"))
        assertTrue(FileSystemTools.isInsideEntry(ws().path, File(ws(), "deep/new.txt").path))
    }

    @Test fun `path traversal is detected before any filesystem work`() {
        assertTrue(FileSystemTools.hasTraversal("../etc/passwd"))
        assertTrue(FileSystemTools.hasTraversal("a/../../b"))
        assertTrue(FileSystemTools.hasTraversal("a\\..\\b"))
        assertFalse(FileSystemTools.hasTraversal("a/b/c.txt"))
        assertFalse(FileSystemTools.hasTraversal("..hidden/x"))
    }

    @Test fun `a path outside the root is refused`() {
        val outside = tmp.newFolder("elsewhere")
        val f = File(outside, "x.txt").apply { writeText("x") }
        assertFalse(FileSystemTools.isInside(ws().path, f.path))
        val stat = FileSystemTools.stat(f.path, ws().path)
        assertFalse("outside the root, nothing is reported", stat.exists)
    }

    @Test fun `containment rejects a sibling with a shared prefix`() {
        val root = File(ws(), "proj").apply { mkdirs() }
        val sibling = File(ws(), "proj2/file.txt")
        assertTrue(FileSystemTools.isInside(root.path, File(root, "a.txt").path))
        assertFalse(FileSystemTools.isInside(root.path, sibling.path))
    }

    @Test fun `null root means no boundary, not a refusal`() {
        val f = file("free.txt", "x")
        assertTrue(FileSystemTools.isInside(null, f.path))
        assertTrue(FileSystemTools.stat(f.path, null).exists)
    }

    // ── disk_usage ─────────────────────────────────────────────────────────

    @Test fun `disk usage totals a known tree`() {
        file("proj/a.txt", "12345")        // 5
        file("proj/b.txt", "1234567890")   // 10
        file("proj/sub/c.txt", "123")      // 3
        val usage = FileSystemTools.diskUsage(File(ws(), "proj").path, ws().path)
        assertEquals(18L, usage.totalBytes)
        assertEquals(3, usage.fileCount)
        assertEquals(2, usage.dirCount)    // sub + proj
        assertTrue(usage.complete)
        assertTrue(usage.render().contains("18 bytes"))
    }

    @Test fun `empty directory is zero bytes and complete`() {
        val dir = File(ws(), "empty").apply { mkdirs() }
        val usage = FileSystemTools.diskUsage(dir.path, ws().path)
        assertEquals(0L, usage.totalBytes)
        assertEquals(0, usage.fileCount)
        assertTrue(usage.complete)
    }

    @Test fun `git is excluded by default and included on request`() {
        file("repo/src/a.kt", "1234")
        file("repo/.git/objects/blob", "1234567890123456")
        val without = FileSystemTools.diskUsage(File(ws(), "repo").path, ws().path)
        assertEquals(4L, without.totalBytes)
        assertTrue(without.skipped.contains(".git"))
        val with = FileSystemTools.diskUsage(File(ws(), "repo").path, ws().path, excludeGit = false)
        assertEquals(20L, with.totalBytes)
    }

    @Test fun `a symlink loop is not followed forever`() {
        val dir = File(ws(), "loop").apply { mkdirs() }
        File(dir, "a.txt").writeText("12345")
        try {
            java.nio.file.Files.createSymbolicLink(File(dir, "self").toPath(), dir.toPath())
        } catch (_: Exception) {
            return
        }
        val usage = FileSystemTools.diskUsage(dir.path, ws().path)
        assertEquals(5L, usage.totalBytes)
    }

    @Test fun `a symlink out of the root is skipped, not traversed`() {
        val outside = tmp.newFolder("victim")
        File(outside, "big.bin").writeText("0123456789")
        val dir = File(ws(), "safe").apply { mkdirs() }
        File(dir, "own.txt").writeText("123")
        try {
            java.nio.file.Files.createSymbolicLink(File(dir, "escape").toPath(), outside.toPath())
        } catch (_: Exception) {
            return
        }
        val usage = FileSystemTools.diskUsage(dir.path, ws().path)
        assertEquals(3L, usage.totalBytes)
        assertTrue(usage.skipped.contains("escape"))
    }

    @Test fun `entry limit reports an incomplete result as a lower bound`() {
        val dir = File(ws(), "wide").apply { mkdirs() }
        for (i in 0 until 40) File(dir, "f$i.txt").writeText("x")
        val usage = FileSystemTools.diskUsage(dir.path, ws().path, maxEntries = 5)
        assertFalse(usage.complete)
        assertTrue(usage.stopReason!!.contains("entry limit"))
        assertTrue(usage.render().contains("INCOMPLETE"))
        assertTrue("a bounded walk must not claim a total", usage.render().contains("lower bound"))
    }

    @Test fun `depth limit stops descent and says so`() {
        file("deep/a/b/c/d/e.txt", "123")
        val usage = FileSystemTools.diskUsage(File(ws(), "deep").path, ws().path, maxDepth = 2)
        assertFalse(usage.complete)
        assertTrue(usage.stopReason!!.contains("depth limit"))
        assertEquals(0L, usage.totalBytes)
    }

    @Test fun `a missing path is refused rather than reported as empty`() {
        val usage = FileSystemTools.diskUsage(File(ws(), "ghost").path, ws().path)
        assertFalse(usage.complete)
        assertEquals("no such path", usage.stopReason)
    }

    @Test fun `an outside path is refused`() {
        val outside = tmp.newFolder("far")
        File(outside, "x").writeText("1")
        val usage = FileSystemTools.diskUsage(outside.path, ws().path)
        assertFalse(usage.complete)
        assertTrue(usage.stopReason!!.contains("outside the workspace"))
    }

    @Test fun `human byte formatting is exact at the boundaries`() {
        assertEquals("512 B", FileSystemTools.humanBytes(512))
        assertEquals("1.0 KB", FileSystemTools.humanBytes(1024))
        assertEquals("1.5 MB", FileSystemTools.humanBytes(1536L * 1024))
    }
}
