package com.codespace.ide.editor

import android.util.Log
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
                sb.append("\",\"kind\":\"${sym.kind}\",\"filePath\":\"")
                sb.append(sym.filePath.replace("\"", "\\\""))
                sb.append("\",\"line\":${sym.line},\"fileName\":\"${sym.fileName}\"}")
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

    /** Caps: measured safety nets, each one reported by name when it stops work. */
    private const val MAX_WATCHED_FILES = 8_000
    private const val MAX_OBSERVED_DIRS = 256
    private const val WATCH_BUDGET_MS = 1_500L
    private const val SWEEP_INTERVAL_MS = 30_000L
    private const val TAG = "FileIndexer"
    private val observers = mutableListOf<android.os.FileObserver>()
    private val dirtyDirs = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val fileTimestamps = mutableMapOf<String, Long>()

    /**
     * Start watching the workspace for external file changes.
     * When files are modified, added, or deleted outside the editor,
     * re-index only the changed files for efficient incremental updates.
     */
    /**
     * Watch the project for changes so the symbol index cannot go stale.
     *
     * FIX (2026-11-06, owner freeze on opening a project root): this function used to run
     * `root.walkTopDown()` on the CALLER's thread for its first snapshot, and again on every
     * 5-second tick, with no ignore list, no node cap and no time cap. The caller is
     * `ProjectShellScreen.kt:1025-1037`, a `LaunchedEffect`, whose body runs on the composition
     * dispatcher - the main thread. A root holding `.git` and `node_modules` therefore walked
     * tens of thousands of files before the first frame.
     *
     * Now: every byte of file I/O happens inside this IO coroutine, the walk is bounded by
     * [MAX_WATCHED_FILES] and [WATCH_BUDGET_MS] with the same ignore rule the indexer uses
     * ([WatchScan.isIgnored], which also covers VS Code's files.watcherExclude defaults), the
     * 5-second full-tree walk is gone in favour of directory [FileObserver]s (capped at
     * [MAX_OBSERVED_DIRS]) plus a stat-only sweep of the FILES WE ALREADY KNOW about, and the
     * whole loop is suspended while the app is not resumed.
     */
    fun startFileWatcher(workspacePath: String, scope: CoroutineScope) {
        watcherJob?.cancel()
        closeWatchers()
        IndexWatchNotice.clear()
        watcherJob = scope.launch(Dispatchers.IO) {
            val root = File(workspacePath)
            if (!root.exists()) return@launch
            try {
                val scan = WatchScan.scan(
                    root = root,
                    maxFiles = MAX_WATCHED_FILES,
                    budgetMs = WATCH_BUDGET_MS,
                    accept = { it.extension.lowercase() in sourceExtensions },
                )
                synchronized(lock) {
                    fileTimestamps.clear()
                    fileTimestamps.putAll(scan.files)
                }
                // Name-only and measurable: the scan cost is always visible in Output, and a cap
                // that stopped work says so in words the owner can act on.
                Log.i(TAG, "[perf] watch scan " + scan.files.size + " files, " +
                    scan.dirsVisited + " dirs, " + scan.elapsedMs + "ms, capped=" + scan.capped)
                com.codespace.ide.diagnostics.AppOutputLog.log(
                    "[perf] watch scan: " + scan.files.size + " files, " +
                        scan.dirsVisited + " dirs, " + scan.elapsedMs + " ms" +
                        (if (scan.capped) " (cap reached)" else ""),
                    "perf",
                )
                if (scan.capped) {
                    val why = if (scan.elapsedMs >= WATCH_BUDGET_MS)
                        "the " + WATCH_BUDGET_MS + " ms budget" else "the " + MAX_WATCHED_FILES + " file cap"
                    IndexWatchNotice.show(
                        "Symbols cover the first " + scan.files.size + " files, stopping at " + why +
                            ". Deeper folders are still browsable and editable; only symbol indexing and " +
                            "live change tracking stop at that depth.",
                    )
                    Log.i(TAG, "[watch] capped: " + scan.files.size + " files, " +
                        scan.dirsVisited + " dirs, " + scan.elapsedMs + "ms; deeper paths are not watched")
                }
                installDirectoryWatchers(root)

                while (isActive) {
                    delay(SWEEP_INTERVAL_MS)
                    if (!isActive) break
                    // Suspended while backgrounded: the index can wait until the app is visible.
                    if (!com.codespace.ide.AppForeground.resumed) continue
                    drainDirtyDirs()
                    sweepKnownFiles()
                }
            } finally {
                closeWatchers()
            }
        }
    }

    /** A FileObserver per directory, capped: past [MAX_OBSERVED_DIRS] the sweep carries the load. */
    private fun installDirectoryWatchers(root: File) {
        val budget = 400L
        val started = System.currentTimeMillis()
        val dirs = ArrayDeque<File>()
        dirs.add(root)
        var count = 0
        while (dirs.isNotEmpty() && count < MAX_OBSERVED_DIRS && System.currentTimeMillis() - started < budget) {
            val dir = dirs.removeFirst()
            val observer = try {
                object : android.os.FileObserver(dir.absolutePath) {
                    override fun onEvent(event: Int, name: String?) {
                        // Structural change only; content edits are the sweep's job.
                        dirtyDirs.add(dir.absolutePath)
                    }
                }.also { it.startWatching() }
            } catch (_: Throwable) {
                null
            }
            if (observer != null) synchronized(lock) { observers.add(observer); count++ }
            dir.listFiles()?.filter { it.isDirectory && !WatchScan.isIgnored(it) }?.forEach { dirs.add(it) }
        }
        // The observer cap is the second limit that must not be silent: past it, changes in those
        // directories still get picked up, but by the 30 s sweep instead of instantly.
        if (dirs.isNotEmpty()) {
            Log.i(TAG, "[watch] observer cap: " + count + " dirs watched, " +
                dirs.size + "+ not observed; the sweep covers them")
            com.codespace.ide.diagnostics.AppOutputLog.log(
                "[watch] observer cap: " + count + " dirs watched instantly, " +
                    dirs.size + "+ covered by the 30 s sweep",
                "perf",
            )
            IndexWatchNotice.show(
                "Live change tracking covers the first " + count +
                    " folders; the rest update within about 30 seconds. Opening, editing and " +
                    "saving are unaffected.",
            )
        }
    }

    private fun closeWatchers() {
        synchronized(lock) {
            observers.forEach { try { it.stopWatching() } catch (_: Throwable) {} }
            observers.clear()
        }
        dirtyDirs.clear()
    }

    /** Re-scan only the directories an observer flagged: one directory listing each, no recursion. */
    private fun drainDirtyDirs() {
        while (true) {
            val path = dirtyDirs.poll() ?: return
            val dir = File(path)
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (child.isFile && child.extension.lowercase() in sourceExtensions) {
                    val known = synchronized(lock) { fileTimestamps[child.absolutePath] }
                    val mtime = child.lastModified()
                    if (known != mtime) reindexFile(child, mtime)
                }
            }
        }
    }

    /** Stat only the files already in the index. Never walks the tree, so cost is bounded by the cap. */
    private fun sweepKnownFiles() {
        val known = synchronized(lock) { fileTimestamps.keys.toList() }
        for (path in known) {
            val file = File(path)
            if (!file.exists()) {
                synchronized(lock) {
                    symbols.removeAll { it.filePath == path }
                    fileTimestamps.remove(path)
                }
                continue
            }
            val mtime = file.lastModified()
            val prev = synchronized(lock) { fileTimestamps[path] }
            if (prev != mtime) reindexFile(file, mtime)
        }
    }

    private fun reindexFile(file: File, mtime: Long) {
        try {
            synchronized(lock) {
                symbols.removeAll { it.filePath == file.absolutePath }
                fileTimestamps[file.absolutePath] = mtime
            }
            indexFile(file)
        } catch (_: Exception) {
        }
    }

    /** Stop the file watcher. */
    fun stopFileWatcher() {
        watcherJob?.cancel()
        watcherJob = null
    }
}
