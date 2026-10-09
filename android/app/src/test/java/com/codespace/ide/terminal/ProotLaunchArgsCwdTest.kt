package com.codespace.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 (2026-10-09, owner-approved): proves the --cwd threading contract.
 *
 *  - NULL cwd (every non-interactive caller — MCP spawn, tool exec, IDE
 *    environment, DAP, gradle test-debug — all call launchArgs(context) which
 *    defaults to null) keeps the array byte-identical to the pre-F1 build.
 *  - A custom cwd changes EXACTLY the two working-directory entries, verbatim —
 *    no quoting, no mangling: proot receives argv ELEMENTS, not a shell string,
 *    so spaces, quotes and unicode must pass through untouched (owner condition 1e).
 */
class ProotLaunchArgsCwdTest {

    private val rootfs = "/data/user/0/pkg/files/ubuntu-rootfs"
    private val tmpDir = "/data/user/0/pkg/cache/proot-tmp"
    private val hostFiles = "/data/user/0/pkg/files"
    private val selinuxDir = "/data/user/0/pkg/cache/fake-selinux"

    private val nullCwd = ProotInstaller.buildProotArgs(rootfs, tmpDir, hostFiles, selinuxDir, null)

    @Test
    fun `null cwd keeps --cwd slash root`() {
        val cwdEntries = nullCwd.filter { it.startsWith("--cwd=") }
        assertEquals(listOf("--cwd=/root"), cwdEntries)
    }

    @Test
    fun `null cwd keeps -w slash root`() {
        val wIdx = nullCwd.indexOf("-w")
        assertTrue("-w entry must exist", wIdx >= 0)
        assertEquals("/root", nullCwd[wIdx + 1])
    }

    @Test
    fun `custom cwd with spaces passes verbatim`() {
        val cwd = "/host-files/projects/My Co de"
        val args = ProotInstaller.buildProotArgs(rootfs, tmpDir, hostFiles, selinuxDir, cwd)
        assertEquals(listOf("--cwd=$cwd"), args.filter { it.startsWith("--cwd=") })
        assertEquals(cwd, args[args.indexOf("-w") + 1])
    }

    @Test
    fun `custom cwd with quotes and unicode passes verbatim`() {
        val cwd = "/sdcard/My \"co\"de\" Ünï- proyecto"
        val args = ProotInstaller.buildProotArgs(rootfs, tmpDir, hostFiles, selinuxDir, cwd)
        assertEquals(listOf("--cwd=$cwd"), args.filter { it.startsWith("--cwd=") })
        assertEquals(cwd, args[args.indexOf("-w") + 1])
    }

    @Test
    fun `custom cwd changes exactly the two cwd entries and nothing else`() {
        val cwd = "/host-files/projects/1791541161142"
        val custom = ProotInstaller.buildProotArgs(rootfs, tmpDir, hostFiles, selinuxDir, cwd)
        assertEquals(nullCwd.size, custom.size)
        val differing = nullCwd.indices.filter { nullCwd[it] != custom[it] }
        // The two changed positions: the --cwd= entry and the value after -w.
        val expectedPositions = setOf(nullCwd.indexOfFirst { it.startsWith("--cwd=") }, nullCwd.indexOf("-w") + 1)
        assertEquals(expectedPositions, differing.toSet())
    }

    @Test
    fun `bind mounts and env entries survive unchanged`() {
        // Spot-anchor the non-cwd args a real launch depends on — if any of these
        // move or change, non-interactive callers are no longer byte-identical.
        assertTrue(nullCwd.contains("--rootfs=$rootfs"))
        assertTrue(nullCwd.contains("--bind=/dev"))
        assertTrue(nullCwd.contains("--bind=$hostFiles:/host-files"))
        assertTrue(nullCwd.contains("--bind=/sdcard"))
        assertTrue(nullCwd.contains("--bind=/proc/self/cwd:/proc/self/cwd"))
        assertTrue(nullCwd.contains("/bin/bash"))
        assertTrue(nullCwd.contains("--login"))
    }
}
