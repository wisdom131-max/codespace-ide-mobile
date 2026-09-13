package com.codespace.ide.chat

import java.io.File

/**
 * R9-B — Skills (approved R9_PREPLAN, D4/D5).
 *
 * A skill = reusable prompt package with metadata + an optional context hint:
 *   ---
 *   name: Write unit tests
 *   description: Generate tests for the current selection or file
 *   context: selection     # selection | file | diff | problems | clipboard | none
 *   ---
 *   Write thorough unit tests...
 *
 * Sources: (1) a small curated BUILT-IN set, (2) project files in
 * project files in the .codespace/skills dir (same frontmatter-lite parser as custom
 * modes — no YAML lib, no code execution, D5), (3) MCP prompts (R9-C, added
 * to the same surface).
 *
 * D4 — execution model: tap = PREFILL the input + auto-attach the hinted
 * context, then STOP. A skill NEVER auto-sends; the user always reviews and
 * presses send.
 *
 * Deliberately separate from I6 prompt files (.github/prompts +
 * .codespace/prompts = raw text insert): skills carry metadata + context
 * hints + the confirmation flow, in their own directory.
 */
object SkillsCatalog {

    data class Skill(
        val id: String,
        val name: String,
        val description: String,
        val context: String?,          // hint key or null
        val body: String,
        val source: String,            // "builtin" | "project" | "mcp"
    )

    private const val MAX_CTX_CHARS = 8000

    fun builtin(): List<Skill> = listOf(
        Skill(
            id = "explain-file", name = "Explain this file",
            description = "Walk through the attached file's structure and purpose",
            context = "file",
            body = "Explain the attached file. Walk through its structure section by " +
                "section, what each part does, and how it fits the wider project. Be concrete " +
                "and reference real identifiers, not generalities.",
            source = "builtin",
        ),
        Skill(
            id = "write-tests", name = "Write unit tests",
            description = "Generate thorough tests for the current selection or file",
            context = "selection",
            body = "Write thorough unit tests for the attached code. Cover the happy path, " +
                "edge cases, and failure modes. Use the project's existing test style and " +
                "naming if visible; otherwise JUnit-style. Output complete runnable test code.",
            source = "builtin",
        ),
        Skill(
            id = "fix-problem", name = "Fix this problem",
            description = "Diagnose and fix the attached diagnostic",
            context = "problems",
            body = "Diagnose the attached problem(s). Find the root cause in the project, " +
                "explain it in one short paragraph, then produce the exact minimal fix.",
            source = "builtin",
        ),
        Skill(
            id = "review-diff", name = "Review working diff",
            description = "Review the uncommitted changes for bugs and issues",
            context = "diff",
            body = "Review the attached working diff like a strict senior reviewer. Flag real " +
                "bugs, risky changes, and anything that breaks compatibility. Ignore style " +
                "nits unless they hide bugs. End with a verdict: ship / fix first.",
            source = "builtin",
        ),
        Skill(
            id = "explain-trace", name = "Explain this stack trace",
            description = "Explain the attached error/stack trace and locate the cause",
            context = "clipboard",
            body = "Explain the attached error/stack trace: what failed, why, and where the " +
                "faulty code most likely lives in this project. Give the exact file/function " +
                "to look at and a concrete fix.",
            source = "builtin",
        ),
    )

    /** Project skills from the .codespace/skills dir (malformed files skipped). */
    fun projectSkills(projectRoot: String?): List<Skill> {
        if (projectRoot.isNullOrBlank()) return emptyList()
        return try {
            val dir = File(projectRoot, ".codespace/skills")
            (dir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
                ?.toList() ?: emptyList())
                .sortedBy { it.name }
                .mapNotNull { f ->
                    val id = f.name.removeSuffix(".md")
                    val fm = parseFrontmatter(f.readText()) ?: return@mapNotNull null
                    if (fm.body.isBlank()) return@mapNotNull null
                    Skill(
                        id = id,
                        name = fm.fields["name"]?.takeIf { it.isNotBlank() } ?: id,
                        description = fm.fields["description"] ?: "",
                        context = fm.fields["context"]?.takeIf { it.isNotBlank() },
                        body = fm.body,
                        source = "project",
                    )
                }
        } catch (_: Exception) { emptyList() }
    }

    fun all(projectRoot: String?): List<Skill> = builtin() + projectSkills(projectRoot)

