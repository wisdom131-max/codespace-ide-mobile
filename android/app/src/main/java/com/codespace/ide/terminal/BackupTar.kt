package com.codespace.ide.terminal

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * E17 device round-trip fix (2026-10-11, advisor items 1a-1e). The 435 MB backup
 * made by the OLD writer contains entries that TRAVERSE directory symlinks
 * (bin/add-apt-repository as a regular file, because Kotlin's walkTopDown follows
 * symlinked directories), ordered so the symlink "bin -> usr/bin" is extracted
 * BEFORE the files that pass through it — the extractor then failed with
 * FileNotFoundException ENOENT on bin/add-apt-repository (usr/bin did not exist
 * yet) and aborted after 3 processed entries ("1 of 3").
 *
 * This module is PURE JVM (no Android imports) so the exact archive shape is
 * reproduced and tested in the JVM suite:
 *  - Extraction is two-pass: a SCAN pass collects entry names/types, the symlink
 *    map and TOTALS; an EXTRACT pass writes directories and files only, with
 *    symlinks DEFERRED to the very end — a file write can therefore never pass
 *    through a symlinked directory, and a crafted archive cannot write outside
 *    the destination root.
 *  - Entries that traverse a symlink known from the SAME archive are resolved to
 *    their real destination (bin/add-apt-repository -> usr/bin/add-apt-repository).
 *    A resolved destination already written by another entry is a DUPLICATE —
 *    skipped, counted, and reported (never silent).
 *  - Absolute symlink targets are GUEST-absolute: interpreted inside the
 *    destination root, never the host root. ../-climbing resolutions that leave
 *    the root are rejected. Every destination passes
 *    CanonicalPaths.safeEntryDestination.
 *  - Failure messages carry REAL totals (failures of total entries, processed
 *    count) — never a mid-abort "1 of 3" again.
 */
object BackupTar {

    /** A symlink entry recorded in the scan pass. */
    data class SymlinkInfo(val name: String, val target: String)

    /** Everything the extract pass needs, collected up front (pass A). */
    data class ScanResult(
        val totalEntries: Int,
        val dirs: Int,
        val files: Int,
        val symlinks: List<SymlinkInfo>,
        val rejectedNames: List<String>,
    )

    /** Full typed result of an extraction (restoreBackup wraps it). */
    data class ExtractResult(
        val filesWritten: Int,
        val dirsWritten: Int,
        val symlinksCreated: Int,
        val symlinkWarnings: Int,
        val fileFailures: Int,
        val duplicatesSkipped: Int,
        val rejectedEntries: Int,
        val processed: Int,
        val totalEntries: Int,
        val firstFailure: String?,
        /** "bash ✓", "node ✓", "python3 —" style lines for the result text. */
        val verifyLines: List<String>,
        /** usr/bin/bash present — the one path a restored container MUST have. */
        val bashOk: Boolean,
    )

    /** Normalize an archive entry name: strip leading "./", drop "." segments. */
    internal fun normalize(name: String): String =
        name.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

    /**
     * Resolve a path through the archive's own symlink map. Each component
     * prefix that matches a known symlink is substituted by its target
     * (guest-absolute targets are treated as destination-root-relative),
     * iteratively with a depth cap. Returns null when the resolution escapes
     * the destination root.
     */
    fun resolveThroughSymlinks(
        normalizedPath: String,
        symlinkMap: Map<String, String>,
    ): String? {
        var current = normalizedPath
        var depth = 0
        outer@ while (depth++ < 40) {
            val segs = ArrayList(current.split('/').filter { it.isNotEmpty() })
            for (i in segs.indices) {
                val prefix = segs.subList(0, i + 1).joinToString("/")
                val target = symlinkMap[prefix] ?: continue
                val rest = segs.subList(i + 1, segs.size)
                val parent = segs.subList(0, i)
                val tParts = if (target.startsWith("/")) {
                    // Guest-absolute target: destination-root-relative.
                    target.split('/').filter { it.isNotEmpty() && it != "." }
                } else {
                    // Relative target: climbs against the link's parent dir.
                    parent + target.split('/')
                }
                val climbed = ArrayList<String>()
                for (part in tParts) {
                    when {
                        part == ".." -> {
                            if (climbed.isEmpty()) return null
                            climbed.removeAt(climbed.size - 1)
                        }
                        part.isNotEmpty() && part != "." -> climbed.add(part)
                    }
                }
                current = (climbed + rest).joinToString("/")
                continue@outer
            }
            return current.takeIf { it.isNotEmpty() }
        }
        return null
    }

