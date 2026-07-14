package com.codespace.ide.editor

import java.io.File
import kotlinx.coroutines.*

/**
 * P9-1: Background file indexer — builds a symbol index for workspace-wide
 * symbol search (VS Code "Go to Symbol in Workspace" / Ctrl+T).
 *
 * Walks the project tree in a background coroutine, extracts symbols using
 * lightweight regex per file type, and maintains an in-memory index.
 */
object FileIndexer {

    data class IndexedSymbol(
        val name: String,
        val kind: String,       // "class", "function", "variable", "interface", "enum"
        val filePath: String,
        val line: Int,           // 1-based
        val fileName: String,
    )

    data class IndexState(
        val totalFiles: Int = 0,
        val indexedFiles: Int = 0,
        val totalSymbols: Int = 0,
        val isIndexing: Boolean = false,
        val isComplete: Boolean = false,
    )

    private val symbols = mutableListOf<IndexedSymbol>()
    private var state = IndexState()
    private val lock = Any()
    private var indexJob: Job? = null

    // Source file extensions to index
    private val sourceExtensions = setOf(
        "kt", "java", "py", "js", "ts", "tsx", "jsx", "go", "rs", "c", "cpp", "h", "php", "sh"
    )

    // Symbol patterns per language family
    private val classPatterns = listOf(
        Regex("""^\s*(?:public\s+|private\s+|protected\s+|open\s+|abstract\s+|sealed\s+|data\s+|final\s+)*(?:class|object|interface|struct|enum\s+class)\s+(\w+)"""),
        Regex("""^\s*type\s+(\w+)\s+(?:struct|interface)"""),
        Regex("""^\s*(?:export\s+)?(?:class|interface|enum)\s+(\w+)"""),
        Regex("""^\s*class\s+(\w+)"""),
        Regex("""^\s*(?:pub\s+)?(?:struct|enum|trait)\s+(\w+)"""),
    )

    private val functionPatterns = listOf(
        Regex("""^\s*(?:override\s+|open\s+|public\s+|private\s+|protected\s+|internal\s+|static\s+|suspend\s+|inline\s+|abstract\s+|final\s+)*fun\s+(\w+)\s*\("""),
        Regex("""^\s*(?:public|private|protected|static)\s+(?:\w+\s+)+(\w+)\s*\("""),  // Java
        Regex("""^\s*(?:export\s+)?(?:async\s+)?function\s+(\w+)\s*\("""),
        Regex("""^\s*(?:export\s+)?const\s+(\w+)\s*=\s*(?:async\s+)?\("""),
        Regex("""^\s*def\s+(\w+)\s*\("""),
        Regex("""^\s*func\s+(?:\(\w+\s+\*?\w+\)\s+)?(\w+)\s*\("""),
        Regex("""^\s*(?:pub\s+)?fn\s+(\w+)\s*\("""),
        Regex("""^\s*function\s+(\w+)\s*\("""),
    )

    private val variablePatterns = listOf(
        Regex("""^\s*(?:val|var|const\s+val)\s+(\w+)\s*[:=]"""),
        Regex("""^\s*(?:export\s+)?(?:const|let|var)\s+(\w+)\s*[:=]"""),
        Regex("""^\s*(\w+)\s*=\s*[^=]"""),  // Python
    )

    fun getState(): IndexState = synchronized(lock) { state }

    fun getSymbols(): List<IndexedSymbol> = synchronized(lock) { symbols.toList() }

    fun search(query: String): List<IndexedSymbol> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return synchronized(lock) {
            symbols.filter { it.name.lowercase().contains(q) }
                .sortedBy { it.name.lowercase().indexOf(q) }
                .take(100)
        }
    }

    /**
     * Start indexing the workspace in the background.
     * Cancels any previous indexing job.
     */
    fun startIndexing(workspacePath: String, scope: CoroutineScope) {
        indexJob?.cancel()
        synchronized(lock) {
            symbols.clear()
            state = IndexState(isIndexing = true)
        }
        indexJob = scope.launch(Dispatchers.IO) {
            val root = File(workspacePath)
            if (!root.exists()) {
                synchronized(lock) { state = state.copy(isIndexing = false) }
                return@launch
            }
            // Collect files first
            val files = mutableListOf<File>()
            collectSourceFiles(root, files, maxFiles = 800)
            synchronized(lock) {
                state = state.copy(totalFiles = files.size)
            }
            // Index each file
            for (file in files) {
                if (!isActive) break
                try {
                    indexFile(file)
                } catch (_: Exception) {}
                synchronized(lock) {
                    state = state.copy(indexedFiles = state.indexedFiles + 1)
                }
            }
            synchronized(lock) {
                state = state.copy(
                    isIndexing = false,
                    isComplete = true,
                    totalSymbols = symbols.size,
                )
            }
        }
    }

    private fun collectSourceFiles(dir: File, results: MutableList<File>, maxFiles: Int) {
        if (results.size >= maxFiles) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (results.size >= maxFiles) break
            if (child.isDirectory) {
                // Skip hidden dirs, build, node_modules, .git
                if (child.name.startsWith(".") || child.name in setOf("build", "node_modules", ".gradle", ".idea")) continue
                collectSourceFiles(child, results, maxFiles)
            } else if (child.extension.lowercase() in sourceExtensions) {
                results.add(child)
            }
        }
    }

    private fun indexFile(file: File) {
        val lines = try { file.readLines() } catch (_: Exception) { return }
        val fileName = file.name
        val absPath = file.absolutePath
        val indexed = mutableListOf<IndexedSymbol>()

        lines.forEachIndexed { idx, line ->
            // Try class patterns
            for (pattern in classPatterns) {
                val match = pattern.find(line)
                if (match != null && match.groupValues.size > 1) {
                    val name = match.groupValues[1]
                    if (name.isNotBlank()) {
                        indexed.add(IndexedSymbol(name, "class", absPath, idx + 1, fileName))
                        break
                    }
                }
            }
            // Try function patterns
            for (pattern in functionPatterns) {
                val match = pattern.find(line)
                if (match != null && match.groupValues.size > 1) {
                    val name = match.groupValues[1]
                    if (name.isNotBlank()) {
                        indexed.add(IndexedSymbol(name, "function", absPath, idx + 1, fileName))
                        break
                    }
                }
            }
            // Try variable patterns (only top-level — indent <= 4)
            if (line.takeWhile { it == ' ' }.length <= 4) {
                for (pattern in variablePatterns) {
                    val match = pattern.find(line)
                    if (match != null && match.groupValues.size > 1) {
                        val name = match.groupValues[1]
                        if (name.isNotBlank() && name != "_" && name.length > 1) {
                            indexed.add(IndexedSymbol(name, "variable", absPath, idx + 1, fileName))
                            break
                        }
                    }
                }
            }
        }

        if (indexed.isNotEmpty()) {
            synchronized(lock) {
                symbols.addAll(indexed)
            }
        }
    }

    /** Cancel any running indexing job. */
    fun stopIndexing() {
        indexJob?.cancel()
        synchronized(lock) {
            state = state.copy(isIndexing = false)
        }
    }
}
