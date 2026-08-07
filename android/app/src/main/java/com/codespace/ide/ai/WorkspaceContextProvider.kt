package com.codespace.ide.ai

import java.io.File

/**
 * P41-X: Workspace-aware AI context provider.
 *
 * Builds a concise workspace context string that can be injected into AI system prompts,
 * giving the model awareness of the project structure, current file, language, and
 * related files/imports — without sending excessive token data.
 *
 * VS Code parity: matches the "workspace context" that Copilot Chat uses when
 * the @workspace tag is invoked — it includes project tree, open files, and
 * the current file's imports/dependencies.
 */
object WorkspaceContextProvider {

    /** Maximum depth for the project tree listing. */
    private const val MAX_DEPTH = 3

    /** Maximum number of files to list in the tree. */
    private const val MAX_FILES = 80

    /** Maximum characters for the generated context. */
    private const val MAX_CONTEXT_CHARS = 4000

    /** Directories to skip when building the tree. */
    private val SKIP_DIRS = setOf(
        ".git", "node_modules", ".gradle", "build", ".idea", ".vscode",
        "__pycache__", ".venv", "venv", "dist", ".next", ".nuxt",
        "target", "bin", "obj", ".cache", ".expo", ".dart_tool",
        ".terraform", "coverage", ".nyc_output", "tmp", "temp",
    )

    /**
     * Build a workspace context string for AI prompts.
     *
     * @param projectRootPath  Absolute path to the project root
     * @param currentFilePath  Absolute path to the currently open file (or null)
     * @param openFilePaths    List of currently open file paths
     * @return A concise context string, or empty string if no project
     */
    fun buildContext(
        projectRootPath: String?,
        currentFilePath: String? = null,
        openFilePaths: List<String> = emptyList(),
    ): String {
        if (projectRootPath == null) return ""
        val root = File(projectRootPath)
        if (!root.exists() || !root.isDirectory) return ""

        val sb = StringBuilder()
        sb.appendLine("## WORKSPACE CONTEXT")
        sb.appendLine()

        // 1. Project name and type
        val projectName = root.name
        val projectType = detectProjectType(root)
        sb.appendLine("Project: $projectName")
        if (projectType != null) {
            sb.appendLine("Type: $projectType")
        }
        sb.appendLine()

        // 2. Current file info
        if (currentFilePath != null) {
            val currentFile = File(currentFilePath)
            if (currentFile.exists()) {
                val relPath = relativePath(root, currentFile)
                sb.appendLine("Current file: $relPath")
                val lang = detectLanguage(currentFile.name)
                if (lang != null) {
                    sb.appendLine("Language: $lang")
                }
                // Extract imports from current file
                val imports = extractImports(currentFile)
                if (imports.isNotEmpty()) {
                    sb.appendLine("Imports/dependencies:")
                    imports.take(15).forEach { sb.appendLine("  - $it") }
                    if (imports.size > 15) sb.appendLine("  ... (${imports.size} total)")
                }
                sb.appendLine()
            }
        }

        // 3. Open files
        if (openFilePaths.isNotEmpty()) {
            val openRels = openFilePaths
                .filter { File(it).exists() }
                .map { relativePath(root, File(it)) }
                .filter { it.isNotBlank() }
            if (openRels.isNotEmpty()) {
                sb.appendLine("Open files: ${openRels.joinToString(", ")}")
                sb.appendLine()
            }
        }

        // 4. Project structure tree (compact)
        val tree = buildTree(root, depth = 0, maxDepth = MAX_DEPTH, fileCount = intArrayOf(0))
        if (tree.isNotEmpty()) {
            sb.appendLine("Project structure:")
            sb.appendLine(tree)
        }

        // Truncate if too long
        val result = sb.toString()
        return if (result.length > MAX_CONTEXT_CHARS) {
            result.substring(0, MAX_CONTEXT_CHARS) + "\n... (truncated)"
        } else {
            result
        }
    }

    /** Detect project type from marker files. */
    private fun detectProjectType(root: File): String? {
        return when {
            File(root, "package.json").exists() -> {
                val pkg = try { File(root, "package.json").readText() } catch (_: Exception) { "" }
                when {
                    pkg.contains("\"next\"") -> "Next.js (Node.js)"
                    pkg.contains("\"react\"") && pkg.contains("\"react-native\"") -> "React Native"
                    pkg.contains("\"react\"") -> "React (Node.js)"
                    pkg.contains("\"vue\"") -> "Vue.js (Node.js)"
                    pkg.contains("\"express\"") -> "Express (Node.js)"
                    else -> "Node.js"
                }
            }
            File(root, "build.gradle").exists() || File(root, "build.gradle.kts").exists() -> "Android/Gradle"
            File(root, "pom.xml").exists() -> "Java/Maven"
            File(root, "Cargo.toml").exists() -> "Rust"
            File(root, "go.mod").exists() -> "Go"
            File(root, "requirements.txt").exists() || File(root, "setup.py").exists() -> "Python"
            File(root, "pubspec.yaml").exists() -> "Flutter/Dart"
            File(root, "Gemfile").exists() -> "Ruby"
            File(root, "composer.json").exists() -> "PHP"
            File(root, "CMakeLists.txt").exists() -> "C/C++ (CMake)"
            File(root, "Makefile").exists() -> "C/C++ (Make)"
            else -> null
        }
    }

