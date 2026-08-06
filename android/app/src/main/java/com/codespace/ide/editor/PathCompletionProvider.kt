package com.codespace.ide.editor

import com.codespace.ide.domain.Language
import com.codespace.ide.lsp.LspCompletionItem
import java.io.File

/**
 * P41-G: Path Completion Provider
 *
 * Detects when the cursor is inside a path-like string (after import/from/require/include)
 * and lists filesystem entries as completion items.
 *
 * This is pure filesystem — no LSP round-trip needed.
 * When path context is active, ONLY path completions are shown (no keyword mixing).
 */
object PathCompletionProvider {

    // ── Trigger keywords per language ──────────────────────────────────────────

    /** Keywords that precede a path string, per language family. */
    private val PATH_KEYWORDS: Map<Language, Set<String>> = mapOf(
        Language.JAVASCRIPT to setOf("import", "from", "require", "export", "resolve"),
        Language.TYPESCRIPT to setOf("import", "from", "require", "export", "resolve"),
        Language.PYTHON to setOf("import", "from"),
        Language.KOTLIN to setOf("import"),
        Language.JAVA to setOf("import"),
        Language.CPP to setOf("include", "import"),
        Language.C to setOf("include"),
        Language.GO to setOf("import"),
        Language.RUST to setOf("use", "mod"),
        Language.PHP to setOf("require", "include", "require_once", "include_once", "use"),
        Language.SHELL to setOf("source", "."),
    )

    /** Languages that support bare module imports (node_modules / pip packages). */
    private val MODULE_IMPORT_LANGUAGES = setOf(
        Language.JAVASCRIPT, Language.TYPESCRIPT,
    )

    // ── Context detection ──────────────────────────────────────────────────────

    /**
     * Describes a detected path-completion context at the cursor position.
     *
     * @property baseDir   Absolute path of the directory to list files from.
     * @property prefix    The partial path the user has typed (after the last `/`),
     *                      used to filter directory entries.
     * @property rawPath   The full partial path typed (e.g. "./src/comp", "../util", "react").
     * @property isModule  True if this is a bare module import (should check node_modules).
     * @property quoteChar The quote character that opened the string (' or " or `).
     */
    data class PathContext(
        val baseDir: String,
        val prefix: String,
        val rawPath: String,
        val isModule: Boolean,
        val quoteChar: Char,
    )

    /**
     * Detect whether the cursor is inside a path-like string context.
     *
     * Algorithm:
     * 1. Find the last unescaped quote before the cursor.
     * 2. Look at the text before that quote — does it end with an import keyword?
     * 3. Extract the partial path between the quote and cursor.
     * 4. Resolve the base directory (relative to current file or project root).
     *
     * @return PathContext if detected, null otherwise.
     */
    fun detectPathContext(
        text: String,
        cursor: Int,
        language: Language,
        currentFilePath: String?,
        projectRoot: String?,
    ): PathContext? {
        val keywords = PATH_KEYWORDS[language] ?: return null

        val beforeCursor = text.take(cursor)

        // Find the last unescaped quote character before the cursor
        var quoteIdx = -1
        var quoteChar: Char = ' '
        for (i in beforeCursor.indices.reversed()) {
            val c = beforeCursor[i]
            if ((c == '"' || c == '\'' || c == '`') && (i == 0 || beforeCursor[i - 1] != '\\')) {
                // Check if there's a matching closing quote after this position
                // (i.e. we're inside the string, not after it)
                val afterQuote = beforeCursor.substring(i + 1)
                val closingQuote = afterQuote.indexOf(c)
                if (closingQuote == -1) {
                    // No closing quote yet — we're inside the string
                    quoteIdx = i
                    quoteChar = c
                    break
                }
                // There IS a closing quote — cursor might be after it.
                // Skip this quote and keep looking backwards.
            }
        }

        if (quoteIdx < 0) return null

        // Get text before the quote — trim trailing whitespace and punctuation
        val beforeQuote = beforeCursor.take(quoteIdx).trimEnd().trimEnd('(', '=').trimEnd()

        // Check if any import keyword precedes the quote
        val matchedKeyword = keywords.firstOrNull { kw ->
            beforeQuote.endsWith(kw, ignoreCase = true) &&
            // Ensure it's a word boundary (not a substring of another word)
            (beforeQuote.length == kw.length ||
             !beforeQuote[beforeQuote.length - kw.length - 1].isLetterOrDigit())
        }
        if (matchedKeyword == null) return null

        // Extract the partial path (between opening quote and cursor)
        val partialPath = beforeCursor.substring(quoteIdx + 1)

        // Determine if this is a bare module import (no ./ ../ ~/ / prefix)
        val isBareModule = !partialPath.startsWith("./") &&
                          !partialPath.startsWith("../") &&
                          !partialPath.startsWith("/") &&
                          !partialPath.startsWith("~/") &&
                          partialPath.isNotEmpty()

        val isModule = isBareModule && language in MODULE_IMPORT_LANGUAGES

        // Resolve base directory
        val fileDir = currentFilePath?.let { File(it).parentFile }?.absolutePath
        val root = projectRoot ?: fileDir

        val (baseDir, relativePath) = resolveBaseDir(partialPath, fileDir, root)

        // The prefix is the last path segment (filter for directory listing)
        val prefix = if (relativePath.contains('/')) {
            relativePath.substringAfterLast('/')
        } else {
            relativePath
        }

        return PathContext(
            baseDir = baseDir,
            prefix = prefix,
            rawPath = partialPath,
            isModule = isModule,
            quoteChar = quoteChar,
        )
    }

