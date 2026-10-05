package com.codespace.ide.resolver

import com.codespace.ide.util.CanonicalPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * V0-d: JVM coverage for the host-resolution branch tree lifted verbatim from
 * ToolPathResolver.resolve. Temp folders drive the exists()-dependent branches;
 * nonexistent paths drive the NEW-file branches deterministically.
 */
class HostResolutionTest {

    @get:Rule val tmp = TemporaryFolder()

    private val files = "/fake/files"
    private val pkg = "com.codespace.ide.debug"
    private val rootfs = "/fake/rootfs"

    private fun resolve(raw: String, root: String? = null) =
        HostResolution.toHostPath(raw, root, files, pkg, rootfs)

    @Test fun `relative path joins an existing host project root`() {
        val root = tmp.newFolder("proj")
        assertEquals(
            CanonicalPaths.canonical(java.io.File(root, "src/A.kt")),
            resolve("src/A.kt", root.absolutePath),
        )
    }

    @Test fun `relative path translates a guest-spelled root`() {
        // Root does not exist on the HOST (JVM): guest spelling -> rootfs translation first.
        assertEquals(
            CanonicalPaths.canonical(java.io.File(java.io.File(rootfs, "root/repos/proj"), "src/A.kt")),
            resolve("src/A.kt", "/root/repos/proj"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `relative path without a project root throws`() {
        resolve("src/A.kt", null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty path throws`() {
        resolve("   ")
    }

    @Test fun `absolute existing file canonicalizes as-is`() {
        val f = tmp.newFile("A.kt")
        assertEquals(f.canonicalPath, resolve(f.absolutePath))
    }

    @Test fun `android storage path never gains the rootfs prefix`() {
        assertEquals(
            CanonicalPaths.canonical(java.io.File("/storage/emulated/0/proj/new.kt")),
            resolve("/storage/emulated/0/proj/new.kt"),
        )
    }

    @Test fun `filesDir path passes through`() {
        assertEquals(
            CanonicalPaths.canonical(java.io.File("$files/projects/p1/new.kt")),
            resolve("$files/projects/p1/new.kt"),
        )
    }

    @Test fun `data-user package path passes through`() {
        assertEquals(
            CanonicalPaths.canonical(java.io.File("/data/user/0/$pkg/files/x/new.kt")),
            resolve("/data/user/0/$pkg/files/x/new.kt"),
        )
    }

    @Test fun `new guest file resolves into the rootfs`() {
        assertEquals(
            CanonicalPaths.canonical(java.io.File(rootfs, "etc/newdir/new.kt")),
            resolve("/etc/newdir/new.kt"),
        )
    }

    @Test fun `identity facade matches on canonical and boundary suffix`() {
        // V0-b: the facade is the ONE identity door — prove it works host-side.
        val a = tmp.newFile("A.kt")
        assertTrue(PathResolver.sameIdentity(a.absolutePath, a.canonicalPath))
        assertTrue(PathResolver.sameIdentity("/x/y/z.txt", "z.txt"))
        assertTrue(!PathResolver.sameIdentity("/x/out2", "/x/out"))
        assertEquals(CanonicalPaths.canonicalKey(a.absolutePath), PathResolver.identity(a.absolutePath))
        assertTrue(PathResolver.isInside(tmp.root.absolutePath, a.absolutePath))
        assertTrue(!PathResolver.isInside("/x/out", "/x/out2"))
    }
}
