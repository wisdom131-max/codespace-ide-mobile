package com.codespace.ide.editor

import com.codespace.ide.domain.Language

/**
 * P41-U: Built-in source actions that work WITHOUT an LSP server.
 * Provides "Organize Imports" and "Remove Unused Imports" for languages
 * where LSP may not be running or may not support these source actions.
 *
 * Supported languages: Kotlin, Java, Python, JavaScript, TypeScript
 */
object BuiltinSourceActions {

    /**
     * Organize imports: sort alphabetically, remove duplicates, group by package.
     * Returns the modified file content, or null if no changes were made.
     */
    fun organizeImports(content: String, language: Language): String? {
        return when (language) {
            Language.KOTLIN, Language.JAVA -> organizeKotlinJavaImports(content)
            Language.PYTHON -> organizePythonImports(content)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> organizeJsTsImports(content)
            else -> null
        }
    }

    /**
     * Remove unused imports: detect which imported symbols are not referenced
     * in the code body and remove their import lines.
     * Returns the modified file content, or null if no changes were made.
     */
    fun removeUnusedImports(content: String, language: Language): String? {
        return when (language) {
            Language.KOTLIN, Language.JAVA -> removeUnusedKotlinJavaImports(content)
            Language.PYTHON -> removeUnusedPythonImports(content)
            Language.JAVASCRIPT, Language.TYPESCRIPT -> removeUnusedJsTsImports(content)
            else -> null
        }
    }


    /**
     * Remove unused code: detect unused top-level functions and variables
     * (not just imports). Works via simple reference counting — no AST needed.
     * Returns the modified file content, or null if no changes were made.
     */
    fun removeUnusedCode(content: String, language: Language): String? {
        val lines = content.lines()
        val result = lines.toMutableList()

        // Find top-level function/variable declarations
        data class Decl(val lineNum: Int, val name: String, val startLine: Int, val endLine: Int)
        val decls = mutableListOf<Decl>()

        val fnRegex = when (language) {
            Language.KOTLIN -> Regex("""^\s*(?:fun|private fun|public fun|internal fun|protected fun)\s+(\w+)""")
            Language.JAVA -> Regex("""^\s*(?:public|private|protected|static)\s+[\w<>\[\],\s]+\s+(\w+)\s*\(""")
            Language.PYTHON -> Regex("""^\s*def\s+(\w+)""")
            Language.JAVASCRIPT, Language.TYPESCRIPT -> Regex("""^\s*(?:function|const|let|var)\s+(\w+)""")
            else -> null
        }

        if (fnRegex == null) return null

        for (i in lines.indices) {
            val match = fnRegex.find(lines[i])
            if (match != null) {
                val name = match.groupValues[1]
                // Skip main, constructor, init, test functions, override
                if (name in setOf("main", "init", "constructor", "toString", "hashCode", "equals")
                    || name.startsWith("test") || name.startsWith("Test")
                    || "override" in lines[i] || "@Test" in lines.getOrNull(i - 1)?.trim() ?: ""
                ) continue

                // Find the end of the declaration (simple heuristic)
                val endLine = when (language) {
                    Language.PYTHON -> findPythonBlockEnd(lines, i)
                    Language.KOTLIN, Language.JAVA -> findBraceBlockEnd(lines, i)
                    Language.JAVASCRIPT, Language.TYPESCRIPT -> findBraceBlockEnd(lines, i)
                    else -> i
                }
                decls.add(Decl(i, name, i, endLine))
            }
        }

        if (decls.isEmpty()) return null

        // Check which declarations are referenced elsewhere
        val linesToRemove = mutableSetOf<Int>()
        for (decl in decls) {
            // Search for the name in lines outside the declaration's own block
            val pattern = Regex("""\b${Regex.escape(decl.name)}\b""")
            var found = false
            for (i in lines.indices) {
                if (i in decl.startLine..decl.endLine) continue
                if (pattern.containsMatchIn(lines[i])) {
                    found = true
                    break
                }
            }
            if (!found) {
                (decl.startLine..decl.endLine).forEach { linesToRemove.add(it) }
            }
        }

        if (linesToRemove.isEmpty()) return null

        for (lineNum in linesToRemove) {
            result[lineNum] = ""
        }

        return result.joinToString("\n")
    }

    private fun findPythonBlockEnd(lines: List<String>, startLine: Int): Int {
        val startIndent = lines[startLine].takeWhile { it == ' ' }.length
        var end = startLine
        for (i in (startLine + 1) until lines.size) {
            val line = lines[i]
            if (line.trim().isEmpty()) continue
            val indent = line.takeWhile { it == ' ' }.length
            if (indent <= startIndent && line.trim().isNotEmpty()) {
                return i - 1
            }
            end = i
        }
        return end
    }

