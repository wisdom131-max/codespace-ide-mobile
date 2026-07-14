package com.codespace.ide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL

/**
 * Phase 12-D — Download Center
 *
 * Tracks all SDK / tool / package downloads initiated by the IDE.
 * Provides live progress via StateFlow and a persistent download history.
 */
object DownloadCenter {

    private const val TAG = "DownloadCenter"

    enum class DownloadState { QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

    data class DownloadEntry(
        val id: String,
        val name: String,
        val url: String,
        val destPath: String,
        val state: DownloadState = DownloadState.QUEUED,
        val totalBytes: Long = 0L,
        val downloadedBytes: Long = 0L,
        val errorMessage: String? = null,
        val startedAt: Long = System.currentTimeMillis(),
        val completedAt: Long? = null,
    ) {
        val progressFraction: Float
            get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

        val isActive: Boolean
            get() = state == DownloadState.QUEUED || state == DownloadState.DOWNLOADING
    }

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads.asStateFlow()

    val activeDownloads: List<DownloadEntry>
        get() = _downloads.value.filter { it.isActive }

    /**
     * Download a file with live progress updates.
     * @return true on success, false on failure
     */
    suspend fun download(
        context: Context,
        id: String,
        name: String,
        url: String,
        destFile: File,
    ): Boolean = withContext(Dispatchers.IO) {
        val entry = DownloadEntry(
            id = id, name = name, url = url, destPath = destFile.absolutePath,
            state = DownloadState.DOWNLOADING,
        )
        add(entry)

        try {
            destFile.parentFile?.mkdirs()
            val connection = URL(url).openConnection()
            connection.connect()
            val total = connection.contentLengthLong
            update(id) { it.copy(totalBytes = total) }

            connection.getInputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        update(id) { it.copy(downloadedBytes = downloaded) }
                    }
                }
            }

            update(id) {
                it.copy(
                    state = DownloadState.COMPLETED,
                    downloadedBytes = total.coerceAtLeast(it.downloadedBytes),
                    completedAt = System.currentTimeMillis(),
                )
            }
            true
        } catch (e: Exception) {
            update(id) {
                it.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "Unknown error")
            }
            destFile.delete()
            false
        }
    }

    /** Cancel an in-progress download by ID. */
    fun cancel(id: String) {
        update(id) { it.copy(state = DownloadState.CANCELLED) }
    }

    /** Remove a completed/failed/cancelled entry from the list. */
    fun dismiss(id: String) {
        _downloads.update { list -> list.filter { it.id != id } }
    }

    /** Clear all completed, failed, and cancelled downloads from history. */
    fun clearFinished() {
        _downloads.update { list -> list.filter { it.isActive } }
    }

    /** Retry a failed download — resets state to QUEUED. */
    fun retry(id: String) {
        update(id) {
            it.copy(
                state = DownloadState.QUEUED,
                downloadedBytes = 0,
                errorMessage = null,
                startedAt = System.currentTimeMillis(),
                completedAt = null,
            )
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun add(entry: DownloadEntry) {
        _downloads.update { list ->
            val existing = list.indexOfFirst { it.id == entry.id }
            if (existing >= 0) list.toMutableList().apply { set(existing, entry) }
            else list + entry
        }
    }

    private fun update(id: String, transform: (DownloadEntry) -> DownloadEntry) {
        _downloads.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }
}