    /** Build a compact tree listing of the project. */
    private fun buildTree(dir: File, depth: Int, maxDepth: Int, fileCount: IntArray): String {
        if (depth > maxDepth || fileCount[0] >= MAX_FILES) return ""
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)

        val children = dir.listFiles()?.sortedBy { it.name } ?: return ""
        val dirs = children.filter { it.isDirectory && it.name !in SKIP_DIRS && !it.name.startsWith(".") }
        val files = children.filter { it.isFile && !it.name.startsWith(".") }

        for (d in dirs) {
            if (fileCount[0] >= MAX_FILES) break
            sb.appendLine("$indent${d.name}/")
            sb.append(buildTree(d, depth + 1, maxDepth, fileCount))
        }
        for (f in files) {
            if (fileCount[0] >= MAX_FILES) break
            fileCount[0]++
            sb.appendLine("$indent${f.name}")
        }

        return sb.toString()
    }

    /** Extract import/dependency statements from a source file. */
    private fun extractImports(file: File): List<String> {
        val content = try { file.readText() } catch (_: Exception) { return emptyList() }
        val ext = file.extension.lowercase()
        val imports = mutableListOf<String>()

        when (ext) {
            "kt", "java" -> {
                Regex("""^import\s+(.+)""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1].trim())
                }
            }
            "py" -> {
                Regex("""^(?:from\s+(\S+)\s+)?import\s+(.+)""", RegexOption.MULTILINE).findAll(content).forEach { m ->
                    val from = m.groupValues[1]
                    val what = m.groupValues[2].trim()
                    imports.add(if (from.isNotEmpty()) "$from.$what" else what)
                }
            }
            "js", "jsx", "ts", "tsx" -> {
                Regex("""^(?:import|const|let|var)\s+.*?require\s*\(\s*['"]([^'"]+)['"]""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
                Regex("""^import\s+.*?from\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
                Regex("""^import\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
            }
            "go" -> {
                Regex("""^import\s+"(.+)"""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
                Regex("""import\s*\(([\s\S]*?)\)""").findAll(content).forEach { block ->
                    Regex("""^\s*"(.+)"""", RegexOption.MULTILINE).findAll(block.groupValues[1]).forEach {
                        imports.add(it.groupValues[1])
                    }
                }
            }
            "rs" -> {
                Regex("""use\s+([^;]+);""").findAll(content).forEach {
                    imports.add(it.groupValues[1].trim())
                }
            }
            "c", "cpp", "h", "hpp" -> {
                Regex("""#include\s+[<"]([^>"]+)[>"]""").findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
            }
            "rb" -> {
                Regex("""^require\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
                Regex("""^require_relative\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
            }
            "php" -> {
                Regex("""use\s+([^;]+);""").findAll(content).forEach {
                    imports.add(it.groupValues[1].trim())
                }
                Regex("""require(?:_once)?\s+['"]([^'"]+)['"]""").findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
            }
            "sh", "bash" -> {
                Regex("""^source\s+(\S+)""", RegexOption.MULTILINE).findAll(content).forEach {
                    imports.add(it.groupValues[1])
                }
            }
        }

        return imports.distinct()
    }

    /** Detect language from file extension. */
    private fun detectLanguage(filename: String): String? {
        return when (filename.substringAfterLast('.', "").lowercase()) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "js" -> "JavaScript"
            "jsx" -> "JavaScript (JSX)"
            "ts" -> "TypeScript"
            "tsx" -> "TypeScript (TSX)"
            "go" -> "Go"
            "rs" -> "Rust"
            "c" -> "C"
            "cpp" -> "C++"
            "h" -> "C/C++ Header"
            "hpp" -> "C++ Header"
            "cs" -> "C#"
            "rb" -> "Ruby"
            "php" -> "PHP"
            "swift" -> "Swift"
            "dart" -> "Dart"
            "sh" -> "Shell"
            "bash" -> "Bash"
            "html" -> "HTML"
            "css" -> "CSS"
            "json" -> "JSON"
            "yaml", "yml" -> "YAML"
            "xml" -> "XML"
            "md" -> "Markdown"
            "sql" -> "SQL"
            "toml" -> "TOML"
            else -> null
        }
    }

    /** Compute a relative path from root to file. */
    private fun relativePath(root: File, file: File): String {
        val rootPath = root.absolutePath.removeSuffix("/")
        val filePath = file.absolutePath
        return if (filePath.startsWith(rootPath)) {
            filePath.removePrefix("$rootPath/").removePrefix(rootPath)
        } else {
            filePath
        }
    }
}