    /**
     * Pass A: scan the archive for names, types, the symlink map and totals.
     * Reads the whole stream (data skipped); the caller reopens for pass B.
     */
    fun scan(archiveFile: File): ScanResult = archiveFile.inputStream().use { scanStream(it) }

    /** Bug 4 (owner round 2026-10-14): scan a stream straight from a SAF picker — no 200+ MB cache copy. */
    fun scanStream(input: java.io.InputStream): ScanResult {
        var total = 0; var dirs = 0; var files = 0
        val symlinks = ArrayList<SymlinkInfo>()
        val rejected = ArrayList<String>()
        GzipOpen.openStream(input).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val name = normalize(entry.name)
                    if (name.isEmpty() ||
                        com.codespace.ide.util.CanonicalPaths.safeEntryDestination(File("/"), entry.name) == null
                    ) {
                        rejected.add(entry.name)
                    } else when {
                        entry.isDirectory -> { dirs++; total++ }
                        entry.isSymbolicLink -> {
                            symlinks.add(SymlinkInfo(name, entry.linkName ?: ""))
                            total++
                        }
                        else -> { files++; total++ }
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return ScanResult(total, dirs, files, symlinks, rejected)
    }

    /**
     * ADVISOR 2026-10-13 (item 4c): READ-ONLY archive inspection for the
     * "Check backup" button — no extraction, no writes. Reports top-level
     * names, symlink entries, counts, and the critical binaries the gate
     * needs (bash, env) straight from the entry names.
     */
    data class InspectReport(
        val ok: Boolean,
        val sizeBytes: Long,
        val modifiedMs: Long,
        val topLevel: List<String>,
        val symlinks: List<SymlinkInfo>,
        val totalEntries: Int,
        val dirs: Int,
        val files: Int,
        val hasBash: Boolean,
        val hasEnv: Boolean,
        val rejected: List<String>,
        val error: String?,
    )

    fun inspect(archiveFile: File): InspectReport =
        archiveFile.inputStream().use { inspectStream(it, archiveFile.length(), archiveFile.lastModified()) }

    /** Bug 4 (owner round 2026-10-14): Check backup works on a PICKED stream (Settings > Container Backup).
     *  A stream has no size/mtime of its own — callers that KNOW them pass them in
     *  (the File delegate does), streams default to -1 (the picker reports its own
     *  size line via the ContentResolver). */
    fun inspectStream(input: java.io.InputStream, sizeBytes: Long = -1L, modifiedMs: Long = -1L): InspectReport {
        val topLevel = LinkedHashSet<String>()
        val symlinks = ArrayList<SymlinkInfo>()
        val rejected = ArrayList<String>()
        var total = 0; var dirs = 0; var files = 0
        var hasBash = false; var hasEnv = false
        try {
            GzipOpen.openStream(input).use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextEntry
                    while (entry != null) {
                        val name = normalize(entry.name)
                        if (name.isEmpty()) {
                            rejected.add(entry.name)
                        } else if (com.codespace.ide.util.CanonicalPaths.safeEntryDestination(File("/"), entry.name) == null) {
                            rejected.add(entry.name)
                        } else {
                            topLevel.add(name.substringBefore('/'))
                            when {
                                entry.isDirectory -> { dirs++; total++ }
                                entry.isSymbolicLink -> {
                                    symlinks.add(SymlinkInfo(name, entry.linkName ?: ""))
                                    total++
                                }
                                else -> { files++; total++ }
                            }
                            if (name == "usr/bin/bash") hasBash = true
                            if (name == "usr/bin/env") hasEnv = true
                        }
                        entry = tar.nextEntry
                    }
                }
            }
            return InspectReport(
                ok = true, sizeBytes = sizeBytes, modifiedMs = modifiedMs,
                topLevel = topLevel.toList(), symlinks = symlinks, totalEntries = total,
                dirs = dirs, files = files, hasBash = hasBash, hasEnv = hasEnv,
                rejected = rejected, error = null,
            )
        } catch (e: Exception) {
            return InspectReport(
                ok = false, sizeBytes = sizeBytes, modifiedMs = modifiedMs,
                topLevel = emptyList(), symlinks = emptyList(), totalEntries = 0, dirs = 0, files = 0,
                hasBash = false, hasEnv = false, rejected = emptyList(), error = "${e.message}",
            )
        }
    }

    /**
     * Pass B: extract directories and files (in archive order, parents
     * auto-created), then create the DEFERRED symlinks. No file write can pass
     * through a symlink because none exists yet; traversal entries resolve
     * through the scan-pass symlink map instead.
     */
    fun extract(archiveFile: File, dest: File, scan: ScanResult, onProgress: (String) -> Unit): ExtractResult =
        archiveFile.inputStream().use { extractStream(it, dest, scan, onProgress) }

    /** Bug 4 (owner round 2026-10-14): extract from a stream — restore reads the picked file directly. */
    fun extractStream(input: java.io.InputStream, dest: File, scan: ScanResult, onProgress: (String) -> Unit): ExtractResult {
        val symlinkMap = HashMap<String, String>()
        scan.symlinks.forEach { symlinkMap[it.name] = it.target }
        val written = HashSet<String>()
        var filesWritten = 0; var dirsWritten = 0
        var fileFailures = 0; var duplicates = 0; var processed = 0
        var firstFailure: String? = null
        val failures = ArrayList<String>()

        GzipOpen.openStream(input).use { raw ->
            TarArchiveInputStream(raw).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    val cur: TarArchiveEntry = entry
                    val name = normalize(cur.name)
                    if (name.isNotEmpty()) {
                        processed++
                        if (cur.isDirectory) {
                            val outFile = com.codespace.ide.util.CanonicalPaths.safeEntryDestination(dest, name)
                            if (outFile != null && (outFile.exists() || outFile.mkdirs())) dirsWritten++
                        } else if (!entry.isSymbolicLink) {
                            // Resolve through the archive's own symlinks; a guest
                            // directory symlink must not shadow the real file.
                            // A resolution that ESCAPES the root is REJECTED —
                            // never written, never faked as a real directory.
                            val resolvedName = resolveThroughSymlinks(name, symlinkMap)
                            val outFile = resolvedName?.let {
                                com.codespace.ide.util.CanonicalPaths.safeEntryDestination(dest, it)
                            }
                            if (resolvedName == null || outFile == null) {
                                // escapes the destination root — rejected, never written
                                failures.add("rejected (escape): $name")
                            } else if (!written.add(resolvedName)) {
                                duplicates++
                            } else {
                                outFile.parentFile?.mkdirs()
                                val outcome = runCatching {
                                    outFile.outputStream().use { out -> tar.copyTo(out) }
                                    if ((cur.mode and 0b001_001_001) != 0) outFile.setExecutable(true, false)
                                    outFile.setReadable(true, false)
                                }
                                if (outcome.isFailure) {
                                    fileFailures++
                                    val cause = outcome.exceptionOrNull()
                                    val detail = "${cause?.javaClass?.simpleName}: ${cause?.message} (entry $name)"
                                    if (firstFailure == null) firstFailure = detail
                                    failures.add(detail)
                                    written.remove(resolvedName)
                                } else {
                                    filesWritten++
                                    if (filesWritten % 500 == 0) onProgress("Restored $filesWritten files...")
                                }
                            }
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }

        // DEFERRED symlink creation — after every file write, so no write ever
        // traverses a symlink. Best-effort on device kernels that block the
        // syscall (counted, logged in the result, never fatal).
        var symlinksCreated = 0; var symlinkWarnings = 0
        scan.symlinks.forEach { info ->
            val outFile = com.codespace.ide.util.CanonicalPaths.safeEntryDestination(dest, info.name)
            if (outFile == null || !symlinkTargetStaysInsideRoot(info.name, info.target)) {
                // escapes the destination root — the link is never created
                symlinkWarnings++
            } else {
                runCatching {
                    val link = outFile.toPath()
                    val target = Paths.get(info.target)
                    outFile.parentFile?.mkdirs()
                    if (Files.exists(link) || Files.isSymbolicLink(link)) Files.delete(link)
                    Files.createSymbolicLink(link, target)
                }.onSuccess { symlinksCreated++ }
                    .onFailure { symlinkWarnings++ }
            }
        }

        // Post-verify: no silent partial restore. bash is REQUIRED; node and
        // python3 are reported as present/absent (a container without node is
        // valid if node was never installed).
        val bashOk = File(dest, "usr/bin/bash").exists()
        val verify = listOf(
            "bash " + (if (bashOk) "\u2713" else "\u2717"),
            "node " + (if (File(dest, "usr/bin/node").exists()) "\u2713" else "\u2014"),
            "python3 " + (if (File(dest, "usr/bin/python3").exists()) "\u2713" else "\u2014"),
        )
        return ExtractResult(
            filesWritten, dirsWritten, symlinksCreated, symlinkWarnings, fileFailures,
            duplicates, scan.rejectedNames.size + countEscapes(failures), processed,
            scan.totalEntries, firstFailure, verify, bashOk,
        )
    }

    /**
     * A symlink target that climbs above the link's parent out of the root must
     * never be created (absolute GUEST targets are fine — they are rootfs-relative
     * under proot, exactly like the live container).
     */
    internal fun symlinkTargetStaysInsideRoot(linkPath: String, target: String): Boolean {
        if (target.startsWith("/")) return true
        var depth = linkPath.split('/').filter { it.isNotEmpty() && it != "." }.size - 1
        if (depth < 0) depth = 0
        for (seg in target.split('/')) {
            when {
                seg == ".." -> { if (depth == 0) return false; depth-- }
                seg.isEmpty() || seg == "." -> {}
                else -> depth++
            }
        }
        return true
    }

    private fun countEscapes(failures: List<String>): Int =
        failures.count { it.startsWith("rejected (escape)") }

    /**
     * Writer fix (advisor 1e): never descend through symlinked directories —
     * the old walkTopDown followed the bin link into usr/bin and emitted duplicate
     * entries under bin, one for every file there,
     * bloating the archive and creating the exact restore hazard above. The
     * restore still ACCEPTS old archives (extract resolves them); new backups
     * simply stop carrying traversal entries.
     */
    fun writeRootfs(
        rootfs: File,
        tar: TarArchiveOutputStream,
        skipPrefixes: List<String>,
        onProgress: (String) -> Unit,
    ): Int {
        val counter = WriteCounter()
        // Depth-first WITHOUT following directory symlinks. Kotlin's walkTopDown
        // cannot express this: its onEnter hook suppresses the directory NODE
        // itself, which would silently DROP every rootfs symlink (bin, lib...)
        // from the archive. The symlink is EMITTED here, its children never.
        fun visit(file: File) {
            val relPath = file.relativeTo(rootfs).path
            if (relPath.startsWith("proc/") || relPath.startsWith("sys/") || relPath.startsWith("dev/")) return
            if (skipPrefixes.any { relPath == it || relPath.startsWith("$it/") }) return
            runCatching {
                when {
                    Files.isSymbolicLink(file.toPath()) -> {
                        val link = Files.readSymbolicLink(file.toPath())
                        val symEntry = TarArchiveEntry(relPath, TarArchiveEntry.LF_SYMLINK)
                        symEntry.linkName = link.toString()
                        tar.putArchiveEntry(symEntry)
                        tar.closeArchiveEntry()
                    }
                    file.isDirectory -> {
                        tar.putArchiveEntry(TarArchiveEntry(file, relPath))
                        tar.closeArchiveEntry()
                    }
                    else -> {
                        val entry = TarArchiveEntry(file, relPath)
                        entry.size = file.length()
                        if (file.canExecute()) entry.mode = entry.mode or 0b001_001_001
                        tar.putArchiveEntry(entry)
                        file.inputStream().use { it.copyTo(tar) }
                        tar.closeArchiveEntry()
                        counter.totalBytes += file.length()
                    }
                }
                counter.filesWritten++
                if (counter.filesWritten % 500 == 0) onProgress("Backed up ${counter.filesWritten} files (${counter.totalBytes / (1024 * 1024)} MB)...")
            }
            if (file.isDirectory && !Files.isSymbolicLink(file.toPath())) {
                file.listFiles()?.forEach { visit(it) }
            }
        }
        rootfs.listFiles()?.forEach { visit(it) }
        return counter.filesWritten
    }

    /** Mutable counters shared with the local visit function above. */
    private class WriteCounter(var filesWritten: Int = 0, var totalBytes: Long = 0L)

    /** Tiny indirection so the JVM suite can hit the same open path as device. */
    object GzipOpen {
        /** Bug 4 (owner 2026-10-14): streams validated by CONTENT (gzip magic), never by name or MIME. */
        fun openStream(input: java.io.InputStream): InputStream =
            java.io.BufferedInputStream(
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(input)
            )

        fun open(file: File): InputStream =
            java.io.BufferedInputStream(
                org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream(file.inputStream())
            )
    }
}
