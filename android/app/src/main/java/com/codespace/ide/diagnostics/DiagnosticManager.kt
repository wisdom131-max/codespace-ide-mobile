package com.codespace.ide.diagnostics

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase P — Advanced Problems Panel: Central Diagnostic Manager.
 *
 * Single source of truth for ALL diagnostics in the app (LSP, build, compiler,
 * linter, static analyzer). Every diagnostic source publishes here; the Problems
 * panel, editor markers, and notifications all read from here.
 *
 * Thread-safe: background threads (LSP reader, build process) call ingest/update/clear;
 * UI reads from [diagnostics] (SnapshotStateList) on the main thread.
 *
 * Core guarantees:
 *   - NO STALE ERRORS — old diagnostics are replaced when a source publishes new ones
 *   - NO RANDOM DUPLICATES — deterministic dedup by (source, sourceId, uri, range, severity, code, message)
 *   - NO UI THREAD BLOCKING — mutations are Handler.post'd to main thread
 *   - NO LOST DIAGNOSTICS — source ownership prevents cross-source overwrites
 *   - NO CROSS-SOURCE OVERWRITES — each source owns its diagnostics
 *   - NO CRASH WHEN SOURCE FAILS — source failure marks diagnostics stale, doesn't destroy store
 */
object DiagnosticManager {

    // ── Diagnostic Source identity ───────────────────────────────────────

    enum class DiagnosticSource {
        LSP, COMPILER, BUILD, LINTER, STATIC_ANALYZER, TYPE_CHECKER, TEST, PROJECT, EXTENSION, OTHER
    }

    enum class Severity { ERROR, WARNING, INFO, HINT }

    enum class SourceHealth { READY, UNAVAILABLE, FAILED, STALE }

    /**
     * Normalized diagnostic model — the single representation used across the app.
     */
    data class Diagnostic(
        val id: String,                        // deterministic hash for dedup
        val source: DiagnosticSource,          // LSP, COMPILER, BUILD, etc.
        val sourceId: String,                  // "python-pyright", "gradle", "kotlin", "lintchecker"
        val uri: String,                       // file URI (e.g. "file:///path/to/file.kt")
        val filePath: String,                  // human-readable path
        val range: DiagnosticRange,            // start/end line+col (1-based for display)
        val severity: Severity,
        val message: String,
        val code: String? = null,
        val codeDescription: String? = null,
        val sourceName: String? = null,        // display name from LSP "source" field
        val relatedInformation: List<RelatedInfo> = emptyList(),
        val tags: List<String> = emptyList(),
        val quickFixes: List<QuickFix> = emptyList(),
        val timestamp: Long = System.currentTimeMillis(),
        val documentVersion: Int? = null,
        var isStale: Boolean = false,
    )

    data class DiagnosticRange(
        val startLine: Int,    // 1-based
        val startColumn: Int,   // 1-based
        val endLine: Int,       // 1-based
        val endColumn: Int,     // 1-based
    ) {
        companion object {
            val EMPTY = DiagnosticRange(1, 1, 1, 1)
        }
    }

    data class RelatedInfo(
        val message: String,
        val uri: String,
        val filePath: String,
        val line: Int,     // 1-based
        val column: Int,   // 1-based
    )

    data class QuickFix(
        val title: String,
        val kind: String? = null,
        val isPreferred: Boolean = false,
        val editJson: String? = null,
        val commandJson: String? = null,
    )

    /**
     * Tracks health of each diagnostic source.
     * Key = "source/sourceId" (e.g. "LSP/python-pyright")
     */
    private val sourceHealth = ConcurrentHashMap<String, SourceHealth>()

    // ── Central store ────────────────────────────────────────────────────

    private val _diagnostics: SnapshotStateList<Diagnostic> = mutableStateListOf()
    val diagnostics: SnapshotStateList<Diagnostic> get() = _diagnostics

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Ingestion ────────────────────────────────────────────────────────

    /**
     * Replace all diagnostics from a specific source+sourceId for a given URI.
     * Standard LSP pattern: server publishes COMPLETE set — old ones replaced.
     * Thread-safe.
     */
    fun publishDiagnostics(
        source: DiagnosticSource,
        sourceId: String,
        uri: String,
        filePath: String,
        newDiagnostics: List<Diagnostic>,
        documentVersion: Int? = null,
    ) {
        val sourceKey = "${source}/${sourceId}"

        mainHandler.post {
            // Remove old diagnostics from this source for this URI
            _diagnostics.removeAll { it.source == source && it.sourceId == sourceId && it.uri == uri }

            // Add new diagnostics (with dedup within this batch)
            val seen = mutableSetOf<String>()
            for (diag in newDiagnostics) {
                if (seen.add(diag.id)) {
                    _diagnostics.add(diag)
                }
            }

            sourceHealth[sourceKey] = SourceHealth.READY
        }
    }

    /**
     * Add a single diagnostic (for non-LSP sources like build, linter).
     * Does NOT clear existing — use publishDiagnostics for sources that send complete sets.
     */
    fun addDiagnostic(diagnostic: Diagnostic) {
        mainHandler.post {
            val exists = _diagnostics.any { it.id == diagnostic.id }
            if (!exists) {
                _diagnostics.add(diagnostic)
            }
            sourceHealth["${diagnostic.source}/${diagnostic.sourceId}"] = SourceHealth.READY
        }
    }

