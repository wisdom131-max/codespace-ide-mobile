package com.codespace.ide.agent

import java.io.File

/**
 * P2 (2026-11-20) — context offloading.
 *
 * A tool that prints a 5 MB build log must not put 5 MB into the next request. Past the threshold
 * the complete output goes to a session-scoped scratch file and the model receives a pointer plus
 * honest excerpts. Nothing is silently dropped, and the visible text never claims to be complete.
 */
object ContextOffload {

    /** Above this many characters a result is offloaded. Small results keep today's behaviour. */
    const val THRESHOLD_CHARS = 12_000

    /** How much of the head and the tail the model sees inline. */
    const val EXCERPT_CHARS = 1_000

    /** Scratch files older than this are deleted on the next offload. */
    const val KEEP_DAYS = 7

    fun shouldOffload(length: Int): Boolean = length > THRESHOLD_CHARS

    data class Ref(val reference: String, val file: File)

    private fun safe(s: String): String = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun dir(root: File, projectId: String?, sessionId: String): File =
        File(File(File(root, "offload"), safe(projectId ?: "user")), safe(sessionId))

    /** Writes the complete output and returns its reference, or null when the write failed. */
    fun store(root: File, projectId: String?, sessionId: String, tool: String, output: String): Ref? {
        val d = dir(root, projectId, sessionId)
        return try {
            if (!d.isDirectory && !d.mkdirs()) return null
            cleanup(root, KEEP_DAYS, System.currentTimeMillis())
            val name = safe(tool) + "-" + Integer.toHexString(output.hashCode()) + ".txt"
            val f = File(d, name)
            // The same output twice in one session reuses the file instead of duplicating it.
            if (!f.isFile || f.length() != output.toByteArray().size.toLong()) f.writeText(output)
            Ref("offload://" + (projectId ?: "user") + "/" + sessionId + "/" + name, f)
        } catch (_: Throwable) {
            null
        }
    }

    /** Reads a reference back. Refuses anything that escapes its own session directory. */
    fun read(root: File, reference: String): String? {
        val parts = reference.removePrefix("offload://").split('/')
        if (parts.size != 3) return null
        if (parts.any { it.isEmpty() || it == ".." || it.contains('\\') }) return null
        val f = File(dir(root, parts[0], parts[1]), parts[2])
        return try {
            if (f.isFile) f.readText() else null
        } catch (_: Throwable) {
            null
        }
    }

    /** What the model and the row see instead of the full output. */
    fun summary(tool: String, ref: String, output: String): String {
        val head = output.take(EXCERPT_CHARS)
        val tail = output.takeLast(EXCERPT_CHARS)
        return "[" + tool + " output offloaded: " + output.length + " characters, not shown in full]" +
            "\nComplete output: " + ref +
            "\nRead it with read_file using that path." +
            "\n--- first " + head.length + " chars ---\n" + head +
            "\n--- last " + tail.length + " chars ---\n" + tail
    }

    /** Deletes scratch files past retention. A failure here never fails the tool call. */
    fun cleanup(root: File, keepDays: Int, now: Long) {
        val cutoff = now - keepDays * 24L * 60L * 60L * 1000L
        runCatching {
            File(root, "offload").listFiles()?.forEach { proj ->
                proj.listFiles()?.forEach { sess ->
                    sess.listFiles()?.forEach { f -> if (f.lastModified() < cutoff) f.delete() }
                }
            }
        }
    }

    /**
     * The entry point the chat path calls. Small output is returned untouched; a failed write
     * returns an honest line rather than losing the text or pretending it was trimmed.
     */
    fun apply(root: File, projectId: String?, sessionId: String, tool: String, output: String): String {
        if (!shouldOffload(output.length)) return output
        val ref = store(root, projectId, sessionId, tool, output)
        if (ref == null) {
            return "[" + tool + " output is " + output.length + " characters and could not be " +
                "offloaded to a scratch file. The full output is NOT available; re-run with a " +
                "narrower scope.]\n" + output.take(EXCERPT_CHARS)
        }
        return summary(tool, ref.reference, output)
    }
}
