package com.codespace.ide.agent

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Wave 1 Group B (owner approval 2026-11-11): filesystem inspection and copy.
 *
 * WHY THESE EXIST: the model could ask "how big is this?" or "what is in here?" only by reading
 * files, and "copy this tree" only by writing every file back one at a time through the editor
 * staging path. Metadata questions were answered by dumping content; a directory copy cost N calls
 * and could not be reviewed as one operation.
 *
 * SAFETY MODEL, in one place because it is the whole point:
 *  - Every path is resolved to a canonical host path FIRST, then checked against the workspace
 *    root AFTER canonicalisation, so `..`, a symlink, or a hardlink spelling cannot walk out.
 *  - A symlink is inspected as a link, never silently followed as its target, when that target
 *    would leave the root.
 *  - Every traversal is bounded by depth, entries and time, and a bounded result SAYS it was
 *    bounded. A partial answer is never presented as a total.
 *  - Nothing here writes: copy is the only mutating operation and it refuses to overwrite.
 */
object FileSystemTools {

    // ── bounds ─────────────────────────────────────────────────────────────

    /** A metadata call is cheap; this is the directory listing ceiling, not a traversal one. */
    const val MAX_STAT_CHILDREN = 100
    const val MAX_DISK_ENTRIES = 20_000
    const val MAX_DISK_DEPTH = 24
    const val MAX_DISK_MS = 4_000L
    const val MAX_COPY_ENTRIES = 5_000
    const val MAX_COPY_DEPTH = 24
    const val COPY_CHUNK = 64 * 1024
    const val MAX_COPY_BYTES = 200L * 1024 * 1024

    // ── result types ───────────────────────────────────────────────────────