    /**
     * R9-C — MCP prompts as read-only skills. Only ALREADY-CONNECTED servers
     * appear (lazy spawn by design — they connect on first chat message);
     * id encodes server+prompt so runSkill can fetch via prompts/get.
     */
    fun mcpSkills(): List<Skill> =
        com.codespace.ide.agent.McpClientManager.cachedPrompts().map { p ->
            Skill(
                id = "mcp:" + p.server + ":" + p.name,
                name = p.name,
                description = p.description.ifBlank { "MCP prompt from " + p.server },
                context = null,
                body = "",   // fetched at run time via prompts/get
                source = "mcp",
            )
        }

    /**
     * Resolve a skill's context hint to an auto-attach ChatAttachment (D4).
     * Returns null when the hinted context is unavailable (skill still prefills
     * the input — the user can attach manually). Never throws.
     */
    fun buildContextAttachment(
        hint: String?,
        projectRoot: String?,
        currentFilePath: String?,
        androidContext: android.content.Context,
    ): ChatAttachment? {
        return try {
        when (hint) {
            "selection" -> {
                val sel = com.codespace.ide.editor.EditorSelectionStore.take()
                if (sel == null || sel.selText.isNullOrBlank()) null
                else {
                    val f = File(sel.filePath)
                    val rel = try {
                        if (f.path.startsWith(projectRoot ?: "")) f.relativeTo(File(projectRoot)).path else f.name
                    } catch (_: Exception) { f.name }
                    ChatAttachment(
                        path = sel.filePath, relPath = rel, name = f.name,
                        kind = ChatAttachment.Kind.SELECTION, selText = sel.selText,
                    )
                }
            }
            "file" -> {
                if (currentFilePath.isNullOrBlank()) null
                else {
                    val f = File(currentFilePath)
                    val rel = try {
                        if (f.path.startsWith(projectRoot ?: "")) f.relativeTo(File(projectRoot)).path else f.name
                    } catch (_: Exception) { f.name }
                    ChatAttachment(
                        path = currentFilePath, relPath = rel, name = f.name,
                        kind = ChatAttachment.Kind.FILE,
                    )
                }
            }
            "diff" -> {
                val root = projectRoot ?: return null
                fun runGit(vararg a: String): String =
                    when (val r = com.codespace.ide.scm.GitCommandExecutor.run(androidContext, a.toList(), workdir = root)) {
                        is com.codespace.ide.scm.GitResult.Ok -> r.output
                        is com.codespace.ide.scm.GitResult.Err -> ""
                    }
                val diff = runGit("diff", "HEAD").take(MAX_CTX_CHARS)
                if (diff.isBlank()) null
                else ChatAttachment(
                    path = "git", relPath = "git-diff", name = "git-diff",
                    kind = ChatAttachment.Kind.SELECTION,
                    selText = "Working diff vs HEAD:\n" + diff,
                )
            }
            "problems" -> {
                val diags = com.codespace.ide.diagnostics.DiagnosticManager.diagnostics
                    .filter { !it.isStale }.take(50)
                if (diags.isEmpty()) null
                else ChatAttachment(
                    path = "problems", relPath = "problems", name = "problems",
                    kind = ChatAttachment.Kind.SELECTION,
                    selText = diags.joinToString("\n") { d ->
                        (if (d.severity == com.codespace.ide.diagnostics.DiagnosticManager.Severity.ERROR) "[ERROR] " else "[WARN] ") +
                            d.filePath.substringAfterLast('/') + ":" + d.range.startLine + " " + d.message
                    },
                )
            }
            "clipboard" -> {
                val cm = androidContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                val clip = try { cm.primaryClip?.getItemAt(0)?.coerceToText(androidContext)?.toString() ?: "" } catch (_: Exception) { "" }
                val content = clip.take(MAX_CTX_CHARS).ifBlank {
                    (com.codespace.ide.terminal.TerminalAiBridge.transcriptTail() ?: "").take(MAX_CTX_CHARS)
                }
                if (content.isBlank()) null
                else ChatAttachment(
                    path = "clipboard", relPath = "clipboard", name = "clipboard",
                    kind = ChatAttachment.Kind.SELECTION, selText = content,
                )
            }
            else -> null
        }
    } catch (_: Exception) { null }
    }
}
