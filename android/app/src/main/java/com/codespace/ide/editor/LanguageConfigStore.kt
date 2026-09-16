package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import org.json.JSONObject
import java.io.File

/**
 * CW7 part 2 — Declarative language configuration (VS Code language-configuration parity).
 *
 * Loads `<workspaceRoot>/.codespace/language-config.json` — one file, sections
 * keyed by language name (lowercase, matching our Language enum) or "all":
 *
 * {
 *   "kotlin": {
 *     "comments": { "lineComment": "//", "blockComment": ["/*", "*/"] },
 *     "brackets": [["(", ")"], ["[", "]"], ["{", "}"]],
 *     "autoClosingPairs": [["(", ")"], ["{", "}"]]
 *   },
 *   "all": { "comments": { "lineComment": "#" } }
 * }
 *
 * - comments.lineComment overrides the editor's Toggle Comment token
 *   (a trailing space is appended if missing, matching built-in behavior).
 * - brackets / autoClosingPairs override BracketPairConfig's per-language
 *   pairs for auto-close, skip-over, surround and match highlighting.
 *   Entries are [open, close] arrays or { "open": "(", "close": ")" } objects;
 *   single-char strings only (BracketPair is Char-based).
 * - Language-specific sections win over "all"; language named sections match
 *   the Language enum case-insensitively (kotlin, Kotlin, KOTLIN all match).
 *
 * Cache is keyed on the file's mtime — editing the config takes effect on the
 * next keystroke without a restart. Malformed JSON is skipped with an Output
 * log, never a crash. blockComment is parsed and stored for future
 * block-comment toggle support (not consumed yet).
 */
internal object LanguageConfigStore {

    data class Config(
        val lineComment: String?,          // normalized with trailing space
        val blockComment: Pair<String, String>?,
        val bracketPairs: List<BracketPair>?,
    )

    private var cacheFile: File? = null
    private var cacheMtime: Long = 0
    private var cached: Config? = null
    private var cachedLang: Language? = null

    /** Combined config for [lang]: "all" section overlaid by the language section. */
    fun configFor(lang: Language, currentFilePath: String?): Config {
        val file = configFileFor(currentFilePath)
            ?: return Config(null, null, null)
        val mtime = file.lastModified()
        if (file == cacheFile && mtime == cacheMtime && lang == cachedLang && cached != null) {
            return cached!!
        }
        val parsed = try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[LANGCFG] skipped malformed language-config.json: ${e.message}", "lsp")
            null
        }
        val result = if (parsed == null) Config(null, null, null)
        else merge(parseSection(parsed.optJSONObject("all")), parseSection(sectionFor(parsed, lang)))

        cacheFile = file; cacheMtime = mtime; cached = result; cachedLang = lang
        return result
    }

    /**
     * Line-comment token for Toggle Comment: workspace config if set,
     * otherwise the built-in per-language token (moved from CodeEditor —
     * identical behavior, CodeEditor was at the 64KB method limit).
     */
    fun lineCommentFor(lang: Language, currentFilePath: String?): String {
        configFor(lang, currentFilePath).lineComment?.let { return it }
        return when (lang) {
            Language.PYTHON, Language.SHELL -> "# "
            Language.KOTLIN, Language.JAVA, Language.JAVASCRIPT,
            Language.TYPESCRIPT, Language.GO, Language.RUST,
            Language.CPP, Language.C -> "// "
            Language.HTML, Language.XML -> "<!-- "
            Language.CSS -> "/* "
            else -> "// "
        }
    }

    /** Workspace bracket pairs for [lang], or null = use BracketPairConfig built-ins. */
    fun bracketPairsFor(lang: Language, currentFilePath: String?): List<BracketPair>? =
        configFor(lang, currentFilePath).bracketPairs

    // ── internals ─────────────────────────────────────────────────────

    private fun sectionFor(root: JSONObject, lang: Language): JSONObject? {
        val name = lang.name.lowercase()
        // Exact lowercase key first, then case-insensitive scan.
        root.optJSONObject(name)?.let { return it }
        for (key in root.keys()) {
            if (key.lowercase() == name && root.optJSONObject(key) != null) return root.optJSONObject(key)
        }
        return null
    }

    private fun parseSection(o: JSONObject?): Partial {
        o ?: return Partial(null, null, null, null)
        val comments = o.optJSONObject("comments")
        var lineComment = comments?.optString("lineComment", null)
        if (lineComment != null) {
            lineComment = lineComment.trim()
            if (lineComment.isEmpty()) lineComment = null
            else if (!lineComment.endsWith(" ")) lineComment += " "
        }
        val block = comments?.optJSONArray("blockComment")
        val blockComment = if (block != null && block.length() >= 2)
            block.optString(0) to block.optString(1) else null
        val pairs = parsePairs(o.optJSONArray("brackets")) ?: parsePairs(o.optJSONArray("autoClosingPairs"))
        return Partial(lineComment, blockComment, pairs, o.optString("comments.lineCommentPlaceholder", null))
    }

    private fun parsePairs(arr: org.json.JSONArray?): List<BracketPair>? {
        arr ?: return null
        val out = mutableListOf<BracketPair>()
        for (i in 0 until arr.length()) {
            val open: String?; val close: String?
            val el = arr.opt(i)
            when (el) {
                is org.json.JSONArray ->
                    if (el.length() >= 2) { open = el.optString(0); close = el.optString(1) } else continue
                is JSONObject -> { open = el.optString("open", null); close = el.optString("close", null) }
                else -> continue
            }
            if (open != null && close != null && open.length == 1 && close.length == 1) {
                val isQuote = open == close && (open == "\"" || open == "'" || open == "`")
                out.add(BracketPair(open[0], close[0], isQuote = isQuote))
            } else {
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[LANGCFG] ignored multi-char pair: '$open' / '$close' (single-char only)", "lsp")
            }
        }
        return out.ifEmpty { null }
    }

    private data class Partial(
        val lineComment: String?,
        val blockComment: Pair<String, String>?,
        val bracketPairs: List<BracketPair>?,
        @Suppress("UNUSED_PARAMETER") val unusedPlaceholder: String?,
    )

    private fun merge(base: Partial, override: Partial): Config {
        val pairs = when {
            base.bracketPairs != null && override.bracketPairs != null -> {
                // Merge by open char: language-specific entries win.
                val byOpen = base.bracketPairs.associateBy { it.open }.toMutableMap()
                override.bracketPairs.forEach { byOpen[it.open] = it }
                byOpen.values.toList()
            }
            override.bracketPairs != null -> override.bracketPairs
            base.bracketPairs != null -> base.bracketPairs
            else -> null
        }
        return Config(
            lineComment = override.lineComment ?: base.lineComment,
            blockComment = override.blockComment ?: base.blockComment,
            bracketPairs = pairs,
        )
    }

    private fun configFileFor(currentFilePath: String?): File? {
        var dir: File? = currentFilePath?.let { File(it).parentFile } ?: return null
        var hops = 0
        while (dir != null && hops < 8) {
            val candidate = File(dir, ".codespace/language-config.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
            hops++
        }
        return null
    }
}
