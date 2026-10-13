package com.codespace.ide.terminal

import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ADVISOR 2026-10-13 (item 1): REAL-ASSET fixtures. The hand-made fixtures
 * encoded an x86 assumption (a lib64 symlink) that the previous gate turned
 * into a critical requirement — rejecting the owner's healthy arm64 restore
 * with "missing lib64". These tests run against the REAL pinned Ubuntu asset
 * (ubuntu-questing-aarch64-pd-v4.30.1.tar.xz, 57,831,960 bytes, SHA-256
 * 5ab35b90cd9a9f180656261ba400a135c4c01c2da4b74522118342f985c2d328) that
 * "Download fresh" installs on the device.
 *
 * The CI workflow fetches, size+hash-verifies and extracts the asset, then
 * exports QUESTING_ROOTFS pointing at the extracted tree. When the variable
 * is absent (a local JVM run) every test SKIPS via assumeTrue — an honest
 * skip, never a fake pass.
 */
class RealAssetBackupTest {

    companion object {
        fun realRootfs(): File? =
            System.getenv("QUESTING_ROOTFS")?.let { File(it) }
                ?.takeIf { it.isDirectory && File(it, "usr/bin/bash").exists() }
    }

    /**
     * The SUSPECT, proven: the real arm64 asset has NO lib64, and the
     * redesigned gate (bash + env critical only) judges it HEALTHY.
     */
    @Test
    fun realArm64Asset_hasNoLib64_andPassesTheGate() {
        val src = realRootfs()
        assumeTrue("QUESTING_ROOTFS not set — real-asset tests skipped", src != null)
        val rootfs = src!!
        assertFalse("the real arm64 asset must not have lib64", File(rootfs, "lib64").exists())
        assertTrue("real asset must ship usr/bin/bash", File(rootfs, "usr/bin/bash").exists())
        assertTrue("real asset must ship usr/bin/env", File(rootfs, "usr/bin/env").exists())
        val h = ContainerSafety.healthOf(rootfs)
        assertTrue("gate must pass on the real asset: $h", h.ok)
        assertTrue(h.missingCritical.isEmpty())
        assertTrue("lib64 line must be informational, not a refusal", h.lines.any { it.startsWith("lib64 absent") })
    }

