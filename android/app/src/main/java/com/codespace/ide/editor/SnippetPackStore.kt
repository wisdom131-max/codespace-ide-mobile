package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import org.json.JSONObject
import java.io.File

/**
 * CW7 — Workspace snippet packs (VS Code user-snippets parity).
 *
 * Loads `<workspaceRoot>/.codespace/snippets/<language>.json` (VS Code snippet
 * format: name -> { prefix, body: [lines] or "string", description }) and an
 * `all.json` that applies to every language. Pack entries are ADDED to the
 * built-in snippet list and WIN on label (prefix) collisions — VS Code semantics.
 *
 * Body placeholder syntax ($1, ${1:label}, $0) matches what the existing
 * parseSnippet/SnippetSession tabstop engine already expands — zero new runtime.
 *
 * The workspace root is resolved by walking up from the open file, same as
 * .codespace/modes resolution — no new params threaded through the shell.
 */
internal object SnippetPackStore {

    /** Caches (dir -> parsed entries) keyed by the snippets dir's newest mtime. */
    private var cache: Pair<File, Long>? = null
    private var cachedEntries: List<Completion> = emptyList()

    /**
     * Pack snippets for [lang] from the workspace containing [currentFilePath].
     * Never throws — a malformed pack is skipped and logged to Output.
     */
    fun packSnippets(lang: Language, currentFilePath: String?): List<Completion> {
        val dir = snippetsDirFor(currentFilePath) ?: return emptyList()
        val mtime = dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L
        val c = cache
        if (c != null && c.first == dir && c.second == mtime) return cachedEntries

        val entries = mutableListOf<Completion>()
        for (f in dir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }.orEmpty()) {
            val applies = f.nameWithoutExtension.equals("all", ignoreCase = true) ||
                    f.nameWithoutExtension.equals(lang.name, ignoreCase = true)
            if (!applies) continue
            try {
                val root = JSONObject(f.readText())
                for (name in root.keys()) {
                    val o = root.optJSONObject(name) ?: continue
                    val prefix = o.optString("prefix", "").trim()
                    if (prefix.isEmpty()) continue
                    val body = o.optJSONArray("body")?.let { arr ->
                        (0 until arr.length()).joinToString("\n") { arr.optString(it) }
                    } ?: o.optString("body", "")
                    if (body.isEmpty()) continue
                    val desc = o.optString("description", name)
                    entries.add(Completion(prefix, CompletionKind.SNIPPET, body, desc, insertTextFormat = 2))
                }
            } catch (e: Exception) {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[SNIPPETS] skipped malformed pack ${f.name}: ${e.message}", "lsp")
            }
        }
        cache = dir to mtime
        cachedEntries = entries
        return entries
    }

    /** Walk up from the open file looking for a .codespace/snippets directory. */
    private fun snippetsDirFor(currentFilePath: String?): File? {
        var dir: File? = currentFilePath?.let { File(it).parentFile } ?: return null
        var hops = 0
        while (dir != null && hops < 8) {
            val candidate = File(dir, ".codespace/snippets")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
            hops++
        }
        return null
    }
}
