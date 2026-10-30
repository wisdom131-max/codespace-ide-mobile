package com.codespace.ide.chat.voice

import java.io.File

/**
 * M3 — VOICE TRANSCRIPTS, STORED AS FACT.
 *
 * A dictation result is a real artifact of the session it happened in: it is kept per
 * (project, conversation) so a "Show Voice Transcripts" view always has something true to show,
 * and so a transcript from session A is never presented as if it came from session B.
 *
 * Format: one record per line, fields separated by TAB, with `\`, TAB, CR and LF escaped.
 * Deliberately not JSON: this file must be readable and testable in a plain JVM unit test.
 * A line that does not parse is SKIPPED and counted, never silently repaired.
 */
data class VoiceTranscript(
    val text: String,
    val atMs: Long,
)

data class VoiceTranscriptRead(
    val items: List<VoiceTranscript>,
    /** Lines that could not be parsed; reported, not swallowed. */
    val skipped: Int = 0,
    val reason: String? = null,
)

class VoiceTranscriptStore(private val root: File) {

    fun fileFor(projectId: String, conversationId: String): File =
        File(File(root, safe(projectId)), safe(conversationId) + ".tsv")

    /**
     * Appends one recognised utterance.
     *
     * @return null on success, or a short reason the caller MUST show. Nothing is ever written
     *   when the text is blank: an empty transcript is not a transcript.
     */
    fun append(projectId: String, conversationId: String, text: String, atMs: Long): String? {
        val clean = text.trim()
        if (clean.isEmpty()) return "Nothing was recognised."
        return try {
            val file = fileFor(projectId, conversationId)
            file.parentFile?.mkdirs()
            file.appendText(atMs.toString() + "\t" + escape(clean) + "\n")
            null
        } catch (t: Throwable) {
            "Could not save the transcript (" + (t.message ?: "unknown") + ")."
        }
    }

    fun read(projectId: String, conversationId: String): VoiceTranscriptRead {
        val file = fileFor(projectId, conversationId)
        if (!file.exists()) return VoiceTranscriptRead(emptyList())
        return try {
            val items = ArrayList<VoiceTranscript>()
            var skipped = 0
            file.forEachLine { raw ->
                if (raw.isNotBlank()) {
                    val parsed = parseLine(raw)
                    if (parsed == null) skipped++ else items.add(parsed)
                }
            }
            VoiceTranscriptRead(items, skipped)
        } catch (t: Throwable) {
            VoiceTranscriptRead(emptyList(), 0, "Could not read transcripts (" + (t.message ?: "unknown") + ").")
        }
    }

    /** Removes this conversation's transcripts. Returns null on success or a reason. */
    fun clear(projectId: String, conversationId: String): String? = try {
        val file = fileFor(projectId, conversationId)
        if (file.exists() && !file.delete()) "Could not delete the transcript file." else null
    } catch (t: Throwable) {
        "Could not delete the transcript file (" + (t.message ?: "unknown") + ")."
    }

    companion object {
        const val MAX_TEXT = 4_000

        fun parseLine(raw: String): VoiceTranscript? {
            val tab = raw.indexOf('\t')
            if (tab <= 0) return null
            val at = raw.substring(0, tab).toLongOrNull() ?: return null
            val text = unescape(raw.substring(tab + 1))
            if (text.isBlank()) return null
            return VoiceTranscript(text, at)
        }

        fun escape(text: String): String {
            val clipped = if (text.length > MAX_TEXT) text.substring(0, MAX_TEXT) else text
            val sb = StringBuilder(clipped.length + 8)
            for (ch in clipped) {
                when (ch) {
                    '\\' -> sb.append("\\\\")
                    '\t' -> sb.append("\\t")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        fun unescape(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\\' && i + 1 < text.length) {
                    when (text[i + 1]) {
                        '\\' -> { sb.append('\\'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        else -> { sb.append(ch); i++ }
                    }
                } else {
                    sb.append(ch)
                    i++
                }
            }
            return sb.toString()
        }

        /** Same rule the transcript store uses: letters, digits, dot, dash, underscore. */
        fun safe(id: String): String {
            val sb = StringBuilder(id.length)
            for (ch in id) {
                if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') sb.append(ch)
            }
            val out = sb.toString().take(80)
            return if (out.isBlank()) "default" else out
        }
    }
}
