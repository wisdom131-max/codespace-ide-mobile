package com.codespace.ide.chat

import java.io.File

/**
 * R3-CHAT-PARITY: chat attachments (VS Code attach-context port).
 *
 * Users attach project files to a chat request three ways:
 *  - the attach-file picker (paperclip) in the panel input row,
 *  - explicit chips (removable, above the input),
 *  - "#relative/path.ext" tokens typed directly into the message
 *    (each token that resolves to a real file auto-attaches).
 *
 * Attached content is injected into the LAST user message of the request
 * conversation (never into saved history — chips clear on send, VS Code parity).
 * Content caps keep a huge file from eating the window. Never throws.
 */
data class ChatAttachment(
    val path: String,       // absolute host path
    val relPath: String,    // project-relative (display + prompt)
    val name: String,
    val kind: Kind = Kind.FILE,
    val selText: String? = null,  // SELECTION only: the selected snippet
    /** IMAGE only: sniffed MIME type (image/jpeg, image/png, image/gif, image/webp). */
    val mimeType: String? = null,
    /** C12 s1-b (2026-10-08): attach-time copy + edit state for the sheet. */
    val snapshot: AttachmentSnapshot? = null,
) {
    enum class Kind { FILE, SELECTION, IMAGE, AUDIO }
}

/**
 * C12 s1-b (owner-approved plan, advisor items 1/2/6): the ATTACH-TIME copy that
 * the AttachmentSheet views and edits.
 *
 *  - content: the first SNAPSHOT_LOAD_CAP bytes decoded ("" for binary files) —
 *    the VIEW/COMPARE basis, never silently sent: per advisor item 1 the UNEDITED
 *    attachment keeps a FRESH DISK READ at send time (s1-e); the copy rides the
 *    send ONLY when the user EDITED it (the override, visibly chosen in the sheet).
 *  - truncated: file larger than the load cap — a partial copy, which is exactly
 *    why Save-to-file is impossible for it (Stage-2 gate).
 *  - isBinary: text sniff failed (invalid UTF-8 or a NUL in the first 8 KB).
 *  - attachHash8k: first-8 KB hash AT ATTACH — VIEW/COMPARE basis only (the sheet's
 *    change notice); never a send-input and never the Stage-2 conflict-gate hash.
 *  - edited/editedContent: the user's override state — turns on the moment the
 *    sheet's editor changes anything; Reset restores the attached version.
 */
data class AttachmentSnapshot(
    val content: String,
    val truncated: Boolean,
    val isBinary: Boolean,
    val attachHash8k: String,
    val edited: Boolean = false,
    val editedContent: String? = null,
) {
    companion object {
        const val LOAD_CAP_BYTES = 64 * 1024
        const val HASH_WINDOW_BYTES = 8 * 1024

        /** Pure-Kotlin view/compare hash (SHA-256 hex of the first 8 KB). */
        fun hash8k(bytes: ByteArray): String {
            return try {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                val win = if (bytes.size > HASH_WINDOW_BYTES) bytes.copyOf(HASH_WINDOW_BYTES) else bytes
                md.digest(win).joinToString("") { String.format("%02x", it) }
            } catch (_: Exception) { "" }
        }

        /**
         * Build the attach-time snapshot for a FILE attachment. Text sniff:
         * invalid UTF-8 or a NUL in the first 8 KB => binary (s1-c warns in the
         * sheet; s1-b carries the flag). Reads at most LOAD_CAP_BYTES. Never
         * throws — unreadable files snapshot as an empty, truncated=false copy.
         */
        fun forFile(path: String): AttachmentSnapshot {
            return try {
                val f = java.io.File(path)
                if (!f.isFile || !f.canRead()) return AttachmentSnapshot("", false, true, "")
                val all = f.readBytes()
                val loaded = if (all.size > LOAD_CAP_BYTES) all.copyOf(LOAD_CAP_BYTES) else all
                val win = if (all.size > HASH_WINDOW_BYTES) all.copyOf(HASH_WINDOW_BYTES) else all
                val hash = hash8k(win)
                if (win.contains(0.toByte())) return AttachmentSnapshot("", false, true, hash)
                val decoded: String = try {
                    java.nio.charset.Charset.forName("UTF-8").newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(loaded)).toString()
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return AttachmentSnapshot("", false, true, hash)
                }
                AttachmentSnapshot(decoded, all.size > LOAD_CAP_BYTES, false, hash)
            } catch (_: Exception) {
                AttachmentSnapshot("", false, true, "")
            }
        }

        /** SELECTION snapshots edit the snippet in place — no file basis, no hash. */
        fun forSelection(selText: String): AttachmentSnapshot =
            AttachmentSnapshot(selText, false, false, "")
    }
}

object ChatAttachmentInjector {

    private const val MAX_FILE_CHARS = 12000
    private const val MAX_TOTAL_CHARS = 24000

    /** "#src/Main.kt" style tokens — require an extension so normal hashtags stay intact. */
    private val HASH_TOKEN = Regex("#([A-Za-z0-9_\\-./]+\\.[A-Za-z0-9]+)")

