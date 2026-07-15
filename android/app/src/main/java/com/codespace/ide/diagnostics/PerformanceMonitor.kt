package com.codespace.ide.diagnostics

import java.io.File
import kotlinx.coroutines.launch

/**
 * P9-3/P9-5: Memory pressure monitor + code metrics for the status bar.
 * Lightweight — reads /proc/meminfo and computes file stats on demand.
 */

object MemoryMonitor {

    data class MemInfo(val totalMb: Int, val availableMb: Int) {
        val usedMb: Int get() = (totalMb - availableMb).coerceAtLeast(0)
        val usagePercent: Int get() = if (totalMb > 0) (usedMb * 100 / totalMb) else 0
        val isLowRam: Boolean get() = availableMb < 100
    }

    fun getMemInfo(): MemInfo {
        return try {
            val meminfo = File("/proc/meminfo").readText()
            val total = extractKb(meminfo, "MemTotal:")
            val avail = extractKb(meminfo, "MemAvailable:")
            MemInfo(totalMb = total / 1024, availableMb = avail / 1024)
        } catch (_: Exception) {
            // Fallback: use Runtime
            val rt = Runtime.getRuntime()
            val used = ((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).toInt()
            val max = (rt.maxMemory() / (1024 * 1024)).toInt()
            MemInfo(totalMb = max, availableMb = (max - used).coerceAtLeast(0))
        }
    }

    private fun extractKb(meminfo: String, key: String): Int {
        val line = meminfo.lines().firstOrNull { it.startsWith(key) } ?: return 0
        return line.filter { it.isDigit() }.trim().toIntOrNull() ?: 0
    }
}

object CodeMetrics {

    data class FileStats(
        val lineCount: Int,
        val sizeBytes: Int,
        val sizeLabel: String,
        val functionCount: Int,
        val maxNestingDepth: Int,
    )

    private val functionPatterns = listOf(
        Regex("""\b(fun|def|function|func|fn)\s+\w+\s*\("""),
        Regex("""\b(class|object|interface|struct|enum)\s+\w+"""),
    )

    private val openerChars = setOf('{', '[', '(')
    private val closerChars = mapOf('}' to '{', ']' to '[', ')' to '(')

    fun analyze(content: String): FileStats {
        val lines = content.split("\n")
        val lineCount = lines.size
        val sizeBytes = content.toByteArray().size

        var functionCount = 0
        var maxDepth = 0
        var currentDepth = 0

        lines.forEach { line ->
            // Count functions
            for (pattern in functionPatterns) {
                if (pattern.containsMatchIn(line)) {
                    functionCount++
                    break
                }
            }
            // Track nesting depth (rough — ignores strings/comments for speed)
            for (c in line) {
                if (c in openerChars) {
                    currentDepth++
                    if (currentDepth > maxDepth) maxDepth = currentDepth
                } else if (closerChars.containsKey(c)) {
                    if (currentDepth > 0) currentDepth--
                }
            }
        }

        return FileStats(
            lineCount = lineCount,
            sizeBytes = sizeBytes,
            sizeLabel = formatSize(sizeBytes),
            functionCount = functionCount,
            maxNestingDepth = maxDepth,
        )
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> String.format("%.1fMB", bytes / (1024.0 * 1024.0))
    }
}

// ── P16-D: Sync Status Monitor ───────────────────────────────────────────────

sealed class SyncState {
    object Idle : SyncState()
    data class Syncing(val label: String) : SyncState()
    data class Success(val label: String) : SyncState()
    data class Error(val msg: String) : SyncState()
}

object SyncStatusMonitor {

    private val _syncState = kotlinx.coroutines.flow.MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: kotlinx.coroutines.flow.StateFlow<SyncState> = _syncState

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()
    )

    fun setSyncing(label: String) { _syncState.value = SyncState.Syncing(label) }

    fun setSuccess(label: String) {
        _syncState.value = SyncState.Success(label)
        scope.launch {
            kotlinx.coroutines.delay(3_000)
            if (_syncState.value is SyncState.Success) _syncState.value = SyncState.Idle
        }
    }

    fun setError(msg: String) {
        _syncState.value = SyncState.Error(msg)
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            if (_syncState.value is SyncState.Error) _syncState.value = SyncState.Idle
        }
    }

    fun setIdle() { _syncState.value = SyncState.Idle }

    private fun launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
        scope.launch(block = block)
}
