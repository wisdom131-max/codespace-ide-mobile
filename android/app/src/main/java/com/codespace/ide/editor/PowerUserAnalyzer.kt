package com.codespace.ide.editor

import java.io.File

/**
 * P41-P: Power User Features analyzer — TODO scanning, dead code detection,
 * duplicate code detection, and complexity metrics.
 *
 * All analysis is local (no LSP required). Designed for the current file
 * and optionally workspace-wide TODO scanning.
 */
object PowerUserAnalyzer {

    data class TodoItem(
        val file: String,
        val line: Int,
        val text: String,
        val tag: String,
    )

    data class DeadCodeItem(
        val name: String,
        val kind: String,
        val line: Int,
        val usages: Int,
    )

    data class DuplicateItem(
        val file: String,
        val lineStart: Int,
        val lineEnd: Int,
        val duplicateCount: Int,
        val preview: String,
    )

    data class ComplexityItem(
        val functionName: String,
        val line: Int,
        val complexity: Int,
        val risk: String,
    )

    private val todoRegex = Regex(
        """(TODO|FIXME|HACK|XXX|NOTE)\b[:\s]*(.*)""",
        RegexOption.IGNORE_CASE
    )

    fun scanTodosInFile(filePath: String, content: String): List<TodoItem> {
        val results = mutableListOf<TodoItem>()
        val fileName = filePath.substringAfterLast('/')
        content.lines().forEachIndexed { idx, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("*") ||
                trimmed.startsWith("/*") || trimmed.startsWith("<!--") ||
                trimmed.startsWith("--") || trimmed.startsWith(";")) {
                val match = todoRegex.find(trimmed)
                if (match != null) {
                    val tag = match.groupValues[1].uppercase()
                    val text = match.groupValues[2].trim()
                    if (text.isNotEmpty() && results.none { it.line == idx + 1 }) {
                        results.add(TodoItem(fileName, idx + 1, text, tag))
                    }
                }
            }
            val inlineComment = line.indexOf("//")
            if (inlineComment >= 0 && inlineComment < line.length - 2) {
                val commentPart = line.substring(inlineComment)
                val match = todoRegex.find(commentPart)
                if (match != null) {
                    val tag = match.groupValues[1].uppercase()
                    val text = match.groupValues[2].trim()
                    if (text.isNotEmpty() && results.none { it.line == idx + 1 }) {
                        results.add(TodoItem(fileName, idx + 1, text, tag))
                    }
                }
            }
        }
        return results
    }

    fun scanTodosInWorkspace(projectRoot: File, maxFiles: Int = 200): List<TodoItem> {
        val results = mutableListOf<TodoItem>()
        val sourceExtensions = setOf(
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp", "h", "hpp",
            "php", "sh", "rb", "swift", "m", "mm", "scala", "clj", "el", "lua", "r", "dart"
        )
        var count = 0
        projectRoot.walkTopDown().forEach { file ->
            if (count >= maxFiles) return results
            if (file.isFile && file.extension.lowercase() in sourceExtensions) {
                val path = file.absolutePath
                if (path.contains("/build/") || path.contains("/.gradle/") ||
                    path.contains("/node_modules/") || path.contains("/.git/") ||
                    path.contains("/gen/")) return@forEach
                try {
                    val content = file.readText()
                    val relPath = file.absolutePath.removePrefix(projectRoot.absolutePath).removePrefix("/")
                    results.addAll(scanTodosInFile(relPath, content))
                    count++
                } catch (_: Exception) {}
            }
        }
        return results
    }

    private val funcDefRegex = Regex(
        """\b(fun|def|function|func|void|int|boolean|bool|float|double|String|val|var|let|const)\s+(\w+)\s*[\(\{<]"""
    )
    private val importRegex = Regex(
        """^\s*(import\s+[\w.]+|from\s+['"]\S+['"]\s+import|#include\s+[<"]\S+[>"])"""
    )

    fun detectDeadCode(content: String): List<DeadCodeItem> {
        val results = mutableListOf<DeadCodeItem>()
        val lines = content.lines()

        val functions = mutableMapOf<String, Int>()
        lines.forEachIndexed { idx, line ->
            val match = funcDefRegex.find(line)
            if (match != null) {
                val name = match.groupValues[2]
                if (name.isNotEmpty() && name !in setOf("if", "for", "while", "when", "switch", "return", "class", "object", "interface", "enum")) {
                    functions[name] = idx + 1
                }
            }
        }

        for ((name, defLine) in functions) {
            val usages = lines.withIndex().count { (idx, line) ->
                idx + 1 != defLine && Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(line)
            }
            if (usages == 0) {
                results.add(DeadCodeItem(name, "function", defLine, 0))
            }
        }

        lines.forEachIndexed { idx, line ->
            val importMatch = importRegex.find(line)
            if (importMatch != null) {
                val importText = importMatch.value
                val name = importText.substringAfterLast('.').substringAfterLast('/').trim()
                    .removeSuffix("'").removeSuffix("\"").removeSuffix(">").removeSuffix(";").trim()
                if (name.isNotEmpty() && name != "*") {
                    val usages = lines.withIndex().count { (i, l) ->
                        i != idx && Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(l)
                    }
                    if (usages == 0) {
                        results.add(DeadCodeItem(name, "import", idx + 1, 0))
                    }
                }
            }
        }

        return results
    }

    fun detectDuplicateCode(content: String, minBlockLength: Int = 5): List<DuplicateItem> {
        val lines = content.lines()
        val results = mutableListOf<DuplicateItem>()
        val seen = mutableMapOf<String, MutableList<Int>>()

        val normalized = lines.map { line ->
            line.trim()
                .replace(Regex("//.*$"), "")
                .replace(Regex("/\\*.*?\\*/"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        for (i in lines.indices) {
            if (i + minBlockLength > lines.size) break
            if (normalized[i].isEmpty() || normalized[i].length < 3) continue
            val block = (i until i + minBlockLength).joinToString("\n") { normalized[it] }
            val hash = block.hashCode().toString()
            seen.getOrPut(hash) { mutableListOf() }.add(i)
        }

        for ((_, lineStarts) in seen) {
            if (lineStarts.size >= 2) {
                val first = lineStarts.first()
                val preview = lines.getOrElse(first) { "" }
                results.add(DuplicateItem(
                    file = "",
                    lineStart = first + 1,
                    lineEnd = first + minBlockLength,
                    duplicateCount = lineStarts.size,
                    preview = preview.take(80)
                ))
            }
        }

        return results.sortedBy { it.lineStart }
    }

    private val decisionKeywords = Regex(
        """\b(if|else\s+if|for|while|when|switch|case|catch|try)\b|&&|\|\||\?\s"""
    )

    private val functionStartRegex = Regex(
        """^\s*(fun\s+\w+|def\s+\w+|function\s+\w+|func\s+\w+|\w+\s*\([^)]*\)\s*\{)"""
    )

    fun calculateComplexity(content: String): List<ComplexityItem> {
        val results = mutableListOf<ComplexityItem>()
        val lines = content.lines()
        var funcName = ""
        var funcStartLine = 0
        var complexity = 1
        var braceDepth = 0
        var inFunction = false

        for (i in lines.indices) {
            val line = lines[i]
            val funcMatch = functionStartRegex.find(line)
            if (funcMatch != null && !inFunction) {
                val matchText = funcMatch.value
                funcName = Regex("""\b(\w+)\s*\(""").find(matchText)?.groupValues?.get(1) ?: "anonymous"
                funcStartLine = i + 1
                complexity = 1
                braceDepth = line.count { it == '{' } - line.count { it == '}' }
                inFunction = true
                continue
            }

            if (inFunction) {
                complexity += decisionKeywords.findAll(line).count()
                braceDepth += line.count { it == '{' } - line.count { it == '}' }

                if (braceDepth <= 0) {
                    val risk = when {
                        complexity <= 5 -> "Low"
                        complexity <= 10 -> "Medium"
                        complexity <= 20 -> "High"
                        else -> "Critical"
                    }
                    results.add(ComplexityItem(funcName, funcStartLine, complexity, risk))
                    inFunction = false
                }
            }
        }

        if (inFunction) {
            val risk = when {
                complexity <= 5 -> "Low"
                complexity <= 10 -> "Medium"
                complexity <= 20 -> "High"
                else -> "Critical"
            }
            results.add(ComplexityItem(funcName, funcStartLine, complexity, risk))
        }

        return results
    }
}