    /**
     * Round-trip with the REAL layout: extract the fixed writer's archive,
     * manifest check passes, gate passes. This is the shape the owner's
     * device will exercise: backup of a real container, restore, gate.
     */
    @Test
    fun roundTrip_realAsset_backupRestore_gatePasses() {
        val src = realRootfs()
        assumeTrue("QUESTING_ROOTFS not set — real-asset tests skipped", src != null)
        val rootfs = src!!
        val base = Files.createTempDirectory("rt-real").toFile()

        // Pass 1: the fixed writer on the real tree (read-only against it).
        val archive = File(base, "roundtrip.tar.gz")
        java.io.FileOutputStream(archive).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                    val written = BackupTar.writeRootfs(rootfs, tar, emptyList()) { }
                    assertTrue("writer must record entries (got $written)", written > 1000)
                }
            }
        }
        assertTrue("archive must be substantial", archive.length() > 10_000_000L)

        // Pass 2: inspect (item 4c) — read-only, sees the real top level.
        val insp = BackupTar.inspect(archive)
        assertTrue("inspect must succeed: ${insp.error}", insp.ok)
        assertTrue("inspect: bash must be found", insp.hasBash)
        assertTrue("inspect: env must be found", insp.hasEnv)
        assertTrue("inspect: top level must include usr", insp.topLevel.contains("usr"))

        // Pass 3: restore — scan, extract, manifest, gate.
        val dest = File(base, "restored")
        val scan = BackupTar.scan(archive)
        val ext = BackupTar.extract(archive, dest, scan) { }
        val manifest = ContainerSafety.manifestCheck(scan, ext)
        assertTrue(manifest.detail, manifest.ok)
        val h = ContainerSafety.healthOf(dest)
        assertTrue("restored real tree must pass the gate: $h", h.ok)
        assertTrue("restored bash must be a real file", File(dest, "usr/bin/bash").isFile)
        assertEquals("extract failures", 0, ext.fileFailures)
    }

    /**
     * The OLD-writer failure shape, reproduced from the REAL layout: an
     * archive whose traversal follows the usrmerge bin/ link and re-emits
     * the same files under both usr/... and bin/usr/... paths. The fixed
     * extractor must count them as duplicates, skip them, and the restored
     * tree must still pass the gate.
     */
    @Test
    fun oldWriterShape_duplicatesFromRealLayout_areSkippedNotFatal() {
        val src = realRootfs()
        assumeTrue("QUESTING_ROOTFS not set — real-asset tests skipped", src != null)
        val rootfs = src!!
        val base = Files.createTempDirectory("rt-dup").toFile()

        // Synthetic archive carrying REAL content plus traversal duplicates.
        val archive = File(base, "dups.tar.gz")
        val bashBytes = File(rootfs, "usr/bin/bash").readBytes()
        val envBytes = File(rootfs, "usr/bin/env").readBytes()
        java.io.FileOutputStream(archive).use { fos ->
            GzipCompressorOutputStream(fos).use { gz ->
                TarArchiveOutputStream(gz).use { tar ->
                    fun entry(name: String, bytes: ByteArray) {
                        val e = org.apache.commons.compress.archivers.tar.TarArchiveEntry(name)
                        e.size = bytes.size.toLong()
                        tar.putArchiveEntry(e)
                        tar.write(bytes)
                        tar.closeArchiveEntry()
                    }
                    fun dirEntry(name: String) {
                        tar.putArchiveEntry(org.apache.commons.compress.archivers.tar.TarArchiveEntry(name))
                        tar.closeArchiveEntry()
                    }
                    dirEntry("usr/")
                    dirEntry("usr/bin/")
                    entry("usr/bin/bash", bashBytes)
                    entry("usr/bin/env", envBytes)
                    // The usrmerge symlink as the real asset ships it.
                    val link = org.apache.commons.compress.archivers.tar.TarArchiveEntry(
                        "bin", org.apache.commons.compress.archivers.tar.TarArchiveEntry.LF_SYMLINK,
                    )
                    link.linkName = "usr/bin"
                    tar.putArchiveEntry(link)
                    tar.closeArchiveEntry()
                    // OLD-WRITER SHAPE 1: traversal entries reached through the
                    // bin/ link — they resolve to a NESTED guest path (usr/bin/usr/bin/...),
                    // never escape the root, and never overwrite the real file.
                    entry("bin/usr/bin/bash", "CORRUPT-DUPLICATE".toByteArray())
                    // OLD-WRITER SHAPE 2: an EXACT duplicate entry name (tar walked
                    // the same file twice) — the extractor must SKIP it.
                    entry("usr/bin/bash", "EXACT-DUPLICATE".toByteArray())
                }
            }
        }

        val dest = File(base, "restored")
        val scan = BackupTar.scan(archive)
        val ext = BackupTar.extract(archive, dest, scan) { }
        assertTrue("exact duplicate entries must be skipped", ext.duplicatesSkipped >= 1)
        assertEquals("no file failures — traversal entries resolve or are rejected, never fatal", 0, ext.fileFailures)
        val h = ContainerSafety.healthOf(dest)
        assertTrue("gate must pass despite the duplicate entries: $h", h.ok)
        assertEquals(
            "the real bash must win over the corrupt duplicates",
            bashBytes.size.toLong(),
            File(dest, "usr/bin/bash").length(),
        )
        assertFalse(
            "the traversal duplicate must NOT overwrite the real env",
            "CORRUPT-DUPLICATE".toByteArray().contentEquals(File(dest, "usr/bin/env").readBytes()),
        )
        val manifest = ContainerSafety.manifestCheck(scan, ext)
        assertTrue("every entry must still be accounted: ${manifest.detail}", manifest.ok)
    }
}
