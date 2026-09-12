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
) {
    enum class Kind { FILE, SELECTION }
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
        for (att in attachments) {
            if (att.kind == ChatAttachment.Kind.SELECTION) {
                val text = (att.selText ?: "").trim().take(MAX_FILE_CHARS)
                if (text.isEmpty()) continue
                sb.append("\n### Selection from ").append(att.relPath).append('\n')
                sb.append(text).append('\n')
                total += text.length
                continue
            }
            try {
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
        return if (total == 0) "" else sb.toString().trimEnd()
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