    private val LANG_BY_EXT = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "java" to "java", "py" to "python",
        "js" to "javascript", "mjs" to "javascript", "ts" to "typescript", "tsx" to "tsx",
        "json" to "json", "xml" to "xml", "html" to "html", "css" to "css",
        "md" to "markdown", "sh" to "bash", "bash" to "bash", "yml" to "yaml",
        "yaml" to "yaml", "sql" to "sql", "go" to "go", "rs" to "rust",
        "c" to "c", "h" to "c", "cpp" to "cpp", "hpp" to "cpp", "swift" to "swift",
        "php" to "php", "rb" to "ruby", "gradle" to "kotlin", "properties" to "properties",
        "toml" to "toml", "txt" to "",
    )

    fun langOf(fileName: String): String =
        LANG_BY_EXT[fileName.substringAfterLast('.', "").lowercase()] ?: ""

    /**
     * Format the attachments as a prompt block. Returns "" when list is empty.
     * Selections render as quoted snippets; files as fenced code.
     */
    fun buildBlock(attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## ATTACHED CONTEXT\n")
        sb.append("The user explicitly attached the following to this request.\n")
        var total = 0
        var noted = 0  // CH04 (P4h): image/audio notes count toward "block has content"
        for (att in attachments) {
            // R8-VISION: images ride the request as structured multimodal parts,
            // never as text. CH04 (P4h): restored conversations now still TELL the
            // model an image was attached (previously the block was empty, so a
            // restored conversation hid the image entirely).
            if (att.kind == ChatAttachment.Kind.IMAGE) {
                sb.append("\n### Image attachment: ").append(att.name).append(" (sent as an image part with this message)\n")
                noted++
                continue
            }
            // CH04 (P4h): audio rides the request as a structured audio part —
            // it must never fall through to the file branch's readText() (binary
            // garbage) and, like images, restored conversations still see the note.
            if (att.kind == ChatAttachment.Kind.AUDIO) {
                sb.append("\n### Audio attachment: ").append(att.name).append(" (sent as an audio part with this message)\n")
                noted++
                continue
            }
            if (att.kind == ChatAttachment.Kind.SELECTION) {
                // C12 s1-e (advisor item 1): an EDITED snippet rides the send as
                // the override; an UNEDITED one keeps its original text (the
                // selection IS the fresh state — nothing is re-read from disk).
                val snap = att.snapshot
                val text = (if (snap != null && snap.edited) (snap.editedContent ?: snap.content) else (att.selText ?: ""))
                    .trim().take(MAX_FILE_CHARS)
                if (text.isEmpty()) continue
                sb.append("\n### Selection from ").append(att.relPath).append('\n')
                sb.append(text).append('\n')
                total += text.length
                continue
            }
            try {
                // C12 s1-e (advisor item 1): an EDITED attachment sends the
                // in-memory copy (the override, visibly chosen in the sheet) —
                // an UNEDITED attachment keeps the FRESH DISK READ below, so a
                // stale attach-time snapshot is never sent silently. The
                // unreadable/missing-file class stays for unedited + restored
                // descriptors (honest skip, as today).
                val editedSnap = att.snapshot
                if (editedSnap != null && editedSnap.edited) {
                    val editedRaw = (editedSnap.editedContent ?: editedSnap.content).trim()
                    if (editedRaw.isEmpty()) continue
                    val remainingEdited = MAX_TOTAL_CHARS - total
                    if (remainingEdited <= 0) { sb.append("\n(remaining attachments skipped — context cap reached)\n"); break }
                    val cappedEdited = editedRaw.take(minOf(MAX_FILE_CHARS, remainingEdited))
                    sb.append("\n### File: ").append(att.relPath).append(" (edited copy)").append('\n')
                    val langEdited = langOf(att.name)
                    sb.append("```").append(langEdited).append('\n')
                    sb.append(cappedEdited)
                    if (cappedEdited.length < editedRaw.length) sb.append("\n(file truncated to fit context)")
                    sb.append("\n```\n")
                    total += cappedEdited.length
                    continue
                }
                val f = File(att.path)
                if (!f.isFile || !f.canRead()) continue
                val remaining = MAX_TOTAL_CHARS - total
                if (remaining <= 0) { sb.append("\n(remaining attachments skipped — context cap reached)\n"); break }
                val raw = f.readText().trim()
                if (raw.isEmpty()) continue
                val capped = raw.take(minOf(MAX_FILE_CHARS, remaining))
                sb.append("\n### File: ").append(att.relPath).append('\n')
                val lang = langOf(att.name)
                sb.append("```").append(lang).append('\n')
                sb.append(capped)
                if (capped.length < raw.length) sb.append("\n(file truncated to fit context)")
                sb.append("\n```\n")
                total += capped.length
            } catch (_: Exception) {
                // unreadable attachment — skip
            }
        }
        return if (total == 0 && noted == 0) "" else sb.toString().trimEnd()
    }

    /**
     * Resolve "#path" tokens typed in [text] against [projectRoot].
     * Returns unique FILE attachments for tokens that hit real files.
     */
    fun resolveHashTokens(text: String, projectRoot: String?): List<ChatAttachment> {
        if (projectRoot.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, ChatAttachment>()
        for (m in HASH_TOKEN.findAll(text)) {
            val rel = m.groupValues[1].take(200)
            if (rel.isBlank() || rel.length > 200) continue
            try {
                val f = File(projectRoot, rel)
                if (!f.isFile || !f.canRead()) continue
                val abs = f.absolutePath
                if (!out.containsKey(abs)) {
                    out[abs] = ChatAttachment(
                        path = abs, relPath = rel,
                        name = f.name, kind = ChatAttachment.Kind.FILE,
                    )
                }
            } catch (_: Exception) { }
        }
        return out.values.toList()
    }
}
