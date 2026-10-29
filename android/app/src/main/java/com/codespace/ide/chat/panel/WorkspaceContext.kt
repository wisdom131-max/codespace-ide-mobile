package com.codespace.ide.chat.panel

import java.io.File

/**
 * WORKSPACE INTELLIGENCE (owner mission 2026-10-29, part 1).
 *
 * Rebuilt from the archived `ai/WorkspaceContextProvider.kt` (P41-X) with three
 * corrections the archive did not have:
 *
 *  1. HONESTY BY CONSTRUCTION. The block is a LISTING, not file contents, and it
 *     says so. The model is told to use read_file/search_files/list_files before
 *     claiming anything about a file. The archive's block was consumed by a panel
 *     with no tool wire at all, so a model could describe a tree it could not read.
 *  2. SECRETS ARE WITHHELD, not merely unlisted. A credential-shaped file never has
 *     its contents read (the collector passes null), and the block says "withheld"
 *     instead of silently omitting the line, which would look like an empty file.
 *  3. EMPTY IS NOT MISSING. currentFileText = "" means a real empty file;
 *     null means "not readable / not read". The block renders those differently.
 *
 * Everything here is pure Kotlin over java.io.File: no Android imports, so the JVM
 * unit tests exercise the real builder rather than a stand-in.
 */
internal data class WorkspaceSnapshot(
    val projectId: String,
    /** Host path of the ACTIVE root, or null when nothing resolved. */
    val activeRoot: String?,
    /** Every workspace root, host paths, active root first when it is known. */
    val roots: List<String>,
    /** Absolute host path of the file the editor has open, if any. */
    val currentFile: String?,
    /**
     * Contents of [currentFile]: "" is a genuinely empty file, null is
     * "unreadable, withheld, or not read". The distinction is rendered.
     */
    val currentFileText: String?,
    val currentFileWithheld: Boolean = false,
    /** Every open editor tab, absolute host paths. */
    val openFiles: List<String> = emptyList(),
    /** AutoInstructionsProvider output for the active root ("" when none). */
    val instructionsBlock: String = "",
)

internal object WorkspaceContext {

    const val MAX_CHARS = 4000
    const val MAX_TREE_DEPTH = 3
    const val MAX_TREE_FILES = 80
    const val MAX_IMPORTS = 15
    const val MAX_OPEN_FILES = 12

    /**
     * Directories that are never part of the project story: VCS metadata, dependency
     * trees, build output and caches. Mirrors the archive list plus `.codespace`
     * deliberately NOT skipped (modes/skills/exports are user content) and `.git`
     * always skipped (thousands of entries, no value in a listing).
     */
    private val SKIP_DIRS = setOf(
        ".git", ".hg", ".svn", "node_modules", ".gradle", "build", ".idea", ".vscode",
        "__pycache__", ".venv", "venv", "dist", ".next", ".nuxt", "target", "bin", "obj",
        ".cache", ".expo", ".dart_tool", ".terraform", "coverage", ".nyc_output",
        "tmp", "temp", ".kotlin",
    )

    /** Files whose CONTENTS must never enter a prompt. Names only, checked lowercased. */
    private val SENSITIVE_NAMES = setOf(
        ".env", ".netrc", ".git-credentials", ".npmrc", ".pgpass", "credentials",
        "secrets", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
    )
    private val SENSITIVE_EXT = setOf(
        "pem", "key", "keystore", "jks", "pfx", "p12", "ppk", "asc", "gpg",
    )

    fun isSensitiveName(name: String): Boolean {
        val n = name.trim().lowercase()
        if (n.isEmpty()) return false
        if (n in SENSITIVE_NAMES) return true
        if (n.startsWith(".env")) return true            // .env, .env.local, .env.production
        if (n.startsWith("id_rsa") || n.startsWith("id_ed25519")) return true
        if (n.startsWith("secrets") || n.startsWith("credentials")) return true
        val ext = n.substringAfterLast('.', "")
        return ext.isNotEmpty() && ext in SENSITIVE_EXT
    }

    /** Project type from marker files, in the archive's precedence order. */
    fun detectProjectType(root: File?): String? {
        val dir = root ?: return null
        if (!dir.isDirectory) return null
        fun has(name: String) = File(dir, name).isFile
        return when {
            has("package.json") -> {
                val pkg = try { File(dir, "package.json").readText() } catch (_: Exception) { "" }
                when {
                    pkg.contains("\"next\"") -> "Next.js (Node.js)"
                    pkg.contains("\"react-native\"") -> "React Native"
                    pkg.contains("\"react\"") -> "React (Node.js)"
                    pkg.contains("\"vue\"") -> "Vue (Node.js)"
                    pkg.contains("\"express\"") -> "Express (Node.js)"
                    else -> "Node.js"
                }
            }
            has("build.gradle.kts") || has("build.gradle") -> "Android/Gradle"
            has("pom.xml") -> "Java/Maven"
            has("Cargo.toml") -> "Rust"
            has("go.mod") -> "Go"
            has("requirements.txt") || has("setup.py") || has("pyproject.toml") -> "Python"
            has("pubspec.yaml") -> "Flutter/Dart"
            has("Gemfile") -> "Ruby"
            has("composer.json") -> "PHP"
            has("CMakeLists.txt") -> "C/C++ (CMake)"
            has("Makefile") -> "C/C++ (Make)"
            else -> null
        }
    }