    /** Resolve the base directory and relative path from the partial path. */
    private fun resolveBaseDir(
        partialPath: String,
        fileDir: String?,
        root: String?,
    ): Pair<String, String> {
        return when {
            partialPath.startsWith("./") -> {
                (fileDir ?: root ?: "") to partialPath.substring(2)
            }
            partialPath.startsWith("../") -> {
                var dir = File(fileDir ?: root ?: "")
                var rest = partialPath
                while (rest.startsWith("../")) {
                    dir = dir.parentFile ?: dir
                    rest = rest.substring(3)
                }
                dir.absolutePath to rest
            }
            partialPath.startsWith("~/") -> {
                (root ?: "") to partialPath.substring(2)
            }
            partialPath.startsWith("/") -> {
                (root ?: "") to partialPath.substring(1)
            }
            else -> {
                (fileDir ?: root ?: "") to partialPath
            }
        }
    }

    // ── Directory listing ──────────────────────────────────────────────────────

    /**
     * List directory contents as LSP completion items.
     *
     * @param ctx   The detected path context.
     * @return List of LspCompletionItem with file/folder names, filtered by prefix.
     */
    fun listPathCompletions(ctx: PathContext): List<LspCompletionItem> {
        // Navigate into subdirectories if the partial path contains slashes
        val dir = if (ctx.rawPath.contains('/')) {
            val parts = ctx.rawPath.split('/')
            var current = File(ctx.baseDir)
            // Navigate all parts except the last (which is the filter prefix)
            for (i in 0 until parts.size - 1) {
                if (parts[i].isEmpty()) continue
                current = File(current, parts[i])
                if (!current.isDirectory) return emptyList()
            }
            current
        } else {
            File(ctx.baseDir)
        }

        if (!dir.isDirectory) return emptyList()

        val prefix = ctx.prefix

        val entries = dir.listFiles()?.filter { file ->
            // Filter by prefix
            if (prefix.isEmpty()) {
                // Show everything, but hide hidden files unless prefix starts with .
                !file.name.startsWith(".") || prefix.startsWith(".")
            } else {
                file.name.startsWith(prefix, ignoreCase = true)
            }
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

        return entries.take(50).map { file ->
            val isDir = file.isDirectory
            LspCompletionItem(
                label = if (isDir) file.name + "/" else file.name,
                detail = if (isDir) "folder" else formatFileSize(file.length()),
                insertText = if (isDir) file.name + "/" else file.name,
                // LSP CompletionItemKind: 17 = File, 19 = Folder, 9 = Module
                kind = if (isDir) 19 else 17,
            )
        }
    }

    /**
     * List installed Node.js modules from node_modules directory.
     * Used for bare imports like `import "react"` or `require("express")`.
     */
    fun listNodeModules(
        projectRoot: String?,
        prefix: String,
    ): List<LspCompletionItem> {
        val nodeModules = projectRoot?.let { File(it, "node_modules") } ?: return emptyList()
        if (!nodeModules.isDirectory) return emptyList()

        // Also check package.json dependencies for the actual list
        val pkgJson = File(projectRoot, "package.json")
        val depNames = mutableSetOf<String>()
        if (pkgJson.exists()) {
            try {
                val json = org.json.JSONObject(pkgJson.readText())
                val deps = json.optJSONObject("dependencies")
                val devDeps = json.optJSONObject("devDependencies")
                deps?.keys()?.forEach { depNames.add(it) }
                devDeps?.keys()?.forEach { depNames.add(it) }
            } catch (_: Exception) {}
        }

        // Merge: directory listing + package.json deps
        val dirEntries = nodeModules.listFiles()?.filter { dir ->
            dir.isDirectory &&
            !dir.name.startsWith(".") &&
            (prefix.isEmpty() || dir.name.startsWith(prefix, ignoreCase = true))
        }?.map { it.name } ?: emptyList()

        val allNames = (dirEntries + depNames)
            .filter { prefix.isEmpty() || it.startsWith(prefix, ignoreCase = true) }
            .distinct()
            .sortedBy { it.lowercase() }

        return allNames.take(30).map { name ->
            val isScoped = name.startsWith("@")
            LspCompletionItem(
                label = if (isScoped) name + "/" else name,
                detail = "npm",
                insertText = if (isScoped) name + "/" else name,
                kind = 9, // Module
            )
        }
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "${bytes / (1024 * 1024)}MB"
    }

    /**
     * Check if a given cursor position is inside a string literal.
     * Simple heuristic: count unescaped quotes before cursor.
     */
    fun isInString(text: String, cursor: Int): Boolean {
        var inString = false
        var stringChar: Char? = null
        var i = 0
        while (i < cursor) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                i += 2 // skip escaped char
                continue
            }
            if (!inString && (c == '"' || c == '\'' || c == '`')) {
                inString = true
                stringChar = c
            } else if (inString && c == stringChar) {
                inString = false
                stringChar = null
            }
            i++
        }
        return inString
    }
}
