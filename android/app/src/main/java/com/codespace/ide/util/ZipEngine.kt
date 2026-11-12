package com.codespace.ide.util

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ZipEngine — the ONE archive implementation behind the zip tools.
 *
 * ENGINE CHOICE (2026-11-12, ZIP tools audit): `java.util.zip` is already the archive engine this
 * app ships with — `WorkspaceManager.createSnapshot` writes with [ZipOutputStream] and the archive
 * viewer reads with [ZipFile] — so this file extends that existing choice rather than adding a
 * second library. No new dependency, and no second zip implementation to keep in step.
 *
 * Every rule here is a security rule first and a convenience second: an archive is untrusted input,
 * and the extraction side of this file is written as if the archive were hostile, because a zip
 * arrives from anywhere.
 *
 * Pure java.io/nio on purpose (no Context, no Android API) so every path, limit and refusal is
 * asserted by a plain JVM test.
 */
object ZipEngine {

    /** Entries per archive. A workspace archive of 2,000 files is large; beyond this, ask again. */
    const val MAX_ENTRIES = 2_000

    /** Total uncompressed bytes written by one extraction: 256 MB, sized for phone storage. */
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /** One entry's uncompressed size: 128 MB. A bigger single file is not a workspace file. */
    const val MAX_ENTRY_BYTES = 128L * 1024 * 1024

    /**
     * Compression ratio ceiling, applied only to entries above [RATIO_FLOOR_BYTES] (8 MB).
     * A 200:1 ratio on a big entry is a decompression bomb tell: repeated bytes are legal, a
     * 500 MB expansion of a 2 KB entry is not something this tool should spend the phone's storage
     * discovering. Small entries are exempt because text legitimately compresses well.
     */
    const val MAX_RATIO = 200L
    const val RATIO_FLOOR_BYTES = 8L * 1024 * 1024

    /** Listing page size. The prompt gets a summary plus this many entries, never the whole CD. */
    const val DEFAULT_LIST = 100
    const val MAX_LIST = 500

    private const val BUFFER = 64 * 1024

    enum class ProblemKind { UNSAFE_PATH, LIMIT, RATIO, OVERWRITE_EXISTS, UNSUPPORTED, IO }

    data class Problem(val kind: ProblemKind, val entry: String, val detail: String) {
        fun line(): String = entry + " (" + kind.name.lowercase().replace('_', ' ') + "): " + detail
    }

    sealed class CreateOutcome {
        data class Ok(
            val archive: File,
            val files: Int,
            val dirs: Int,
            val bytes: Long,
            val skipped: List<Problem>,
        ) : CreateOutcome()

        data class Failed(val reason: String) : CreateOutcome()
    }

    data class ExtractOutcome(
        val files: Int,
        val dirs: Int,
        val bytes: Long,
        val skipped: List<Problem>,
        val failed: List<Problem>,
        val partial: Boolean,
    )

    sealed class ListOutcome {
        data class Ok(
            val entries: List<ZipEntry>,
            val total: Int,
            val totalUncompressed: Long,
            val suspicious: List<Problem>,
            val unsupported: List<Problem>,
            val offset: Int,
        ) : ListOutcome()

        data class Failed(val reason: String) : ListOutcome()
    }

    // ── path rules ───────────────────────────────────────────────────────────

    /**
     * Why an entry name is unsafe, or null when it is fine. Checked BEFORE any file is created,
     * because the whole point of ZIP Slip is that a name like `../../etc/passwd` only looks normal
     * until it is written.
     */
    fun unsafeReason(name: String): String? {
        if (name.isBlank()) return "empty name"
        if (name.indexOf('\u0000') >= 0) return "contains a NUL byte"
        // Both separators, because an archive built on Windows can carry backslashes and the JVM
        // would still treat the whole string as one file name here — a check that is not made now
        // becomes a traversal on a machine that does split on the other separator.
        val unified = name.replace('\\', '/')
        if (unified.startsWith("//")) return "UNC path"
        if (unified.startsWith("/")) return "absolute path"
        if (Regex("^[A-Za-z]:").containsMatchIn(unified)) return "Windows drive prefix"
        val parts = unified.split('/')
        if (parts.any { it == ".." }) return "contains a .. segment"
        if (unified.contains("/../")) return "contains a .. segment"
        return null
    }

