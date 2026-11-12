package com.codespace.ide.agent

import android.content.Context
import com.codespace.ide.util.ZipEngine
import java.io.File

/**
 * The three zip tools, as Copilot sees them: paths in, a short factual report out.
 *
 * WHY THE TOOL LAYER IS THIS THIN (2026-11-12): [ZipEngine] owns the archive rules and the limits
 * and is tested on its own; this object only maps the user's paths into real files, calls the
 * engine, and renders a bounded result. Keeping the two apart is what lets the security tests run
 * without an Android Context, and stops a rendering tweak from ever touching a path check.
 *
 * Output bounds: every result is capped at [MAX_RESULT_CHARS] with an explicit "more" line, because
 * a 2,000-entry archive rendered in full is a prompt budget problem, not a feature.
 */
object ZipTools {

    const val MAX_RESULT_CHARS = 3_000
    private const val MAX_PROBLEM_LINES = 12

    // ── path mapping ─────────────────────────────────────────────────────────

    /**
     * Resolve a tool path to a real file, refusing anything outside the project root.
     *
     * The archive tools write and read whole trees, so "outside the workspace" is not a warning
     * here: it is a refusal. [ToolPathResolver] already produced the host path, so this only has to
     * prove containment.
     */
    fun resolveInside(root: File, raw: String, context: Context? = null): File? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val host = if (context != null) {
            ToolPathResolver.resolve(context, text, root.path)
        } else {
            text
        }
        return try {
            val rootCanon = root.canonicalFile
            val f = File(host)
            val abs = if (f.isAbsolute) f else File(rootCanon, host)
            val canon = abs.canonicalFile
            val rootPath = rootCanon.path + File.separator
            if (canon.path == rootCanon.path || canon.path.startsWith(rootPath)) canon else null
        } catch (_: Exception) {
            null
        }
    }

    // ── create ───────────────────────────────────────────────────────────────

    fun create(
        root: File,
        sourcesRaw: List<String>,
        destRaw: String,
        overwrite: Boolean,
        context: Context? = null,
    ): String {
        if (sourcesRaw.isEmpty()) return "Nothing to archive: give at least one source path."
        val dest = resolveInside(root, destRaw, context)
            ?: return "Refused: the destination is outside the workspace (" + destRaw + ")."
        if (!ZipEngine.isArchiveName(dest.name)) {
            return "Refused: the destination must end in .zip (" + dest.name + ")."
        }
        val sources = ArrayList<File>()
        val refused = ArrayList<String>()
        for (raw in sourcesRaw) {
            val f = resolveInside(root, raw, context)
            if (f == null) refused.add(raw + " (outside the workspace or unreadable)") else sources.add(f)
        }
        if (sources.isEmpty()) {
            return "Refused: no source path was usable. " + refused.joinToString("; ")
        }
        val outcome = when (val o = ZipEngine.create(sources, dest, base = root.canonicalFile, overwrite = overwrite)) {
            is ZipEngine.CreateOutcome.Ok -> o
            is ZipEngine.CreateOutcome.Failed -> return o.reason
        }
        val sb = StringBuilder()
        sb.append("Created ").append(outcome.archive.path).append('\n')
        sb.append("Files: ").append(outcome.files)
        sb.append("  Directories: ").append(outcome.dirs)
        sb.append("  Size: ").append(human(outcome.bytes)).append(" uncompressed\n")
        appendProblems(sb, "Skipped", outcome.skipped)
        if (refused.isNotEmpty()) {
            sb.append("Skipped: ").append(refused.take(MAX_PROBLEM_LINES).joinToString("; ")).append('\n')
        }
        return bound(sb)
    }

    // ── extract ──────────────────────────────────────────────────────────────

    fun extract(
        root: File,
        archiveRaw: String,
        destRaw: String?,
        overwrite: Boolean,
        context: Context? = null,
    ): String {
        val archive = resolveInside(root, archiveRaw, context)
            ?: return "Refused: the archive path is outside the workspace (" + archiveRaw + ")."
        if (!archive.isFile) return "No such archive: " + archiveRaw
        val dest = if (destRaw.isNullOrBlank()) {
            archive.parentFile ?: root
        } else {
            resolveInside(root, destRaw, context)
                ?: return "Refused: the destination is outside the workspace (" + destRaw + ")."
        }
        val outcome = ZipEngine.extract(archive, dest, overwrite)
        if (outcome.files == 0 && outcome.dirs == 0 && outcome.failed.isNotEmpty()) {
            // Nothing landed and something went wrong: say that plainly instead of dressing it up
            // as a partial success with zero files.
            val first = outcome.failed.first()
            return "Extraction failed at " + first.entry + ": " + first.detail
        }
        val sb = StringBuilder()
        sb.append(if (outcome.partial) "Partially extracted " else "Extracted ")
        sb.append(archive.name).append(" into ").append(dest.path).append('\n')
        sb.append("Files: ").append(outcome.files)
        sb.append("  Directories: ").append(outcome.dirs)
        sb.append("  Bytes: ").append(human(outcome.bytes))
        if (outcome.partial) sb.append("  (incomplete)")
        sb.append('\n')
        appendProblems(sb, "Skipped", outcome.skipped)
        appendProblems(sb, "Failed", outcome.failed)
        if (outcome.partial) {
            sb.append("Not every entry was written; the list above says which and why.\n")
        }
        return bound(sb)
    }

    // ── list ─────────────────────────────────────────────────────────────────

    fun list(
        root: File,
        archiveRaw: String,
        offset: Int,
        limit: Int,
        context: Context? = null,
    ): String {
        val archive = resolveInside(root, archiveRaw, context)
            ?: return "Refused: the archive path is outside the workspace (" + archiveRaw + ")."
        val outcome = when (val o = ZipEngine.list(archive, offset, limit)) {
            is ZipEngine.ListOutcome.Ok -> o
            is ZipEngine.ListOutcome.Failed -> return o.reason
        }
        val sb = StringBuilder()
        sb.append(archive.name).append(": ").append(outcome.total).append(" entries, ")
            .append(human(outcome.totalUncompressed)).append(" uncompressed\n")
        if (outcome.suspicious.isNotEmpty()) {
            sb.append("WARNING: ").append(outcome.suspicious.size)
                .append(" entr").append(if (outcome.suspicious.size == 1) "y has" else "ies have")
                .append(" an unsafe path; extraction would refuse them.\n")
        }
        if (outcome.unsupported.isNotEmpty()) {
            appendProblems(sb, "Unsupported", outcome.unsupported)
        }
        val shownTo = outcome.offset + outcome.entries.size
        sb.append("Entries ").append(outcome.offset + 1).append(" to ").append(shownTo)
            .append(if (outcome.total == 0) " (empty archive)" else "").append(":\n")
        for (e in outcome.entries) {
            sb.append("  ")
            sb.append(if (ZipEngine.isDirectoryEntry(e.name)) "dir  " else "file ")
            sb.append(e.name)
            if (!ZipEngine.isDirectoryEntry(e.name)) {
                sb.append("  ").append(human(e.size))
                if (e.compressedSize > 0) sb.append(" (").append(human(e.compressedSize)).append(" compressed)")
            }
            if (ZipEngine.unsafeReason(e.name) != null) sb.append("  UNSAFE")
            sb.append('\n')
            if (sb.length > MAX_RESULT_CHARS) break
        }
        if (shownTo < outcome.total) {
            sb.append("More entries exist: call zip_list with offset=").append(shownTo)
                .append(" for the next page.\n")
        }
        return bound(sb)
    }

    // ── rendering helpers ────────────────────────────────────────────────────

    private fun appendProblems(sb: StringBuilder, label: String, problems: List<ZipEngine.Problem>) {
        if (problems.isEmpty()) return
        sb.append(label).append(": ").append(problems.size).append('\n')
        problems.take(MAX_PROBLEM_LINES).forEach { sb.append("  ").append(it.line()).append('\n') }
        if (problems.size > MAX_PROBLEM_LINES) {
            sb.append("  ...and ").append(problems.size - MAX_PROBLEM_LINES).append(" more\n")
        }
    }

    private fun bound(sb: StringBuilder): String {
        if (sb.length <= MAX_RESULT_CHARS) return sb.toString().trimEnd()
        return sb.substring(0, MAX_RESULT_CHARS).trimEnd() + "\n... (result shortened; refine the request)"
    }

    fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format("%.1f KB", bytes / 1024.0)
        else -> bytes.toString() + " B"
    }
}
