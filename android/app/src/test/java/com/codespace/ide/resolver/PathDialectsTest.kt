package com.codespace.ide.resolver

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * V0-d: JVM coverage for the path-dialect translation tables lifted verbatim from
 * ProotInstaller (hostToGuest/guestToHost) plus the V0-c binding-registry hook.
 * Every row of both `when` tables gets at least one vector; the registry consult
 * is proven both ways (None default falls through; an active binding wins).
 */
class PathDialectsTest {

    private val rootfs = "/fake/rootfs"
    private val files = "/fake/files"

    @After fun tearDown() {
        BindingRegistry.active = BindingRegistry.None
    }

    // ── hostToGuest: every table row ────────────────────────────────────────

    @Test fun `rootfs root maps to guest root`() {
        assertEquals("/", PathDialects.hostToGuest(rootfs, rootfs, files))
    }

    @Test fun `rootfs subtree maps to guest slash path`() {
        assertEquals("/etc/profile.d/x.sh", PathDialects.hostToGuest("$rootfs/etc/profile.d/x.sh", rootfs, files))
    }

    @Test fun `shared storage maps to sdcard`() {
        assertEquals("/sdcard", PathDialects.hostToGuest("/storage/emulated/0", rootfs, files))
        assertEquals("/sdcard/proj/a.kt", PathDialects.hostToGuest("/storage/emulated/0/proj/a.kt", rootfs, files))
    }

    @Test fun `guest sdcard spelling passes through`() {
        assertEquals("/sdcard/proj", PathDialects.hostToGuest("/sdcard/proj", rootfs, files))
    }

    @Test fun `filesDir maps to host-files`() {
        assertEquals("/host-files", PathDialects.hostToGuest(files, rootfs, files))
        assertEquals("/host-files/projects/p1/a.kt", PathDialects.hostToGuest("$files/projects/p1/a.kt", rootfs, files))
    }

    @Test fun `unreachable host path yields null`() {
        assertNull(PathDialects.hostToGuest("/storage/1A2B-3C4D/proj", rootfs, files))
        assertNull(PathDialects.hostToGuest("/mnt/other", rootfs, files))
    }

    // ── guestToHost: every table row ─────────────────────────────────────────

    @Test fun `guest sdcard maps to shared storage`() {
        assertEquals(File("/storage/emulated/0"), PathDialects.guestToHost("/sdcard", rootfs, files))
        assertEquals(File("/storage/emulated/0/proj/a.kt"), PathDialects.guestToHost("/sdcard/proj/a.kt", rootfs, files))
    }

    @Test fun `host-files projects root resolves inside filesDir`() {
        assertEquals(File("$files/projects"), PathDialects.guestToHost("/host-files/projects", rootfs, files))
    }

    @Test fun `host-files projects child resolves contained`() {
        assertEquals(File("$files/projects/p1"), PathDialects.guestToHost("/host-files/projects/p1", rootfs, files))
    }

    @Test fun `host-files projects escape falls back to rootfs`() {
        // Lexical canonicalization resolves the .. escape to files/secrets which is
        // OUTSIDE the projects root -> refuse, fall back to the nonexistent-in-guest rootfs path.
        val f = PathDialects.guestToHost("/host-files/projects/../secrets", rootfs, files)
        assertEquals(File(rootfs, "host-files/projects/../secrets"), f)
    }

    @Test fun `other host-files paths refuse filesDir`() {
        // /host-files/<not projects> deliberately does NOT resolve into filesDir
        // (databases, token storage must stay unreachable).
        assertEquals(File(rootfs, "host-files/databases/db"), PathDialects.guestToHost("/host-files/databases/db", rootfs, files))
    }

    @Test fun `generic guest path lands in rootfs`() {
        assertEquals(File(rootfs, "etc/passwd"), PathDialects.guestToHost("/etc/passwd", rootfs, files))
    }

    // ── V0-c: the binding-registry hook ──────────────────────────────────────

    @Test fun `none registry falls through to rootfs`() {
        assertEquals(BindingRegistry.None, BindingRegistry.active)
        assertEquals(File(rootfs, "proj/node_modules/.bin/tsc"), PathDialects.guestToHost("/proj/node_modules/.bin/tsc", rootfs, files))
    }

    @Test fun `active binding wins over the rootfs fallback`() {
        BindingRegistry.active = object : BindingRegistry {
            override fun hostTargetFor(guestPath: String): String? =
                if (guestPath == "/proj/node_modules") "$rootfs/.bindings/abc123" else null
        }
        assertEquals(File("$rootfs/.bindings/abc123"), PathDialects.guestToHost("/proj/node_modules", rootfs, files))
    }

    @Test fun `binding miss keeps rootfs fallback for other paths`() {
        BindingRegistry.active = object : BindingRegistry {
            override fun hostTargetFor(guestPath: String): String? = null
        }
        assertEquals(File(rootfs, "usr/bin/env"), PathDialects.guestToHost("/usr/bin/env", rootfs, files))
    }

    // ── workspaceToGuest: the resolveWorkspacePath dialect ───────────────────

    @Test fun `guest root workspace passes through`() {
        assertEquals("/root/repos/proj", PathDialects.workspaceToGuest("/root/repos/proj", rootfs, files))
    }

    @Test fun `host storage workspace translates to sdcard`() {
        assertEquals("/sdcard/proj", PathDialects.workspaceToGuest("/storage/emulated/0/proj", rootfs, files))
    }

    @Test fun `filesDir workspace translates to host-files`() {
        assertEquals("/host-files/projects/p1", PathDialects.workspaceToGuest("$files/projects/p1", rootfs, files))
    }

    @Test fun `unreachable workspace yields null not a guess`() {
        assertNull(PathDialects.workspaceToGuest("/storage/1A2B-3C4D/proj", rootfs, files))
    }
}
