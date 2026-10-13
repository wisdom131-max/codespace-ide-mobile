package com.codespace.ide.terminal

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * Advisor items A/B/C/D (2026-10-11) — the exact failure shapes from the
 * 14:15 device incident, as pure JVM:
 *  A. symlink-UNSAFE delete (FileTreeWalk assert crash + delete-through-link)
 *  B. half-finished swap recovery (crash between the two renames)
 *  C. container health gate (a gutted rootfs must be judged damaged)
 *  D. backup verification (the 147 MB overwrite of the good 435 MB backup)
 */
class ContainerSafetyTest {

    private fun tempDir(name: String): File =
        java.nio.file.Files.createTempDirectory(name).toFile().resolve("root").apply { mkdirs() }

    /** Minimal healthy container: usr/bin/{bash,env} + bin/lib links. */
    private fun makeContainer(parent: File, healthy: Boolean = true): File {
        val rootfs = File(parent, "rootfs").apply { mkdirs() }
        File(rootfs, "usr/bin").mkdirs()
        File(rootfs, "usr/bin/bash").writeText("#!/bin/bash")
        File(rootfs, "usr/bin/env").writeText("#!/usr/bin/env bash")
        if (!healthy) File(rootfs, "usr/bin/env").delete()
        Files.createSymbolicLink(File(rootfs, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
        Files.createSymbolicLink(File(rootfs, "lib").toPath(), java.nio.file.Paths.get("usr/lib"))
        Files.createSymbolicLink(File(rootfs, "sbin").toPath(), java.nio.file.Paths.get("usr/sbin"))
        // ADVISOR 2026-10-13 (item 1): arm64-accurate fixture — the REAL pinned
        // asset has NO lib64 (verified: 57,831,960-byte ubuntu-questing-aarch64
        // tarball, zero lib64 entries; the loader is /lib/ld-linux-aarch64.so.1).
        // The hand-made lib64 symlink here encoded an x86 assumption that made
        // the gate reject every healthy arm64 container.
        return rootfs
    }

    // ── A: safe delete ────────────────────────────────────────────────────

    /** The 14:15 crash: a stale temp dir holding BOTH the bin link and its target. */
    @Test
    fun safeDeleteHandlesStaleTempDirWithLinkAndTarget() {
        val base = tempDir("a1")
        val stale = File(base, "rootfs.restore.tmp").apply { mkdirs() }
        File(stale, "usr/bin").mkdirs()
        File(stale, "usr/bin/bash").writeText("#!/bin/bash")
        File(stale, "usr/bin/add-apt-repository").writeText("#!/bin/sh")
        Files.createSymbolicLink(File(stale, "bin").toPath(), java.nio.file.Paths.get("usr/bin"))
        // FileTreeWalk's assertion ("rootDir must be verified to be directory
        // beforehand") died exactly here on device. The safe delete must not.
        val summary = ContainerSafety.deleteTreeSafe(stale)
        assertTrue(summary.ok)
        assertFalse(stale.exists())
    }

    /** A root that is a PLAIN FILE (the other assert-crash shape) is deleted as-is. */
    @Test
    fun safeDeleteHandlesPlainFileRoot() {
        val base = tempDir("a2")
        val fileRoot = File(base, "rootfs.restore.tmp")
        fileRoot.writeText("not a directory")
        val summary = ContainerSafety.deleteTreeSafe(fileRoot)
        assertTrue(summary.ok)
        assertFalse(fileRoot.exists())
    }

    /** A directory link to an EXTERNAL tree is deleted AS A LINK — target untouched. */
    @Test
    fun safeDeleteNeverFollowsSymlinkToExternalTree() {
        val base = tempDir("a3")
        val victim = File(base, "outside").apply { mkdirs() }
        File(victim, "project.py").writeText("print('keep me')")
        val inner = File(base, "tree").apply { mkdirs() }
        File(inner, "usr/bin").mkdirs()
        File(inner, "usr/bin/bash").writeText("#!/bin/bash")
        // absolute link — the exact hazard class: bin -> /sdcard-like target
        Files.createSymbolicLink(File(inner, "escape").toPath(), victim.toPath())
        // relative link pointing outside the root via ..
        Files.createSymbolicLink(File(inner, "escape2").toPath(), java.nio.file.Paths.get("../outside"))

        val summary = ContainerSafety.deleteTreeSafe(inner)

        assertTrue(summary.ok)
        assertFalse(inner.exists())
        // the EXTERNAL tree survives byte-for-byte
        assertTrue("nothing outside the root may be deleted through a link", File(victim, "project.py").exists())
        assertEquals("print('keep me')", File(victim, "project.py").readText())
    }

    // ── B: half-finished swap recovery ────────────────────────────────────

    /** Crash between the renames, temp extraction HEALTHY: temp wins. */
    @Test
    fun halfSwapRecoveryWithHealthyTempSwapsItIn() {
        val base = tempDir("b1")
        val rootfs = File(base, "rootfs")            // missing = crashed mid-swap
        val oldDir = makeContainer(File(base, "x"), healthy = true)
        val moved = File(base, "rootfs.restore.old")
        oldDir.renameTo(moved)
        val tmp = makeContainer(File(base, "y"), healthy = true).let {
            val t = File(base, "rootfs.restore.tmp")
            it.renameTo(t)
            t
        }
        val report = ContainerSafety.recoverHalfSwap(rootfs, moved, tmp)
        assertTrue(rootfs.exists())
        assertTrue(ContainerSafety.healthOf(rootfs).ok)
        assertFalse(tmp.exists())
        assertFalse(moved.exists())
        assertTrue(report.contains("swapped in"))
    }

    /** Crash between the renames, temp extraction UNHEALTHY: previous container rolls back. */
    @Test
    fun halfSwapRecoveryWithDamagedTempRollsBackPrevious() {
        val base = tempDir("b2")
        val rootfs = File(base, "rootfs")            // missing
        val oldDir = makeContainer(File(base, "x"), healthy = true)
        val moved = File(base, "rootfs.restore.old")
        oldDir.renameTo(moved)
        val tmp = makeContainer(File(base, "y"), healthy = false).let {
            val t = File(base, "rootfs.restore.tmp")
            it.renameTo(t)
            t
        }
        val report = ContainerSafety.recoverHalfSwap(rootfs, moved, tmp)
        assertTrue(rootfs.exists())
        assertTrue("the PREVIOUS good container must be the one rolled back", ContainerSafety.healthOf(rootfs).ok)
        assertFalse(tmp.exists())
        assertTrue(report.contains("rolled back"))
    }

    /** Crash right after the first rename, no temp: roll .old back. */
    @Test
    fun halfSwapRecoveryWithOnlyOldRollsItBack() {
        val base = tempDir("b3")
        val rootfs = File(base, "rootfs")
        val moved = makeContainer(File(base, "x"), healthy = true).let {
            val m = File(base, "rootfs.restore.old")
            it.renameTo(m)
            m
        }
        val report = ContainerSafety.recoverHalfSwap(rootfs, moved, File(base, "rootfs.restore.tmp"))
        assertTrue(rootfs.exists())
        assertTrue(ContainerSafety.healthOf(rootfs).ok)
        assertTrue(report.contains("rolled back"))
    }

    /** Container intact + stale temp left by an aborted run: temp discarded, container untouched. */
    @Test
    fun halfSwapRecoveryDiscardsStaleTemp() {
        val base = tempDir("b4")
        val rootfs = makeContainer(base, healthy = true)
        val before = File(rootfs, "usr/bin/bash").readText()
        val tmp = File(base, "rootfs.restore.tmp").apply { mkdirs() }
        File(tmp, "usr").mkdirs()
        val report = ContainerSafety.recoverHalfSwap(rootfs, File(base, "rootfs.restore.old"), tmp)
        assertTrue(rootfs.exists())
        assertEquals(before, File(rootfs, "usr/bin/bash").readText())
        assertFalse(tmp.exists())
        assertTrue(report.contains("stale restore temp"))
    }

    /** Swap completed but the OLD tree was never deleted (crash before cleanup): old discarded, container untouched. */
    @Test
    fun halfSwapRecoveryDiscardsLeftoverOld() {
        val base = tempDir("b5")
        val rootfs = makeContainer(base, healthy = true)
        val before = File(rootfs, "usr/bin/bash").readText()
        val leftoverOld = makeContainer(File(base, "x"), healthy = true).let {
            val m = File(base, "rootfs.restore.old")
            it.renameTo(m)
            m
        }
        val report = ContainerSafety.recoverHalfSwap(rootfs, leftoverOld, File(base, "rootfs.restore.tmp"))
        assertTrue(rootfs.exists())
        assertEquals(before, File(rootfs, "usr/bin/bash").readText())
        assertFalse(leftoverOld.exists())
        assertTrue(report.contains("staged old container discarded"))
    }

    /** Fresh state (no rootfs, no leftovers): recovery is a clean no-op report. */
    @Test
    fun halfSwapRecoveryOnFreshStateIsNoOp() {
        val base = tempDir("b6")
        val report = ContainerSafety.recoverHalfSwap(
            File(base, "rootfs"), File(base, "rootfs.restore.old"), File(base, "rootfs.restore.tmp"),
        )
        assertTrue(report.contains("fresh install state"))
        assertFalse("recovery must not conjure a container from nothing", File(base, "rootfs").exists())
    }

    // ── C: container health ───────────────────────────────────────────────

    @Test
    fun healthGateDetectsGuttedContainer() {
        val base = tempDir("c1")
        val rootfs = makeContainer(base, healthy = false)
        val report = ContainerSafety.healthOf(rootfs)
        assertFalse(report.ok)
        assertTrue(report.missingCritical.contains("usr/bin/env"))
        assertTrue(report.lines.any { it.startsWith("usr/bin/env MISSING") })
    }

    @Test
    fun healthGateAcceptsCompleteContainer() {
        val base = tempDir("c2")
        val rootfs = makeContainer(base, healthy = true)
        assertTrue(ContainerSafety.healthOf(rootfs).ok)
    }

    // ── D: backup verification ─────────────────────────────────────────────

    /** Build a minimal tar.gz with the given file entries. */
    private fun buildTar(entries: List<Pair<String, String>>): File {
        val bytes = ByteArrayOutputStream()
        GzipCompressorOutputStream(bytes).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                entries.forEach { (name, content) ->
                    val data = content.toByteArray()
                    val e = TarArchiveEntry(name)
                    e.size = data.size.toLong()
                    tar.putArchiveEntry(e)
                    ByteArrayInputStream(data).use { it.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
            }
        }
        val out = File(tempDir("d"), "backup.tar.gz")
        out.writeBytes(bytes.toByteArray())
        return out
    }

    @Test
    fun backupVerifyRejectsArchiveMissingEnv() {
        // the 147 MB shape: plenty of entries, no usr/bin/env
        val entries = (1..80).map { i -> "usr/bin/tool$i" to "x" } + ("usr/bin/bash" to "#!/bin/bash")
        val report = ContainerSafety.verifyBackupArchive(buildTar(entries))
        assertFalse(report.ok)
        assertTrue(report.verdict.contains("usr/bin/env"))
    }

    @Test
    fun backupVerifyRejectsTinyArchive() {
        val report = ContainerSafety.verifyBackupArchive(buildTar(listOf("usr/bin/bash" to "#!/bin/bash")))
        assertFalse(report.ok)
        assertTrue(report.verdict.contains("not a full container"))
    }

    @Test
    fun backupVerifyRejectsTruncatedArchive() {
        val good = buildTar((1..80).map { "usr/bin/tool$it" to "x" } + ("usr/bin/bash" to "#!/bin/bash") + ("usr/bin/env" to "y"))
        val truncated = File(good.parentFile, "truncated.tar.gz")
        truncated.writeBytes(good.readBytes().copyOfRange(0, good.length().toInt() / 3))
        val report = ContainerSafety.verifyBackupArchive(truncated)
        assertFalse(report.ok)
    }

    @Test
    fun backupVerifyAcceptsFullArchive() {
        val entries = (1..80).map { "usr/bin/tool$it" to "x" } + ("usr/bin/bash" to "#!/bin/bash") + ("usr/bin/env" to "y")
        val report = ContainerSafety.verifyBackupArchive(buildTar(entries))
        assertTrue(report.ok)
    }
}