    /** Language label for the current-file line. Unknown extension returns null. */
    fun languageFor(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "Kotlin"
        "java" -> "Java"
        "py" -> "Python"
        "js", "mjs", "cjs" -> "JavaScript"
        "jsx" -> "JavaScript (React)"
        "ts" -> "TypeScript"
        "tsx" -> "TypeScript (React)"
        "go" -> "Go"
        "rs" -> "Rust"
        "rb" -> "Ruby"
        "php" -> "PHP"
        "c" -> "C"
        "h", "hpp", "cpp", "cc" -> "C/C++"
        "cs" -> "C#"
        "swift" -> "Swift"
        "sh", "bash" -> "Shell"
        "json" -> "JSON"
        "yml", "yaml" -> "YAML"
        "xml" -> "XML"
        "md" -> "Markdown"
        "html" -> "HTML"
        "css" -> "CSS"
        "sql" -> "SQL"
        else -> null
    }

    /**
     * Import-style dependency lines from file TEXT (pure, so it is testable without a
     * filesystem). Capped by [MAX_IMPORTS] at render time, which also reports the total.
     */
    fun importsOf(text: String, fileName: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val out = ArrayList<String>()
        fun scan(re: Regex) { re.findAll(text).forEach { out.add(it.groupValues[1].trim()) } }
        when (ext) {
            "kt", "kts", "java", "cs" -> scan(Regex("""^\s*import\s+(?:static\s+)?([\w.*]+)\s*;?""", RegexOption.MULTILINE))
            "py" -> {
                Regex("""^\s*from\s+(\S+)\s+import\s+""", RegexOption.MULTILINE).findAll(text)
                    .forEach { out.add(it.groupValues[1]) }
                Regex("""^\s*import\s+([\w.]+)""", RegexOption.MULTILINE).findAll(text)
                    .forEach { out.add(it.groupValues[1]) }
            }
            "js", "mjs", "cjs", "jsx", "ts", "tsx" -> {
                scan(Regex("""^\s*import\s+[^'"]*from\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE))
                scan(Regex("""^\s*import\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE))
                scan(Regex("""require\s*\(\s*['"]([^'"]+)['"]\s*\)"""))
            }
            "go" -> {
                scan(Regex("""^\s*import\s+(?:[\w.]+\s+)?"([^"]+)"""", RegexOption.MULTILINE))
                Regex("""import\s*\(([\s\S]*?)\)""").findAll(text).forEach { block ->
                    Regex("""^\s*(?:[\w.]+\s+)?"([^"]+)"""", RegexOption.MULTILINE)
                        .findAll(block.groupValues[1]).forEach { out.add(it.groupValues[1]) }
                }
            }
            "rs" -> scan(Regex("""^\s*use\s+([^;]+);""", RegexOption.MULTILINE))
            "c", "h", "cpp", "cc", "hpp" -> scan(Regex("""#include\s*[<"]([^>"]+)[>"]"""))
            "rb" -> scan(Regex("""^\s*require\s+['"]([^'"]+)['"]""", RegexOption.MULTILINE))
            "php" -> scan(Regex("""^\s*use\s+([\w\\]+)\s*;""", RegexOption.MULTILINE))
        }
        return out.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Compact project tree: directories before files, hidden and skip-listed folders
     * omitted, credential-shaped file names omitted (a listing should not advertise
     * them either). Bounded by depth and entry count so this can never become a dump.
     */
    fun buildTree(
        dir: File?,
        depth: Int = 0,
        maxDepth: Int = MAX_TREE_DEPTH,
        maxFiles: Int = MAX_TREE_FILES,
    ): String {
        if (dir == null || !dir.isDirectory) return ""
        val counter = intArrayOf(0)
        val sb = StringBuilder()
        walk(dir, 0, maxDepth, maxFiles, counter, sb)
        return sb.toString().trimEnd()
    }

    private fun walk(dir: File, depth: Int, maxDepth: Int, maxFiles: Int, counter: IntArray, sb: StringBuilder) {
        if (depth > maxDepth || counter[0] >= maxFiles) return
        val children = try { dir.listFiles()?.sortedBy { it.name.lowercase() } } catch (_: Exception) { null }
            ?: return
        val indent = "  ".repeat(depth)
        // Directories first: the shape of a project is more useful than its file order.
        for (d in children) {
            if (counter[0] >= maxFiles) return
            if (!d.isDirectory) continue
            if (d.name in SKIP_DIRS || d.name.startsWith(".")) continue
            sb.append(indent).append(d.name).append("/\n")
            walk(d, depth + 1, maxDepth, maxFiles, counter, sb)
        }
        for (f in children) {
            if (counter[0] >= maxFiles) return
            if (!f.isFile) continue
            if (f.name.startsWith(".") || isSensitiveName(f.name)) continue
            counter[0]++
            sb.append(indent).append(f.name).append('\n')
        }
    }

    /** Root-relative path when [target] sits under [root]; otherwise the absolute path. */
    fun relativeTo(root: String?, target: String?): String? {
        if (target.isNullOrBlank()) return null
        if (root.isNullOrBlank()) return target
        val r = root.trimEnd('/')
        val t = target.trimEnd('/')
        return if (t == r) "." else if (t.startsWith("$r/")) t.substring(r.length + 1) else t
    }

    /**
     * The system-prompt block. Empty string only when there is genuinely no workspace
     * AND no current file: callers append unconditionally, and a fabricated block is
     * worse than none.
     */
    fun build(snap: WorkspaceSnapshot, maxChars: Int = MAX_CHARS): String {
        val activeRoot = snap.activeRoot?.takeIf { it.isNotBlank() }
        val roots = snap.roots.filter { it.isNotBlank() }
        if (activeRoot == null && roots.isEmpty() && snap.currentFile == null) return ""

        val sb = StringBuilder()
        sb.append("## WORKSPACE CONTEXT\n")

        if (roots.isNotEmpty()) {
            sb.append("\nWorkspace roots (host paths):\n")
            for (r in roots.take(6)) {
                val marker = if (activeRoot != null && r.trimEnd('/') == activeRoot.trimEnd('/')) " [ACTIVE]" else ""
                sb.append("  - ").append(File(r).name).append(": ").append(r).append(marker).append('\n')
            }
        } else if (activeRoot != null) {
            sb.append("\nWorkspace root: ").append(activeRoot).append(" [ACTIVE]\n")
        } else {
            sb.append("\nWorkspace root: UNRESOLVED. Do not invent a path; ask the user or use an\n")
            sb.append("absolute path the user gave you.\n")
        }

        if (activeRoot != null) {
            val rootFile = File(activeRoot)
            sb.append("\nProject: ").append(rootFile.name).append('\n')
            detectProjectType(rootFile)?.let { sb.append("Type: ").append(it).append('\n') }
        }

        // Current file: relative path, language, imports. Contents are never inlined;
        // the model must read_file if it needs the body.
        val cfText = snap.currentFileText
        snap.currentFile?.let { cf ->
            val rel = relativeTo(activeRoot, cf) ?: cf
            sb.append("\nCurrent file: ").append(rel)
            when {
                snap.currentFileWithheld -> sb.append(" (contents withheld: credential-shaped file)\n")
                cfText == null -> sb.append(" (not read)\n")
                cfText.isEmpty() -> sb.append(" (empty file)\n")
                else -> {
                    sb.append('\n')
                    languageFor(File(cf).name)?.let { sb.append("Language: ").append(it).append('\n') }
                    val imports = importsOf(cfText, File(cf).name)
                    if (imports.isNotEmpty()) {
                        sb.append("Imports (").append(minOf(imports.size, MAX_IMPORTS)).append(" of ")
                            .append(imports.size).append("):\n")
                        imports.take(MAX_IMPORTS).forEach { sb.append("  - ").append(it).append('\n') }
                    }
                }
            }
            // Explicit Unit: the `when` is the last expression of this let, so without
            // it the compiler reads the branch as a value and demands an else on the
            // `if`. (CI run 40916125650 caught exactly that.)
            Unit
        }

        val openRels = snap.openFiles
            .filter { it.isNotBlank() }
            .mapNotNull { relativeTo(activeRoot, it) }
            .filter { it.isNotBlank() && it != "." }
            .distinct()
        if (openRels.size > 1) {
            sb.append("\nOpen files: ").append(openRels.take(MAX_OPEN_FILES).joinToString(", "))
            if (openRels.size > MAX_OPEN_FILES) sb.append(" (+").append(openRels.size - MAX_OPEN_FILES).append(" more)")
            sb.append('\n')
        }

        if (activeRoot != null) {
            val tree = buildTree(File(activeRoot))
            if (tree.isNotEmpty()) {
                sb.append("\nProject structure (depth ").append(MAX_TREE_DEPTH)
                    .append(", up to ").append(MAX_TREE_FILES).append(" entries; hidden, build and\n")
                sb.append("dependency folders omitted):\n").append(tree).append('\n')
            }
        }

        sb.append('\n')
        sb.append("This is a LISTING, not file contents. Call read_file before describing what a\n")
        sb.append("file contains, search_files to locate a symbol or string, and list_files to see\n")
        sb.append("inside a folder. Never claim to have read or changed a file unless a tool result\n")
        sb.append("says so. Relative paths resolve against the ACTIVE root.\n")

        val instructions = snap.instructionsBlock.trim()
        if (instructions.isNotEmpty()) {
            sb.append('\n').append(instructions).append('\n')
        }

        return fit(sb.toString().trimEnd(), maxChars)
    }

    /**
     * Character cap with an honest marker. One path used by the builder AND by the send
     * seam, so a block that was cut always says it was cut.
     */
    fun fit(block: String, maxChars: Int): String {
        if (maxChars <= 0 || block.length <= maxChars) return block
        return block.take(maxChars).trimEnd() + "\n[... workspace context truncated to fit the request]"
    }
}