    /**
     * The file an entry is allowed to write to, or null when it escapes [root].
     *
     * Two independent checks, deliberately: the name rules above, and a canonical containment test
     * on the result. Either alone would be a single point of failure.
     */
    fun safeTarget(root: File, entryName: String): File? {
        if (unsafeReason(entryName) != null) return null
        return try {
            val rootCanon = root.canonicalFile
            val target = File(rootCanon, entryName.replace('\\', '/')).canonicalFile
            val rootPath = rootCanon.path + File.separator
            if (target.path == rootCanon.path) return null
            if (!target.path.startsWith(rootPath)) return null
            target
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when any directory between [root] and [target] is a symlink. We never WRITE through one:
     * an existing link inside the destination is exactly the trick that turns a safe relative path
     * into a write somewhere else on the phone.
     */
    fun passesThroughSymlink(root: File, target: File): Boolean {
        var cur = target.parentFile
        val stop = root.canonicalFile
        while (cur != null) {
            if (cur.canonicalFile == stop) return false
            if (Files.isSymbolicLink(cur.toPath())) return true
            cur = cur.parentFile
        }
        return false
    }

    /** True for the platform-independent "this is a directory entry" marker. */
    fun isDirectoryEntry(name: String): Boolean =
        name.endsWith("/") || name.endsWith("\\")

    fun isArchiveName(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".zip") || lower.endsWith(".jar") || lower.endsWith(".apk") ||
            lower.endsWith(".aar") || lower.endsWith(".epub") || lower.endsWith(".whl")
    }

    // ── create ───────────────────────────────────────────────────────────────

    /**
     * Write [sources] into a new archive at [dest]. Entry names are relative to [base] when given
     * (so a single file lands as `file.txt`, a directory as `dir/file.txt`), which is what makes an
     * archive usable when it is extracted somewhere else.
     *
     * The archive is built as `dest.part` and renamed on success, so a failure mid-way never leaves
     * a half-written archive that looks complete.
     */
    fun create(
        sources: List<File>,
        dest: File,
        base: File? = null,
        overwrite: Boolean = false,
        maxEntries: Int = MAX_ENTRIES,
    ): CreateOutcome {
        if (sources.isEmpty()) return CreateOutcome.Failed("No source paths given.")
        // The destination is dropped BEFORE the existence check: `zip_create(sources=["./"])` with
        // the archive about to be written inside that directory is the normal case, and the archive
        // does not exist yet, so an existence check first would fail a legitimate request.
        val destCanonEarly = try { dest.canonicalFile } catch (_: Exception) { dest }
        val partEarly = File(dest.parentFile ?: File("/"), dest.name + ".part")
        val usable = ArrayList<File>(sources.size)
        val earlySkips = ArrayList<Problem>()
        for (src in sources) {
            val canon = try { src.canonicalFile } catch (_: Exception) { src }
            if (canon == destCanonEarly || canon == partEarly) {
                earlySkips.add(Problem(ProblemKind.UNSAFE_PATH, src.name, "is the destination archive"))
            } else {
                usable.add(src)
            }
        }
        if (usable.isEmpty()) {
            return CreateOutcome.Failed("Nothing to archive: the only source was the destination.")
        }
        val missing = usable.firstOrNull { !it.exists() }
        if (missing != null) return CreateOutcome.Failed("Source does not exist: " + missing.path)
        if (dest.exists() && !overwrite) {
            return CreateOutcome.Failed(
                "Archive already exists: " + dest.path + ". Pass overwrite=true to replace it.",
            )
        }
        if (dest.exists() && dest.isDirectory) {
            return CreateOutcome.Failed("Destination is a directory, not an archive path: " + dest.path)
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile ?: File("/"), dest.name + ".part")
        val skipped = ArrayList<Problem>()
        skipped.addAll(earlySkips)
        var files = 0
        var dirs = 0
        var bytes = 0L

        val destCanon = try { dest.canonicalFile } catch (_: Exception) { dest }

        try {
            ZipOutputStream(FileOutputStream(part)).use { zos ->
                val seen = HashSet<String>()
                val queue = ArrayDeque<Pair<File, String>>()
                for (src in sources) {
                    val canon = src.canonicalFile
                    // Refuse the archive itself as a source: a previous run's output inside the
                    // tree would otherwise be packed into the archive being written.
                    if (canon == destCanon || canon == part.canonicalFile) {
                        skipped.add(Problem(ProblemKind.UNSAFE_PATH, src.name, "is the destination archive"))
                        continue
                    }
                    val root = base?.canonicalFile ?: canon
                    val prefix = if (base != null) {
                        canon.relativeToOrNull(root)?.path?.replace('\\', '/') ?: ""
                    } else {
                        ""
                    }
                    queue.add(canon to prefix)
                }
                while (queue.isNotEmpty()) {
                    val (file, prefix) = queue.removeFirst()
                    if (file.isDirectory) {
                        if (prefix.isNotEmpty()) {
                            zos.putNextEntry(ZipEntry(if (prefix.endsWith("/")) prefix else "$prefix/"))
                            zos.closeEntry()
                            dirs++
                        }
                        val children = file.listFiles()
                        if (children == null) {
                            skipped.add(Problem(ProblemKind.IO, prefix, "directory not readable"))
                            continue
                        }
                        children.sortedBy { it.name }.forEach { child ->
                            val childPrefix = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                            // A symlink is packed as its target's CONTENT, never as a link, so an
                            // extracted archive cannot point outside itself.
                            if (Files.isSymbolicLink(child.toPath()) && !child.canonicalFile.isFile) {
                                skipped.add(Problem(ProblemKind.UNSAFE_PATH, childPrefix, "symlink"))
                            } else {
                                queue.add(child to childPrefix)
                            }
                        }
                        continue
                    }
                    if (!file.isFile) {
                        skipped.add(Problem(ProblemKind.UNSUPPORTED, prefix, "not a regular file"))
                        continue
                    }
                    val entryName = if (prefix.isEmpty()) file.name else prefix
                    if (!seen.add(entryName)) {
                        skipped.add(Problem(ProblemKind.IO, entryName, "duplicate entry name"))
                        continue
                    }
                    if (entriesExceeded(seen.size, maxEntries)) {
                        skipped.add(Problem(ProblemKind.LIMIT, entryName, "entry limit reached"))
                        break
                    }
                    try {
                        zos.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { input ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                zos.write(buf, 0, n)
                                bytes += n
                            }
                        }
                        zos.closeEntry()
                        files++
                    } catch (e: Exception) {
                        skipped.add(Problem(ProblemKind.IO, entryName, e.message ?: "read failed"))
                    }
                }
            }
            if (files == 0 && dirs == 0) {
                part.delete()
                return CreateOutcome.Failed("Nothing to archive: every source was skipped.")
            }
            if (dest.exists() && !dest.delete()) {
                part.delete()
                return CreateOutcome.Failed("Could not replace the existing archive: " + dest.path)
            }
            if (!part.renameTo(dest)) {
                part.delete()
                return CreateOutcome.Failed("Could not write the archive: " + dest.path)
            }
            return CreateOutcome.Ok(dest, files, dirs, bytes, skipped)
        } catch (e: Exception) {
            part.delete()
            return CreateOutcome.Failed("Archive creation failed: " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun entriesExceeded(count: Int, max: Int): Boolean = count > max

    // ── extract ──────────────────────────────────────────────────────────────

    /**
     * Extract [archive] into [destDir], streaming each entry and enforcing every limit as it goes.
     *
     * The archive is never read into memory and no entry is buffered whole: 64 KB at a time, the
     * same buffer size the archive viewer uses on 3 GB devices.
     */
    fun extract(
        archive: File,
        destDir: File,
        overwrite: Boolean = false,
        maxEntries: Int = MAX_ENTRIES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
    ): ExtractOutcome {
        val skipped = ArrayList<Problem>()
        val failed = ArrayList<Problem>()
        var files = 0
        var dirs = 0
        var total = 0L
        var partial = false

        if (!archive.isFile) {
            return ExtractOutcome(0, 0, 0, skipped, listOf(
                Problem(ProblemKind.IO, archive.name, "archive not found")), partial = false)
        }
        destDir.mkdirs()
        val root = destDir.canonicalFile

        val zip = try {
            ZipFile(archive)
        } catch (e: Exception) {
            return ExtractOutcome(0, 0, 0, skipped, listOf(
                Problem(ProblemKind.UNSUPPORTED, archive.name,
                    "not a readable zip (" + (e.message ?: e.javaClass.simpleName) + ")")), false)
        }

        try {
            val iter = zip.entries()
            var seen = 0
            while (iter.hasMoreElements()) {
                val entry = iter.nextElement()
                seen++
                if (seen > maxEntries) {
                    failed.add(Problem(ProblemKind.LIMIT, entry.name, "archive exceeds $maxEntries entries"))
                    partial = true
                    break
                }
                val name = entry.name
                if (isDirectoryEntry(name)) {
                    val dir = safeTarget(root, name)
                    if (dir == null) {
                        skipped.add(Problem(ProblemKind.UNSAFE_PATH, name, "escapes the destination"))
                        continue
                    }
                    if (passesThroughSymlink(root, dir)) {
                        skipped.add(Problem(ProblemKind.UNSAFE_PATH, name, "goes through a symlink"))
                        partial = true
                        continue
                    }
                    if (dir.mkdirs() || dir.isDirectory) dirs++ else {
                        skipped.add(Problem(ProblemKind.IO, name, "directory could not be created"))
                    }
                    continue
                }
                val target = safeTarget(root, name)
                if (target == null) {
                    skipped.add(Problem(ProblemKind.UNSAFE_PATH, name, "escapes the destination"))
                    partial = true
                    continue
                }
                if (passesThroughSymlink(root, target)) {
                    skipped.add(Problem(ProblemKind.UNSAFE_PATH, name, "goes through a symlink"))
                    partial = true
                    continue
                }
                if (entry.size > maxEntryBytes) {
                    skipped.add(Problem(ProblemKind.LIMIT, name, "entry larger than " + mb(maxEntryBytes)))
                    partial = true
                    continue
                }
                if (entry.size in 0..RATIO_FLOOR_BYTES && entry.compressedSize > 0 &&
                    entry.size / maxOf(1L, entry.compressedSize) > MAX_RATIO && entry.size > RATIO_FLOOR_BYTES
                ) {
                    skipped.add(Problem(ProblemKind.RATIO, name, "suspicious compression ratio"))
                    partial = true
                    continue
                }
                if (target.exists() && !overwrite) {
                    // An entry that was not written means the extraction is NOT complete, even
                    // though nothing failed: "extracted 0 files" must never read as success.
                    skipped.add(Problem(ProblemKind.OVERWRITE_EXISTS, name, "already exists"))
                    partial = true
                    continue
                }
                if (target.exists() && target.isDirectory) {
                    skipped.add(Problem(ProblemKind.OVERWRITE_EXISTS, name, "is a directory"))
                    partial = true
                    continue
                }
                target.parentFile?.mkdirs()
                val before = total
                try {
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            val buf = ByteArray(BUFFER)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > maxTotalBytes) {
                                    throw LimitBreach("total uncompressed size exceeds " + mb(maxTotalBytes))
                                }
                                if (total - before > maxEntryBytes) {
                                    throw LimitBreach("entry larger than " + mb(maxEntryBytes))
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    files++
                } catch (e: LimitBreach) {
                    target.delete()
                    total = before
                    failed.add(Problem(ProblemKind.LIMIT, name, e.message ?: "limit"))
                    partial = true
                    // A total-size breach means every later entry breaches too: stop instead of
                    // grinding through the rest of a bomb one refusal at a time.
                    if (e.message?.contains("total") == true) break
                } catch (e: Exception) {
                    target.delete()
                    total = before
                    failed.add(Problem(ProblemKind.IO, name, e.message ?: "write failed"))
                    partial = true
                }
            }
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
        return ExtractOutcome(files, dirs, total, skipped, failed, partial)
    }

    private class LimitBreach(override val message: String) : Exception(message)

    private fun mb(bytes: Long): String = (bytes / (1024 * 1024)).toString() + " MB"

    // ── list ─────────────────────────────────────────────────────────────────

    /**
     * Read the central directory only — no entry is decompressed. Bounded by [limit] so a 50,000
     * entry archive cannot fill the prompt.
     */
    fun list(archive: File, offset: Int = 0, limit: Int = DEFAULT_LIST): ListOutcome {
        if (!archive.isFile) return ListOutcome.Failed("Archive not found: " + archive.name)
        val zip = try {
            ZipFile(archive)
        } catch (e: Exception) {
            return ListOutcome.Failed(
                "Not a readable zip: " + (e.message ?: e.javaClass.simpleName))
        }
        try {
            val all = ArrayList<ZipEntry>()
            val suspicious = ArrayList<Problem>()
            val unsupported = ArrayList<Problem>()
            var totalBytes = 0L
            val iter = zip.entries()
            while (iter.hasMoreElements()) {
                val e = iter.nextElement()
                all.add(e)
                if (e.size > 0) totalBytes += e.size
                unsafeReason(e.name)?.let { suspicious.add(Problem(ProblemKind.UNSAFE_PATH, e.name, it)) }
                if (e.method != ZipEntry.STORED && e.method != ZipEntry.DEFLATED) {
                    unsupported.add(Problem(ProblemKind.UNSUPPORTED, e.name, "compression method " + e.method))
                }
            }
            val from = offset.coerceIn(0, maxOf(0, all.size))
            val to = (from + limit.coerceIn(1, MAX_LIST)).coerceAtMost(all.size)
            return ListOutcome.Ok(all.subList(from, to), all.size, totalBytes, suspicious, unsupported, from)
        } catch (e: Exception) {
            return ListOutcome.Failed("Could not read the archive: " + (e.message ?: "unknown error"))
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
    }

    private fun File.relativeToOrNull(root: File): File? = try {
        val base = root.toPath()
        val me = this.toPath()
        if (!me.startsWith(base)) null else base.relativize(me).toFile()
    } catch (_: Exception) {
        null
    }
}
