package com.codespace.ide.agent

/**
 * Pure helpers behind the VS Code-parity search tools.
 *
 * Everything here is testable on the JVM with plain strings, so the disk walk lives in
 * AgentTools and the decisions live here. That split is deliberate: the walker can fail
 * (permissions, huge trees) and the answer shaping must still be provably correct.
 */
internal object RepoSearch {

    /** How many matches a tool result may carry before it must say it was cut. */
    const val MAX_HITS = 80

    /** Files a single tool call may scan. Above this the answer says it stopped. */
    const val MAX_FILES = 400

    /**
     * Symbol search: a line that DECLARES something whose name matches [query].
     *
     * This is text-based, not a language server, and it says so in its output. VS Code's
     * `search_workspace_symbols` is backed by language intelligence; calling ours the same
     * thing without a caveat would be a lie the model then repeats to the user.
     */
    fun symbols(
        files: List<Pair<String, String>>,
        query: String,
        max: Int = MAX_HITS,
    ): List<String> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val pattern = try {
            Regex(
                "\\b(fun|class|object|interface|enum|data class|struct|def|function|func|const|val|var|" +
                    "type|trait|impl|module)\\s+([A-Za-z_][A-Za-z0-9_]*)",
            )
        } catch (t: Throwable) {
            return emptyList()
        }
        val lower = needle.lowercase()
        val out = ArrayList<String>()
        for ((path, text) in files) {
            if (text.isEmpty()) continue
            var line = 0
            for (raw in text.lineSequence()) {
                line++
                val m = pattern.find(raw) ?: continue
                val name = m.groupValues[2]
                if (!name.lowercase().contains(lower)) continue
                out.add(path + ":" + line + " " + raw.trim().take(160))
                if (out.size >= max) return out
            }
        }
        return out
    }

    /**
     * Project structure as an indented tree, bounded by depth and entries.
     *
     * Directories end with "/" and nothing is invented: an entry that could not be read is
     * simply not listed, because a placeholder in a structure map reads like a real file.
     */
    fun structure(
        paths: List<String>,
        maxDepth: Int = 2,
        maxEntries: Int = 120,
    ): String {
        val depth = maxDepth.coerceIn(1, 6)
        val entries = ArrayList<String>()
        var truncated = false
        val seen = HashSet<String>()
        for (raw in paths.sorted()) {
            val clean = raw.trim().trimStart('/')
            if (clean.isEmpty()) continue
            val parts = clean.split('/').filter { it.isNotEmpty() }
            if (parts.size > depth) continue
            val joined = parts.joinToString("/")
            if (!seen.add(joined)) continue
            val indent = "  ".repeat(parts.size - 1)
            val last = parts.last()
            entries.add(indent + last + if (raw.endsWith("/")) "/" else "")
            if (entries.size >= maxEntries) {
                truncated = true
                break
            }
        }
        if (entries.isEmpty()) return "No readable entries under this path."
        val head = "Structure (depth " + depth + "):"
        return if (truncated) {
            head + "\n" + entries.joinToString("\n") +
                "\n[... stopped at " + maxEntries + " entries]"
        } else {
            head + "\n" + entries.joinToString("\n")
        }
    }

    /**
     * Several replacements in ONE file, all or nothing.
     *
     * All-or-nothing matters more than it looks: applying the first three of five edits and
     * then failing leaves a file in a state the user never asked for and the model never saw.
     * So every pair is validated against the evolving text first, and any failure aborts with
     * the whole operation untouched.
     */
    fun replaceMany(
        current: String,
        pairs: List<Pair<String, String>>,
        replaceAll: Boolean = false,
    ): ReplaceOutcome {
        if (pairs.isEmpty()) return ReplaceOutcome.Refused("No replacements were supplied.")
        var working = current
        var applied = 0
        for ((index, pair) in pairs.withIndex()) {
            val (oldText, newText) = pair
            val single = ReplaceText.apply(working, oldText, newText, replaceAll)
            when (single) {
                is ReplaceOutcome.Refused ->
                    return ReplaceOutcome.Refused(
                        "Replacement " + (index + 1) + " of " + pairs.size + " failed: " + single.reason +
                            ". Nothing was changed.",
                    )
                is ReplaceOutcome.Ok -> {
                    working = single.updated
                    applied += single.count
                }
            }
        }
        return ReplaceOutcome.Ok(working, applied)
    }
}