    data class Stat(
        val exists: Boolean,
        val isDirectory: Boolean,
        val isFile: Boolean,
        val isSymlink: Boolean,
        val symlinkTarget: String?,
        val symlinkEscapesRoot: Boolean,
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val readable: Boolean,
        val writable: Boolean,
        val childCount: Int,
        val children: List<String>,
        val childrenTruncated: Boolean,
    ) {
        fun render(displayPath: String, root: String?): String {
            if (!exists) return "No such path: $displayPath" + (root?.let { "\nWorkspace root: $it" } ?: "")
            val sb = StringBuilder()
            sb.append(if (isDirectory) "Directory" else if (isFile) "File" else "Special file")
            sb.append(": ").append(displayPath).append('\n')
            sb.append("size: ").append(sizeBytes).append(" bytes\n")
            sb.append("last modified: ").append(
                java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date(lastModifiedMs)))
            sb.append(" (").append(lastModifiedMs).append(")\n")
            sb.append("readable: ").append(readable).append(", writable: ").append(writable).append('\n')
            if (isSymlink) {
                sb.append("symlink -> ").append(symlinkTarget ?: "?")
                if (symlinkEscapesRoot) sb.append("  [target leaves the workspace root]")
                sb.append('\n')
            }
            if (isDirectory) {
                sb.append("children: ").append(childCount)
                if (childrenTruncated) sb.append(" (showing first ").append(children.size).append(")")
                sb.append('\n')
                children.forEach { sb.append("  - ").append(it).append('\n') }
            }
            return sb.toString().trimEnd()
        }
    }

    data class DiskUsage(
        val path: String,
        val complete: Boolean,
        val stopReason: String?,
        val totalBytes: Long,
        val fileCount: Int,
        val dirCount: Int,
        val depth: Int,
        val elapsedMs: Long,
        val skipped: List<String>,
    ) {
        fun render(): String {
            val sb = StringBuilder()
            sb.append("Disk usage for ").append(path).append('\n')
            sb.append("total: ").append(totalBytes).append(" bytes (")
                .append(humanBytes(totalBytes)).append(")\n")
            sb.append("files: ").append(fileCount).append(", directories: ").append(dirCount)
                .append(", depth: ").append(depth).append('\n')
            sb.append("elapsed: ").append(elapsedMs).append(" ms\n")
            if (!complete) {
                sb.append("INCOMPLETE: ").append(stopReason ?: "traversal limit reached")
                    .append(". The figures above are a lower bound, not a total.\n")
            }
            if (skipped.isNotEmpty()) {
                sb.append("skipped: ").append(skipped.joinToString(", ")).append('\n')
            }
            return sb.toString().trimEnd()
        }
    }

    data class CopyResult(
        val source: String,
        val destination: String,
        val filesCopied: Int,
        val dirsCreated: Int,
        val bytesCopied: Long,
        val errors: List<String>,
        val cleanedUp: Boolean,
        val complete: Boolean,
    ) {
        fun render(): String {
            val sb = StringBuilder()
            sb.append(if (complete) "Copied " else "PARTIAL copy: ")
            sb.append(source).append(" -> ").append(destination).append('\n')
            sb.append("files: ").append(filesCopied).append(", directories: ").append(dirsCreated)
                .append(", bytes: ").append(bytesCopied).append('\n')
            if (!complete) {
                sb.append("Some items were not copied")
                if (cleanedUp) sb.append("; the partial destination was removed")
                sb.append(".\n")
            }
            errors.take(10).forEach { sb.append("  ! ").append(it).append('\n') }
            if (errors.size > 10) sb.append("  ! ...and ").append(errors.size - 10).append(" more\n")
            return sb.toString().trimEnd()
        }
    }

    fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) { v /= 1024.0; i++ }
        return String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }

    // ── boundary guard ─────────────────────────────────────────────────────

    /**
     * Canonical containment. Both sides go through CanonicalPaths.canonicalFile so a symlinked
     * parent cannot produce a spelling that merely LOOKS inside the root.
     */
    /**
     * Containment for the ENTRY itself rather than what it resolves to.
     *
     * [isInside] canonicalises the whole path, so a symlink inside the workspace that points outside
     * it reads as "outside" and the tool refuses to describe the link at all. That is wrong for
     * `file_stat`: the link LIVES inside the workspace, and the useful answer is "here is the link,
     * and its target leaves the root". So the parent is canonicalised (resolving any link ABOVE it)
     * while the last segment is taken literally.
     */
    fun isInsideEntry(root: String?, path: String): Boolean {
        if (root.isNullOrBlank()) return true
        val f = File(path).absoluteFile
        if (hasTraversal(path)) return false
        val parent = f.parentFile ?: return false
        val r = com.codespace.ide.util.CanonicalPaths.canonical(File(root).absoluteFile).trimEnd('/')
        val p = com.codespace.ide.util.CanonicalPaths.canonical(parent)
        return p == r || p.startsWith(r + "/")
    }

    fun isInside(root: String?, path: String): Boolean {
        if (root.isNullOrBlank()) return true
        return try {
            val r = com.codespace.ide.util.CanonicalPaths.canonical(File(root).absoluteFile)
            val p = com.codespace.ide.util.CanonicalPaths.canonical(File(path).absoluteFile)
            p == r || p.startsWith(r.trimEnd('/') + "/")
        } catch (_: Exception) {
            false
        }
    }

    /** Reject `..` segments before any filesystem work, so a refusal never touches the disk. */
    fun hasTraversal(raw: String): Boolean =
        raw.split('/', '\\').any { it == ".." }

    // ── file_stat ──────────────────────────────────────────────────────────

    fun stat(path: String, root: String?, allowOutside: Boolean = false): Stat {
        val file = File(path)
        if (!allowOutside && !isInsideEntry(root, path)) {
            return Stat(false, false, false, false, null, false, 0, 0, false, false, 0,
                emptyList(), false)
        }
        if (!file.exists()) {
            // exists() follows links: a dangling symlink exists as a link but not as a target.
            val dangling = try { java.nio.file.Files.isSymbolicLink(file.toPath()) } catch (_: Exception) { false }
            if (!dangling) {
                return Stat(false, false, false, false, null, false, 0, 0, false, false, 0,
                    emptyList(), false)
            }
        }
        val isSymlink = try { java.nio.file.Files.isSymbolicLink(file.toPath()) } catch (_: Exception) { false }
        val target = if (isSymlink) {
            try { java.nio.file.Files.readSymbolicLink(file.toPath()).toString() } catch (_: Exception) { null }
        } else null
        // A link is measured by what it points at (that is what a user means by "size"), but the
        // escape flag is about where it points.
        val resolvedTargetFile = if (isSymlink && target != null) {
            val rel = File(target)
            if (rel.isAbsolute) rel else File(file.parentFile, target)
        } else file
        val escapes = isSymlink && target != null && !isInside(root, resolvedTargetFile.path)
        val metaFile = if (isSymlink && !escapes) resolvedTargetFile else file
        val exists = metaFile.exists()
        val isDir = metaFile.isDirectory
        val isFile = metaFile.isFile
        val children = ArrayList<String>()
        var childCount = 0
        var truncated = false
        if (isDir && (!isSymlink || !escapes)) {
            val list = try { metaFile.listFiles() } catch (_: Exception) { null } ?: emptyArray()
            childCount = list.size
            val sorted = list.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            for (f in sorted) {
                if (children.size >= MAX_STAT_CHILDREN) { truncated = true; break }
                children.add(f.name + if (f.isDirectory) "/" else "")
            }
        }
        // An escaping link is DESCRIBED, never measured: File.length()/canRead()/lastModified()
        // all follow the link, so asking them would leak the target's size and permissions.
        val inspectable = exists && !(isSymlink && escapes)
        return Stat(
            exists = exists,
            isDirectory = isDir,
            isFile = isFile,
            isSymlink = isSymlink,
            symlinkTarget = target,
            symlinkEscapesRoot = escapes,
            sizeBytes = if (inspectable && !isDir) metaFile.length() else 0L,
            lastModifiedMs = if (inspectable) metaFile.lastModified() else 0L,
            readable = inspectable && metaFile.canRead(),
            writable = inspectable && metaFile.canWrite(),
            childCount = childCount,
            children = children,
            childrenTruncated = truncated,
        )
    }

    // ── disk_usage ─────────────────────────────────────────────────────────

    /**
     * Iterative (never recursive) walk: a deep tree must not be able to blow the stack, and the
     * deadline must be honoured on the way DOWN, not only between top-level entries.
     *
     * `.git` is excluded by default because its object store dominates every measurement and
     * nobody asking "how big is this project" means the pack files.
     */
    fun diskUsage(
        path: String,
        root: String?,
        excludeGit: Boolean = true,
        maxEntries: Int = MAX_DISK_ENTRIES,
        maxDepth: Int = MAX_DISK_DEPTH,
        budgetMs: Long = MAX_DISK_MS,
        clock: () -> Long = { System.nanoTime() / 1_000_000 },
    ): DiskUsage {
        val started = clock()
        val base = File(path)
        val result = DiskUsage(path, true, null, 0L, 0, 0, 0, 0L, emptyList())
        if (!isInside(root, path)) {
            return result.copy(complete = false, stopReason = "path is outside the workspace root")
        }
        if (!base.exists()) return result.copy(complete = false, stopReason = "no such path")
        if (!base.canRead()) return result.copy(complete = false, stopReason = "path is not readable")
        var bytes = 0L
        var files = 0
        var dirs = 1   // the measured directory itself counts as one
        var deepest = 0
        var entries = 0
        var complete = true
        var stop: String? = null
        val skipped = ArrayList<String>()
        val seenLinks = HashSet<String>()
        // Stack of (dir, depth); the ROOT counts as depth 0.
        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(base to 0)
        while (stack.isNotEmpty()) {
            if (clock() - started > budgetMs) {
                complete = false; stop = "time budget of ${budgetMs}ms reached"; break
            }
            val (dir, depth) = stack.removeLast()
            if (depth > deepest) deepest = depth
            if (depth >= maxDepth) {
                complete = false
                stop = stop ?: "depth limit of $maxDepth reached"
                continue
            }
            val children = try { dir.listFiles() } catch (_: Exception) { null }
            if (children == null) { skipped.add(dir.name); complete = false; stop = stop ?: "an unreadable directory was skipped"; continue }
            for (child in children) {
                entries++
                if (entries > maxEntries) {
                    complete = false; stop = "entry limit of $maxEntries reached"; break
                }
                val name = child.name
                if (excludeGit && name == ".git") { skipped.add(".git"); continue }
                val isLink = try { java.nio.file.Files.isSymbolicLink(child.toPath()) } catch (_: Exception) { false }
                if (isLink) {
                    val target = try { java.nio.file.Files.readSymbolicLink(child.toPath()) } catch (_: Exception) { null }
                    val targetFile = if (target == null) null else {
                        if (target.isAbsolute) File(target.toString()) else File(child.parentFile, target.toString())
                    }
                    if (targetFile == null || !isInside(root, targetFile.path)) {
                        skipped.add(name); continue           // never follow a link out of the root
                    }
                    val key = com.codespace.ide.util.CanonicalPaths.canonical(targetFile.absoluteFile)
                    if (!seenLinks.add(key)) { skipped.add(name); continue }  // loop
                    // A LINKED DIRECTORY IS NOT DESCENDED. Its target is measured where it
                    // actually lives (or not at all, if that is outside the root), so a loop can
                    // never be walked twice and a link cannot inflate the total.
                    if (child.isDirectory) { skipped.add(name + "/"); continue }
                }
                if (child.isDirectory) { dirs++; stack.addLast(child to depth + 1) }
                else if (child.isFile) { files++; bytes += child.length() }
                else skipped.add(name)
            }
            if (!complete && stop?.startsWith("entry limit") == true) break
        }
        return DiskUsage(
            path = path,
            complete = complete,
            stopReason = stop,
            totalBytes = bytes,
            fileCount = files,
            dirCount = dirs,
            depth = deepest,
            elapsedMs = clock() - started,
            skipped = skipped.distinct().take(10),
        )
    }

    // ── copy_path ──────────────────────────────────────────────────────────

    /**
     * Copy a file or a tree. Destination must not exist: overwrite is REFUSED, not offered,
     * because a wrong `cp -r` onto a source directory is the classic way to destroy a project and
     * there is no undo outside the staging path.
     *
     * A symlink is copied AS A LINK only when its target stays inside the root; a link out of the
     * root (or a dangling one) is reported, never materialised, so a copy cannot smuggle reads of
     * an outside file into the workspace.
     */
    fun copy(
        source: String,
        destination: String,
        root: String?,
        maxEntries: Int = MAX_COPY_ENTRIES,
        maxBytes: Long = MAX_COPY_BYTES,
        onFile: ((File, ByteArray) -> Unit)? = null,
    ): CopyResult {
        val src = File(source)
        val dst = File(destination)
        var files = 0
        var dirs = 0
        var bytes = 0L
        val errors = ArrayList<String>()
        fun fail(msg: String): CopyResult {
            val cleaned = try { if (dst.exists()) dst.deleteRecursively() else false } catch (_: Exception) { false }
            errors.add(msg)
            return CopyResult(source, destination, files, dirs, bytes, errors, cleaned, false)
        }
        if (!isInside(root, source)) return CopyResult(source, destination, 0, 0, 0, listOf("source is outside the workspace root"), false, false)
        if (!isInside(root, destination)) return CopyResult(source, destination, 0, 0, 0, listOf("destination is outside the workspace root"), false, false)
        if (!src.exists()) return CopyResult(source, destination, 0, 0, 0, listOf("no such path: $source"), false, false)
        if (dst.exists()) return CopyResult(source, destination, 0, 0, 0, listOf("destination already exists; copy_path never overwrites"), false, false)
        // The destination must not be inside the source: that is a recursive copy by definition.
        val canonicalSrc = com.codespace.ide.util.CanonicalPaths.canonical(src.absoluteFile)
        val canonicalDst = com.codespace.ide.util.CanonicalPaths.canonical((dst.parentFile ?: dst).absoluteFile)
        if (canonicalDst == canonicalSrc || canonicalDst.startsWith(canonicalSrc.trimEnd('/') + "/")) {
            return CopyResult(source, destination, 0, 0, 0, listOf("destination is inside the source; that would copy forever"), false, false)
        }
        dst.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return CopyResult(source, destination, 0, 0, 0, listOf("could not create the destination directory: ${parent.path}"), false, false)
        }
        if (src.isFile) {
            return try {
                val written = copyFile(src, dst, maxBytes, onFile)
                CopyResult(source, destination, 1, 0, written, emptyList(), false, true)
            } catch (e: Exception) {
                fail("could not copy $source: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (!src.isDirectory) return CopyResult(source, destination, 0, 0, 0, listOf("source is neither a file nor a directory: $source"), false, false)
        if (!dst.mkdirs() && !dst.isDirectory) return CopyResult(source, destination, 0, 0, 0, listOf("could not create $destination"), false, false)
        dirs++
        val stack = ArrayDeque<Pair<File, File>>()
        stack.addLast(src to dst)
        var entries = 0
        var depthGuard = ArrayDeque<Pair<File, Int>>()
        depthGuard.addLast(src to 0)
        while (depthGuard.isNotEmpty()) {
            val (dir, depth) = depthGuard.removeLast()
            if (depth >= MAX_COPY_DEPTH) return fail("depth limit of $MAX_COPY_DEPTH reached at ${dir.path}")
            val rel = try { dir.toRelativeString(src) } catch (_: Exception) { "" }
            val outDir = if (rel.isEmpty()) dst else File(dst, rel)
            val children = dir.listFiles() ?: run {
                errors.add("could not read ${dir.path}")
                return fail("could not read ${dir.path}")
            }
            for (child in children) {
                entries++
                if (entries > maxEntries) return fail("entry limit of $maxEntries reached")
                val outChild = File(outDir, child.name)
                val isLink = try { java.nio.file.Files.isSymbolicLink(child.toPath()) } catch (_: Exception) { false }
                if (isLink) {
                    val target = try { java.nio.file.Files.readSymbolicLink(child.toPath()) } catch (_: Exception) { null }
                    val targetFile = if (target == null) null else {
                        if (target.isAbsolute) File(target.toString()) else File(child.parentFile, target.toString())
                    }
                    if (targetFile == null || !isInside(root, targetFile.path)) {
                        errors.add("skipped symlink out of the workspace: ${child.name}")
                        continue
                    }
                    try {
                        java.nio.file.Files.createSymbolicLink(outChild.toPath(), target)
                        continue
                    } catch (_: Exception) {
                        // Filesystems that refuse links get a copy of the target instead.
                        if (targetFile.isFile) {
                            bytes += copyFile(targetFile, outChild, maxBytes - bytes, onFile); files++; continue
                        }
                        errors.add("could not recreate symlink ${child.name}")
                        continue
                    }
                }
                if (child.isDirectory) {
                    if (!outChild.mkdirs() && !outChild.isDirectory) return fail("could not create ${outChild.path}")
                    dirs++
                    depthGuard.addLast(child to depth + 1)
                } else if (child.isFile) {
                    if (bytes + child.length() > maxBytes) return fail("byte limit of $maxBytes reached")
                    try {
                        bytes += copyFile(child, outChild, maxBytes - bytes, onFile)
                        files++
                    } catch (e: Exception) {
                        return fail("could not copy ${child.name}: ${e.message ?: e.javaClass.simpleName}")
                    }
                } else {
                    errors.add("skipped special file: ${child.name}")
                }
            }
        }
        stack.clear()
        return CopyResult(source, destination, files, dirs, bytes, errors, false, errors.isEmpty())
    }

    /**
     * Streaming copy in fixed chunks: a 200MB file must not become a 200MB ByteArray on a 3GB
     * device. When [onFile] is supplied the bytes are handed to it instead of being written, which
     * is how the EDIT-mode staging path reuses this same walk.
     */
    private fun copyFile(src: File, dst: File, remainingBudget: Long, onFile: ((File, ByteArray) -> Unit)?): Long {
        if (src.length() > remainingBudget) throw IOException("byte limit reached at " + src.name)
        val buffer = ByteArray(COPY_CHUNK)
        var total = 0L
        FileInputStream(src).use { input ->
            if (onFile != null) {
                while (true) {
                    val n = input.read(buffer)
                    if (n <= 0) break
                    total += n
                    onFile(src, buffer.copyOf(n))
                }
            } else {
                FileOutputStream(dst).use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        out.write(buffer, 0, n)
                        total += n
                    }
                }
            }
        }
        return total
    }

    /** True when the file looks binary (NUL byte in the first 8KB): the staging path is text-only. */
    fun looksBinary(file: File): Boolean {
        return try {
            FileInputStream(file).use { input ->
                val probe = ByteArray(8192)
                val n = input.read(probe)
                if (n <= 0) return false
                for (i in 0 until n) if (probe[i] == 0.toByte()) return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