    private fun findBraceBlockEnd(lines: List<String>, startLine: Int): Int {
        var depth = 0
        var foundOpen = false
        for (i in startLine until lines.size) {
            for (ch in lines[i]) {
                when (ch) {
                    '{' -> { depth++; foundOpen = true }
                    '}' -> { depth--; if (foundOpen && depth == 0) return i }
                }
            }
        }
        return startLine
    }

    // ── Kotlin / Java ──────────────────────────────────────────

    private fun organizeKotlinJavaImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.startsWith("import ") || line.startsWith("import static ")
        }
        if (importRanges.isEmpty()) return null

        val allImports = mutableSetOf<String>()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                if (trimmed.startsWith("import ")) allImports.add(trimmed)
            }
        }

        if (allImports.isEmpty()) return null

        // Sort: group by top-level package, then alphabetical
        val sorted = allImports.sortedWith(compareBy(
            { it.removePrefix("import ").removePrefix("import static ").substringBefore(".") },
            { it }
        ))

        var result = lines.toMutableList()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx -> result[idx] = "" }
        }

        val insertIdx = importRanges.first().first
        for ((i, imp) in sorted.withIndex()) {
            result[insertIdx + i] = imp
        }

        val newContent = result.joinToString("\n")
        return if (newContent != content) newContent else null
    }

    private fun removeUnusedKotlinJavaImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.startsWith("import ") || line.startsWith("import static ")
        }
        if (importRanges.isEmpty()) return null

        data class ImportLine(val lineNum: Int, val text: String, val symbol: String, val isWildcard: Boolean)
        val imports = mutableListOf<ImportLine>()

        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                if (trimmed.startsWith("import ")) {
                    val isWildcard = trimmed.endsWith(".*")
                    val cleaned = trimmed.removePrefix("import ").removePrefix("static ").removeSuffix(".*")
                    val symbol = cleaned.substringAfterLast(".")
                    imports.add(ImportLine(idx, trimmed, symbol, isWildcard))
                }
            }
        }

        if (imports.isEmpty()) return null

        val importLineNums = imports.map { it.lineNum }.toSet()
        val packageLine = lines.indexOfFirst { it.trim().startsWith("package ") }
        val body = lines.mapIndexedNotNull { idx, line ->
            if (idx in importLineNums || idx == packageLine) null else line
        }.joinToString("\n")

        val toRemove = mutableListOf<Int>()
        for (imp in imports) {
            if (imp.isWildcard) {
                val pkgLast = imp.text.removePrefix("import ").removePrefix("static ").removeSuffix(".*").substringAfterLast(".")
                if (pkgLast.isNotEmpty() && !body.contains(pkgLast)) {
                    toRemove.add(imp.lineNum)
                }
            } else {
                val pattern = Regex("""\b${Regex.escape(imp.symbol)}\b""")
                if (!pattern.containsMatchIn(body)) {
                    toRemove.add(imp.lineNum)
                }
            }
        }

        if (toRemove.isEmpty()) return null

        for (lineNum in toRemove) { lines[lineNum] = "" }
        return lines.joinToString("\n")
    }

    // ── Python ─────────────────────────────────────────────────

    private fun organizePythonImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.startsWith("import ") || line.startsWith("from ")
        }
        if (importRanges.isEmpty()) return null

        val allImports = mutableSetOf<String>()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                if (trimmed.startsWith("import ") || trimmed.startsWith("from ")) {
                    allImports.add(trimmed)
                }
            }
        }

        if (allImports.isEmpty()) return null

        val sorted = allImports.sorted()

        var result = lines.toMutableList()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx -> result[idx] = "" }
        }

        val insertIdx = importRanges.first().first
        for ((i, imp) in sorted.withIndex()) {
            result[insertIdx + i] = imp
        }

        val newContent = result.joinToString("\n")
        return if (newContent != content) newContent else null
    }

    private fun removeUnusedPythonImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.startsWith("import ") || line.startsWith("from ")
        }
        if (importRanges.isEmpty()) return null

        data class PyImport(val lineNum: Int, val names: List<String>)
        val imports = mutableListOf<PyImport>()

        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                if (trimmed.startsWith("import ")) {
                    val names = trimmed.removePrefix("import ")
                        .split(",").map { it.trim().split(" as ").last().trim() }
                    imports.add(PyImport(idx, names))
                } else if (trimmed.startsWith("from ")) {
                    val importPart = trimmed.substringAfter(" import ")
                    val names = importPart.replace("(", "").replace(")", "").replace(" ", "")
                        .split(",").map { it.trim().split(" as ").last().trim() }
                    imports.add(PyImport(idx, names))
                }
            }
        }

        if (imports.isEmpty()) return null

        val importLineNums = imports.map { it.lineNum }.toSet()
        val body = lines.mapIndexedNotNull { idx, line ->
            if (idx in importLineNums) null else line
        }.joinToString("\n")

        val toRemove = mutableListOf<Int>()
        for (imp in imports) {
            val allUnused = imp.names.all { name -> !body.contains(name) }
            if (allUnused) toRemove.add(imp.lineNum)
        }

        if (toRemove.isEmpty()) return null

        for (lineNum in toRemove) { lines[lineNum] = "" }
        return lines.joinToString("\n")
    }

    // ── JavaScript / TypeScript ─────────────────────────────────

    private fun organizeJsTsImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.contains("import ") || line.contains("require(")
        }
        if (importRanges.isEmpty()) return null

        val allImports = mutableSetOf<String>()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                if (trimmed.contains("import ") || trimmed.contains("require(")) {
                    allImports.add(trimmed)
                }
            }
        }

        if (allImports.isEmpty()) return null

        // Sort: npm packages first (no ./ or ../), then relative imports
        val sorted = allImports.sortedWith(compareBy(
            { if (it.contains("./") || it.contains("../")) 1 else 0 },
            { it }
        ))

        var result = lines.toMutableList()
        for ((start, end) in importRanges) {
            (start..end).forEach { idx -> result[idx] = "" }
        }

        val insertIdx = importRanges.first().first
        for ((i, imp) in sorted.withIndex()) {
            result[insertIdx + i] = imp
        }

        val newContent = result.joinToString("\n")
        return if (newContent != content) newContent else null
    }

    private fun removeUnusedJsTsImports(content: String): String? {
        val lines = content.lines().toMutableList()
        val importRanges = findImportRanges(lines) { line ->
            line.contains("import ") || line.contains("require(")
        }
        if (importRanges.isEmpty()) return null

        data class JsImport(val lineNum: Int, val names: List<String>)
        val imports = mutableListOf<JsImport>()

        for ((start, end) in importRanges) {
            (start..end).forEach { idx ->
                val trimmed = lines[idx].trim()
                val namedImportRegex = Regex("""import\s*\{([^}]+)\}\s*from\s*['"]""")
                val defaultImportRegex = Regex("""import\s+(\w+)\s+from\s*['"]""")
                val requireRegex = Regex("""(?:const|let|var)\s+\{([^}]+)\}\s*=\s*require\(""")
                val requireDefaultRegex = Regex("""(?:const|let|var)\s+(\w+)\s*=\s*require\(""")

                namedImportRegex.find(trimmed)?.let { match ->
                    val names = match.groupValues[1].split(",").map {
                        it.trim().split(" as ").last().trim()
                    }.filter { it.isNotEmpty() }
                    imports.add(JsImport(idx, names))
                } ?: defaultImportRegex.find(trimmed)?.let { match ->
                    imports.add(JsImport(idx, listOf(match.groupValues[1])))
                } ?: requireRegex.find(trimmed)?.let { match ->
                    val names = match.groupValues[1].split(",").map {
                        it.trim().split(":").last().trim()
                    }.filter { it.isNotEmpty() }
                    imports.add(JsImport(idx, names))
                } ?: requireDefaultRegex.find(trimmed)?.let { match ->
                    imports.add(JsImport(idx, listOf(match.groupValues[1])))
                }
            }
        }

        if (imports.isEmpty()) return null

        val importLineNums = imports.map { it.lineNum }.toSet()
        val body = lines.mapIndexedNotNull { idx, line ->
            if (idx in importLineNums) null else line
        }.joinToString("\n")

        val toRemove = mutableListOf<Int>()
        for (imp in imports) {
            val allUnused = imp.names.all { name ->
                val pattern = Regex("""\b${Regex.escape(name)}\b""")
                !pattern.containsMatchIn(body)
            }
            if (allUnused) toRemove.add(imp.lineNum)
        }

        if (toRemove.isEmpty()) return null

        for (lineNum in toRemove) { lines[lineNum] = "" }
        return lines.joinToString("\n")
    }

    // ── Shared helpers ──────────────────────────────────────────

    private fun findImportRanges(
        lines: List<String>,
        isImport: (String) -> Boolean,
    ): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        var inBlock = false
        var blockStart = -1

        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (isImport(trimmed)) {
                if (!inBlock) { inBlock = true; blockStart = i }
            } else if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                if (inBlock && i > blockStart) {
                    val nextImport = (i + 1 until lines.size).firstOrNull { lines[it].trim().isNotEmpty() }
                        ?.let { isImport(lines[it].trim()) } ?: false
                    if (!nextImport) {
                        ranges.add(blockStart to i - 1)
                        inBlock = false
                    }
                }
            } else {
                if (inBlock) {
                    ranges.add(blockStart to i - 1)
                    inBlock = false
                }
            }
        }
        if (inBlock) ranges.add(blockStart to lines.lastIndex)
        return ranges
    }
}
