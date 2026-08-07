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
    private var fileIndexerScope: CoroutineScope? = null

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
        fileIndexerScope = scope
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

    // ── P41-Q: Persistent symbol cache (cross-session) ──────────────────────

    private var cacheFile: File? = null

    /**
     * Save the current symbol index to a JSON cache file for cross-session persistence.
     * Call after indexing completes to avoid re-scanning on next app launch.
     */
    fun saveCache(cacheDir: File) {
        try {
            val dir = File(cacheDir, "lsp-cache").apply { mkdirs() }
            val file = File(dir, "symbol-index.json")
            cacheFile = file
            val sb = StringBuilder()
            sb.append("[")
            val snapshot = synchronized(lock) { symbols.toList() }
            snapshot.forEachIndexed { idx, sym ->
                if (idx > 0) sb.append(",")
                sb.append("{\"name\":\"")
                sb.append(sym.name.replace("\"\\", "\\\\").replace("\"", "\\\""))
                sb.append("\",\"kind\":\"${'$'}{sym.kind}\",\"filePath\":\"")
                sb.append(sym.filePath.replace("\"", "\\\""))
                sb.append("\",\"line\":${'$'}{sym.line},\"fileName\":\"${'$'}{sym.fileName}\"}")
            }
            sb.append("]")
            file.writeText(sb.toString())
        } catch (_: Exception) {}
    }

    /**
     * Load a previously saved symbol cache. Call before startIndexing to
     * provide instant results while a fresh index builds in the background.
     */
    fun loadCache(cacheDir: File): Boolean {
        try {
            val file = File(File(cacheDir, "lsp-cache"), "symbol-index.json")
            cacheFile = file
            if (!file.exists()) return false
            val text = file.readText()
            val arr = org.json.JSONArray(text)
            synchronized(lock) {
                symbols.clear()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    symbols.add(IndexedSymbol(
                        name = obj.getString("name"),
                        kind = obj.getString("kind"),
                        filePath = obj.getString("filePath"),
                        line = obj.getInt("line"),
                        fileName = obj.getString("fileName"),
                    ))
                }
                state = state.copy(
                    isComplete = true,
                    totalSymbols = symbols.size,
                    totalFiles = symbols.map { it.filePath }.distinct().size,
                    indexedFiles = symbols.map { it.filePath }.distinct().size,
                )
            }
            return true
        } catch (_: Exception) { return false }
    }

    // ── P41-Q: File watcher integration ─────────────────────────────────────

    private var watcherJob: Job? = null
    private val fileTimestamps = mutableMapOf<String, Long>()

    /**
     * Start watching the workspace for external file changes.
     * When files are modified, added, or deleted outside the editor,
     * re-index only the changed files for efficient incremental updates.
     */
    fun startFileWatcher(workspacePath: String, scope: CoroutineScope) {
        watcherJob?.cancel()
        val root = File(workspacePath)
        if (!root.exists()) return

        // Snapshot initial file timestamps
        root.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() in sourceExtensions) {
                fileTimestamps[file.absolutePath] = file.lastModified()
            }
        }

        watcherJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000) // Check every 5 seconds
                if (!isActive) break

                val changed = mutableListOf<File>()
                val seen = mutableSetOf<String>()

                root.walkTopDown().forEach { file ->
                    if (file.isFile && file.extension.lowercase() in sourceExtensions) {
                        val path = file.absolutePath
                        val mtime = file.lastModified()
                        seen.add(path)
                        val prev = fileTimestamps[path]
                        if (prev == null || prev != mtime) {
                            changed.add(file)
                            fileTimestamps[path] = mtime
                        }
                    }
                }

                // Remove deleted files from index
                val deleted = fileTimestamps.keys.filter { it !in seen }
                if (deleted.isNotEmpty()) {
                    synchronized(lock) {
                        symbols.removeAll { it.filePath in deleted }
                        deleted.forEach { fileTimestamps.remove(it) }
                    }
                }

                // Re-index changed files
                if (changed.isNotEmpty()) {
                    for (file in changed) {
                        if (!isActive) break
                        try {
                            // Remove old symbols for this file
                            synchronized(lock) {
                                symbols.removeAll { it.filePath == file.absolutePath }
                            }
                            indexFile(file)
                        } catch (_: Exception) {}
                    }
                    // Save updated cache
                    cacheFile?.let { /* cache is saved by caller via saveCache() */ }
                }
            }
        }
    }

    /** Stop the file watcher. */
    fun stopFileWatcher() {
        watcherJob?.cancel()
        watcherJob = null
    }
}
