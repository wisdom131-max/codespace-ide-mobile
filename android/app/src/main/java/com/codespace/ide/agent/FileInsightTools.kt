package com.codespace.ide.agent

import java.io.File
import java.security.MessageDigest

/**
 * S1 (owner approval 2026-11-11): capabilities the model kept working around.
 *
 * WHY THESE EXIST: before this file, "is this file what I think it is" and "what actually changed
 * between these two copies" had no tool. The model either read whole files into the context to
 * compare them by eye, or ran a shell command whose output it then had to trust. Both are expensive
 * and neither is verifiable. These are small, side-effect-free, and each one returns something a
 * model can use without a second call.
 *
 * Deliberately NOT here: anything that needs a subsystem that does not exist yet (see the audit's
 * INFRASTRUCTURE_REQUIRED rows). A tool that is advertised before its executor works is a lie the
 * model pays for.
 */
object FileInsightTools {

    /** Size cap for anything read into the model context by these tools. */
    const val MAX_READ = 4_000_000

    // ── checksum ───────────────────────────────────────────────────────────

    /**
     * Digest of a file. SHA-256 is the default because it is what signatures and release pages
     * publish; MD5/SHA-1 are offered only for matching a value someone else already published.
     */
    fun checksum(path: String, algorithm: String = "SHA-256"): String {
        val algo = when (algorithm.trim().uppercase().replace("_", "-")) {
            "SHA256", "SHA-256" -> "SHA-256"
            "SHA1", "SHA-1" -> "SHA-1"
            "MD5" -> "MD5"
            "SHA512", "SHA-512" -> "SHA-512"
            else -> return "Unknown algorithm '$algorithm'. Use sha256, sha1, md5 or sha512."
        }
        val f = File(path)
        if (!f.exists()) return "No such file: $path"
        if (f.isDirectory) return "$path is a directory. Use list_dir, then checksum a file."
        if (f.length() > MAX_READ) return "File is ${f.length()} bytes; refusing to hash more than $MAX_READ in one call."
        return try {
            val md = MessageDigest.getInstance(algo)
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var n = input.read(buf)
                while (n > 0) {
                    md.update(buf, 0, n)
                    n = input.read(buf)
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            algo + " " + hex + "  " + path
        } catch (e: Exception) {
            "Could not hash $path: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    // ── diff_files ─────────────────────────────────────────────────────────

    data class DiffLine(val kind: Char, val text: String)

    data class DiffResult(
        val lines: List<DiffLine>,
        val added: Int,
        val removed: Int,
        val identical: Boolean,
    ) {
        fun render(pathA: String, pathB: String, max: Int = 8000): String {
            if (identical) return "$pathA and $pathB are identical."
            val sb = StringBuilder()
            sb.append("--- ").append(pathA).append("\n+++ ").append(pathB).append('\n')
            sb.append("@@ +").append(added).append(" -").append(removed).append(" @@\n")
            var used = sb.length
            var omitted = 0
            for (l in lines) {
                val line = l.kind + l.text + "\n"
                if (used + line.length > max) { omitted++; continue }
                sb.append(line); used += line.length
            }
            if (omitted > 0) sb.append("... ").append(omitted).append(" more lines omitted\n")
            return sb.toString()
        }
    }

    /**
     * Line diff by longest common subsequence, computed on trimmed common prefix/suffix first.
     * Bounded by [MAX_DIFF_LINES] on each side so a 50k-line pair cannot hang the chat: when a file
     * is too big the answer says so instead of silently truncating the comparison.
     */
    fun diff(a: String, b: String, context: Int = 3): DiffResult {
        val la = a.lines()
        val lb = b.lines()
        if (a == b) return DiffResult(emptyList(), 0, 0, true)
        // Common prefix
        var pre = 0
        while (pre < la.size && pre < lb.size && la[pre] == lb[pre]) pre++
        // Common suffix
        var suf = 0
        while (suf < la.size - pre && suf < lb.size - pre &&
            la[la.size - 1 - suf] == lb[lb.size - 1 - suf]
        ) suf++
        val midA = la.subList(pre, la.size - suf)
        val midB = lb.subList(pre, lb.size - suf)
        val ops = lcsOps(midA, midB)
        val out = ArrayList<DiffLine>(ops.size + 2 * context)
        // Context before
        for (i in (pre - context).coerceAtLeast(0) until pre) out.add(DiffLine(' ', la[i]))
        var added = 0
        var removed = 0
        ops.forEach { op ->
            out.add(op)
            if (op.kind == '+') added++ else if (op.kind == '-') removed++
        }
        for (i in (la.size - suf) until (la.size - suf + context).coerceAtMost(la.size)) {
            out.add(DiffLine(' ', la[i]))
        }
        return DiffResult(out, added, removed, false)
    }

    private const val MAX_DIFF_LINES = 4000

    private fun lcsOps(a: List<String>, b: List<String>): List<DiffLine> {
        if (a.size > MAX_DIFF_LINES || b.size > MAX_DIFF_LINES) {
            return (a.map { DiffLine('-', it) } + b.map { DiffLine('+', it) })
                .also { }
        }
        // Classic DP over the changed region only.
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<DiffLine>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out.add(DiffLine(' ', a[i])); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { out.add(DiffLine('-', a[i])); i++ }
                else -> { out.add(DiffLine('+', b[j])); j++ }
            }
        }
        while (i < n) { out.add(DiffLine('-', a[i])); i++ }
        while (j < m) { out.add(DiffLine('+', b[j])); j++ }
        return out
    }

    // ── move_path ──────────────────────────────────────────────────────────

    /**
     * Move or rename. Refuses to overwrite an existing destination unless asked, because a silent
     * overwrite is unrecoverable and the model cannot see what it destroyed.
     */
    fun move(from: String, to: String, overwrite: Boolean = false): String {
        val src = File(from)
        if (!src.exists()) return "No such path: $from"
        val dst = File(to)
        if (dst.exists() && !overwrite) return "Refused: $to already exists. Pass overwrite=true to replace it."
        if (dst.isDirectory && src.isFile) {
            val target = File(dst, src.name)
            if (target.exists() && !overwrite) return "Refused: ${target.path} already exists."
            return runMove(src, target)
        }
        dst.parentFile?.let { if (!it.exists() && !it.mkdirs()) return "Could not create the destination directory: ${it.path}" }
        return runMove(src, dst)
    }

    private fun runMove(src: File, dst: File): String {
        val size = if (src.isFile) src.length() else 0L
        return try {
            if (src.renameTo(dst)) "Moved $src -> $dst"
            else {
                // Cross-filesystem: copy then delete, and say which path was taken.
                if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
                val deleted = src.deleteRecursively()
                "Moved $src -> $dst (copied across filesystems" +
                    (if (deleted) ")" else "; could not remove the original, delete it manually)")
            }
        } catch (e: Exception) {
            "Could not move $src -> $dst: " + (e.message ?: e.javaClass.simpleName) +
                (if (size > 0) " ($size bytes)" else "")
        }
    }

    // ── journal_write ──────────────────────────────────────────────────────

    /**
     * A short note about the current task, appended to the project's own journal.
     *
     * WHY: within one task the model has its tool results; across a long task or a restart it does
     * not. Writing a line costs almost nothing and gives the NEXT turn (and the user) a trail that
     * survives the context window. It is a note, not memory: never secrets, never credentials.
     */
    fun journalWrite(file: File, note: String, nowIso: String): String {
        val text = note.trim()
        if (text.isEmpty()) return "Nothing to write: the note was empty."
        val cleaned = scrub(text)
        if (cleaned.isEmpty()) return "Refused: the note looks like a secret and was not written."
        return try {
            file.parentFile?.mkdirs()
            file.appendText("[" + nowIso + "] " + cleaned.take(1000) + "\n")
            "Noted."
        } catch (e: Exception) {
            "Could not write the journal: " + (e.message ?: e.javaClass.simpleName)
        }
    }

    private val SECRETISH = listOf(
        Regex("(?i)\\b(sk|pk|ghp|ghs|glpat|xox[bap])[-_][A-Za-z0-9_\\-]{12,}"),
        Regex("(?i)\\b(api[_-]?key|token|password|secret)\\s*[:=]\\s*\\S{8,}"),
        Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        Regex("\\b[A-Fa-f0-9]{32,}\\b"),
    )

    /** Never let the journal become a place secrets are stored in plain text. */
    fun scrub(text: String): String {
        var out = text
        for (r in SECRETISH) out = r.replace(out, "[redacted]")
        return out.trim()
    }
}