    /**
     * Add multiple diagnostics incrementally (dedup against existing).
     */
    fun addDiagnostics(diagnostics: List<Diagnostic>) {
        if (diagnostics.isEmpty()) return
        mainHandler.post {
            val existing = _diagnostics.map { it.id }.toMutableSet()
            for (diag in diagnostics) {
                if (existing.add(diag.id)) {
                    _diagnostics.add(diag)
                }
            }
            val first = diagnostics.first()
            sourceHealth["${first.source}/${first.sourceId}"] = SourceHealth.READY
        }
    }

    // ── Clearing ─────────────────────────────────────────────────────────

    /** Clear diagnostics from a specific source+sourceId for a given URI. */
    fun clearDiagnostics(source: DiagnosticSource, sourceId: String, uri: String) {
        mainHandler.post {
            _diagnostics.removeAll { it.source == source && it.sourceId == sourceId && it.uri == uri }
        }
    }

    /** Clear all diagnostics from a specific source+sourceId (all URIs). E.g. LSP crash. */
    fun clearSource(source: DiagnosticSource, sourceId: String) {
        val sourceKey = "${source}/${sourceId}"
        mainHandler.post {
            _diagnostics.removeAll { it.source == source && it.sourceId == sourceId }
            sourceHealth[sourceKey] = SourceHealth.UNAVAILABLE
        }
    }

    /** Mark all diagnostics from a source as stale (don't remove yet). */
    fun markSourceStale(source: DiagnosticSource, sourceId: String) {
        val sourceKey = "${source}/${sourceId}"
        mainHandler.post {
            _diagnostics.forEachIndexed { i, d ->
                if (d.source == source && d.sourceId == sourceId && !d.isStale) {
                    _diagnostics[i] = d.copy(isStale = true)
                }
            }
            sourceHealth[sourceKey] = SourceHealth.STALE
        }
    }

    /** Clear diagnostics for a specific file (all sources). */
    fun clearFile(uri: String) {
        mainHandler.post {
            _diagnostics.removeAll { it.uri == uri }
        }
    }

    /** Update URI/path for diagnostics when a file is renamed. */
    fun renameFile(oldUri: String, newUri: String, newFilePath: String) {
        mainHandler.post {
            _diagnostics.forEachIndexed { i, d ->
                if (d.uri == oldUri) {
                    _diagnostics[i] = d.copy(uri = newUri, filePath = newFilePath)
                }
            }
        }
    }

    /** Clear all diagnostics. */
    fun clearAll() {
        mainHandler.post {
            _diagnostics.clear()
            sourceHealth.clear()
        }
    }

    // ── Source Health ────────────────────────────────────────────────────

    fun setSourceHealth(source: DiagnosticSource, sourceId: String, health: SourceHealth) {
        sourceHealth["${source}/${sourceId}"] = health
    }

    fun getSourceHealth(source: DiagnosticSource, sourceId: String): SourceHealth {
        return sourceHealth["${source}/${sourceId}"] ?: SourceHealth.UNAVAILABLE
    }

    /** Mark a source as failed (e.g. LSP crashed and couldn't restart). */
    fun markSourceFailed(source: DiagnosticSource, sourceId: String) {
        val sourceKey = "${source}/${sourceId}"
        mainHandler.post {
            _diagnostics.forEachIndexed { i, d ->
                if (d.source == source && d.sourceId == sourceId && !d.isStale) {
                    _diagnostics[i] = d.copy(isStale = true)
                }
            }
            sourceHealth[sourceKey] = SourceHealth.FAILED
        }
    }

    // ── Queries (read-only, any thread) ───────────────────────────────────

    fun getDiagnosticsForFile(fileRef: String): List<Diagnostic> {
        return _diagnostics.filter { it.uri == fileRef || it.filePath == fileRef }
    }

    fun getDiagnosticsBySource(source: DiagnosticSource, sourceId: String): List<Diagnostic> {
        return _diagnostics.filter { it.source == source && it.sourceId == sourceId }
    }

    fun getAllDiagnostics(): List<Diagnostic> {
        return _diagnostics.toList()
    }

    fun countBySeverity(): DiagnosticCounts {
        var errors = 0; var warnings = 0; var info = 0; var hints = 0
        for (d in _diagnostics) {
            if (d.isStale) continue
            when (d.severity) {
                Severity.ERROR -> errors++
                Severity.WARNING -> warnings++
                Severity.INFO -> info++
                Severity.HINT -> hints++
            }
        }
        return DiagnosticCounts(errors, warnings, info, hints)
    }

    data class DiagnosticCounts(
        val errors: Int,
        val warnings: Int,
        val info: Int,
        val hints: Int,
    ) {
        val total: Int get() = errors + warnings + info + hints
    }

    // ── Dedup ID computation ─────────────────────────────────────────────

    /**
     * Deterministic ID for deduplication.
     * Identity: source, sourceId, uri, range, severity, code, normalized message.
     */
    fun computeId(
        source: DiagnosticSource,
        sourceId: String,
        uri: String,
        range: DiagnosticRange,
        severity: Severity,
        code: String?,
        message: String,
    ): String {
        val normalizedMessage = message.trim().lowercase()
        val codePart = code ?: ""
        return "$source/$sourceId|$uri|${range.startLine}:${range.startColumn}-${range.endLine}:${range.endColumn}|$severity|$codePart|$normalizedMessage"
    }
}
