package com.codespace.ide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        runTransfer(entry)
    }

    /**
     * IG05 (P3a): the shared transfer engine. The old cancel() only flipped the
     * state — the transfer never checked it and its COMPLETION overwrote
     * CANCELLED with COMPLETED; retry() flipped to QUEUED with no engine
     * watching. Now:
     *  - the transfer loop checks the entry state and STOPS when cancelled
     *    (partial file deleted, state left at CANCELLED);
     *  - completion can only transition out of DOWNLOADING, so a late cancel
     *    can no longer be overwritten by a completed transfer;
     *  - retry() actually re-runs the transfer from scratch.
     */
    private suspend fun runTransfer(entry: DownloadEntry): Boolean = withContext(Dispatchers.IO) {
        val id = entry.id
        val destFile = File(entry.destPath)
        try {
            destFile.parentFile?.mkdirs()
            val connection = URL(entry.url).openConnection()
            connection.connect()
            val total = connection.contentLengthLong
            update(id) { it.copy(totalBytes = total) }

            connection.getInputStream().use { input ->
                destFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    var read: Int
                    var sinceCancelCheck = 0
                    while (input.read(buf).also { read = it } != -1) {
                        // IG05: check cancellation every ~64 chunks (512 KB) so the
                        // cancel button actually stops the transfer.
                        if (++sinceCancelCheck >= 64) {
                            sinceCancelCheck = 0
                            if (_downloads.value.find { it.id == id }?.state == DownloadState.CANCELLED) {
                                throw java.io.IOException("Cancelled")
                            }
                        }
                        output.write(buf, 0, read)
                        downloaded += read
                        update(id) { it.copy(downloadedBytes = downloaded) }
                    }
                }
            }

            // IG05: completion is only valid from the DOWNLOADING state — a
            // cancel that raced the final chunk keeps its CANCELLED state.
            var ok = false
            _downloads.update { list ->
                list.map { if (it.id == id && it.state == DownloadState.DOWNLOADING) {
                    ok = true
                    it.copy(
                        state = DownloadState.COMPLETED,
                        downloadedBytes = total.coerceAtLeast(it.downloadedBytes),
                        completedAt = System.currentTimeMillis(),
                    )
                } else it }
            }
            ok
        } catch (e: Exception) {
            val cancelled = e.message == "Cancelled" ||
                _downloads.value.find { it.id == id }?.state == DownloadState.CANCELLED
            if (cancelled) {
                destFile.delete()  // partial file from a cancelled transfer
            } else {
                update(id) {
                    it.copy(state = DownloadState.FAILED, errorMessage = e.message ?: "Unknown error")
                }
                destFile.delete()
            }
            false
        }
    }

    /** Cancel an in-progress download by ID. The transfer loop observes it (IG05). */
    fun cancel(id: String) {
        update(id) {
            // IG05: only a live transfer can be cancelled; never overwrite a
            // terminal COMPLETED/FAILED state.
            if (it.state == DownloadState.DOWNLOADING || it.state == DownloadState.QUEUED)
                it.copy(state = DownloadState.CANCELLED)
            else it
        }
    }

    /** Remove a completed/failed/cancelled entry from the list. */
    fun dismiss(id: String) {
        _downloads.update { list -> list.filter { it.id != id } }
    }

    /** Clear all completed, failed, and cancelled downloads from history. */
    fun clearFinished() {
        _downloads.update { list -> list.filter { it.isActive } }
    }

    /**
     * Retry a failed/cancelled download (IG05): actually re-runs the transfer.
     * The old version only flipped the state to QUEUED — nothing watched it,
     * so the button appeared to work while doing nothing.
     */
    fun retry(id: String) {
        val entry = _downloads.value.find { it.id == id } ?: return
        if (entry.state != DownloadState.FAILED && entry.state != DownloadState.CANCELLED) return
        update(id) {
            it.copy(
                state = DownloadState.DOWNLOADING,
                downloadedBytes = 0,
                errorMessage = null,
                startedAt = System.currentTimeMillis(),
                completedAt = null,
            )
        }
        kotlinx.coroutines.MainScope().launch { runTransfer(entry) }
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
