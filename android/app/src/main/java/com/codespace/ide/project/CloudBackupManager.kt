package com.codespace.ide.project

import android.content.Context
import android.util.Log
import com.codespace.ide.util.CanonicalPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * P16-B: Cloud Backup Manager
 * Backup/restore projects to the Railway backend as tar.gz archives.
 * Endpoints (all require Bearer auth):
 *   POST /api/backup/upload   — multipart, field 'archive', returns {"id":"..."}
 *   GET  /api/backup/list     — returns [{id,name,size,created_at}]
 *   GET  /api/backup/:id/download — returns tar.gz stream
 */
object CloudBackupManager {

    private const val MAX_RETRIES = 3
    private val backoffDelayMs = longArrayOf(1000, 3000, 7000)

    // TEST-53-FIX: onAttempt reports retry progress ("Retrying (2/3)...") so the caller
    // can surface it to the user via SyncStatusMonitor/actionMsg instead of silently
    // retrying with no visible feedback. Final error message now states how many
    // attempts were made, so a network failure clearly reads as "retried 3x" not
    // just a generic single failure.
    private suspend fun <T> retryNetwork(
        tag: String,
        onAttempt: ((attempt: Int, max: Int) -> Unit)? = null,
        block: suspend () -> T,
    ): T {
        var lastError: Exception? = null
        for (attempt in 0 until MAX_RETRIES) {
            if (attempt > 0) onAttempt?.invoke(attempt + 1, MAX_RETRIES)
            try {
                return block()
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(backoffDelayMs[attempt])
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    kotlinx.coroutines.delay(backoffDelayMs[attempt])
                }
            }
        }
        throw java.io.IOException(
            "$tag failed after $MAX_RETRIES attempts: ${lastError?.message ?: "network error"}",
            lastError,
        )
    }

    data class BackupEntry(
        val id: String,
        val name: String,
        val sizeBytes: Long,
        val createdAt: String,
    )

    // ── Backup ──────────────────────────────────────────────────────────────────

    suspend fun backupProject(
        context: Context,
        projectId: String,
        backendUrl: String,
        authToken: String,
        onRetry: ((attempt: Int, max: Int) -> Unit)? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            // TEST-53-FIX: projectId from navigation is a timestamp (System.currentTimeMillis()),
            // but the actual folder on disk is named after the project name.
            // Resolve the real directory: check SharedPreferences for the project name,
            // then fall back to direct projectId match, then search projects/ for a matching folder.
            val projectsBase = File(context.filesDir, "projects")
            val projectDir = resolveProjectDir(context, projectId, projectsBase)
            if (projectDir == null || !projectDir.exists())
                error("Project directory not found. Looked for '$projectId' in ${projectsBase.absolutePath}")

            val archiveFile = File(context.cacheDir, "backup_${projectId}_${System.currentTimeMillis()}.tar.gz")
            try {
                createTarGz(projectDir, archiveFile)

                retryNetwork("backup", onAttempt = onRetry) {
                    val boundary = "----Boundary${System.currentTimeMillis()}"
                    val url = URL("$backendUrl/api/backup/upload")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Authorization", "Bearer $authToken")
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }

                    conn.outputStream.use { out ->
                        val bos = BufferedOutputStream(out)
                        val CRLF = "\r\n"
                        // part header
                        bos.write("--$boundary$CRLF".toByteArray())
                        bos.write("Content-Disposition: form-data; name=\"archive\"; filename=\"$projectId.tar.gz\"$CRLF".toByteArray())
                        bos.write("Content-Type: application/gzip$CRLF$CRLF".toByteArray())
                        // file content
                        FileInputStream(archiveFile).use { fis -> fis.copyTo(bos) }
                        bos.write("$CRLF--$boundary--$CRLF".toByteArray())
                        bos.flush()
                    }

                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.readText() ?: ""
                    conn.disconnect()

                    if (code !in 200..299) error("Upload failed ($code): $body")
                    JSONObject(body).getString("id")
                }
            } finally {
                archiveFile.delete()
            }
        }
    }

    // ── Restore ─────────────────────────────────────────────────────────────────

    suspend fun restoreProject(
        context: Context,
        backupId: String,
        backendUrl: String,
        authToken: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val archiveFile = File(context.cacheDir, "restore_${backupId}_${System.currentTimeMillis()}.tar.gz")
            try {
                retryNetwork("restore") {
                    val url = URL("$backendUrl/api/backup/$backupId/download")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        setRequestProperty("Authorization", "Bearer $authToken")
                        connectTimeout = 30_000
                        readTimeout = 120_000
                    }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                        conn.disconnect()
                        error("Download failed ($code): $body")
                    }
                    conn.inputStream.use { ins -> FileOutputStream(archiveFile).use { out -> ins.copyTo(out) } }
                    conn.disconnect()
                }

                val destDir = File(context.filesDir, "projects")
                destDir.mkdirs()
                extractTarGz(archiveFile, destDir)
                destDir.absolutePath
            } finally {
                archiveFile.delete()
            }
        }
    }

    // ── List ────────────────────────────────────────────────────────────────────

    suspend fun listBackups(
        backendUrl: String,
        authToken: String,
    ): Result<List<BackupEntry>> = withContext(Dispatchers.IO) {
        runCatching {
            retryNetwork("listBackups") {
                val url = URL("$backendUrl/api/backup/list")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Authorization", "Bearer $authToken")
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: "[]"
                conn.disconnect()
                if (code !in 200..299) error("List failed ($code): $body")
                val arr = JSONArray(body)
                (0 until arr.length()).map { i ->
                    val obj = arr.getJSONObject(i)
                    BackupEntry(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        sizeBytes = obj.optLong("size"),
                        createdAt = obj.optString("created_at"),
                    )
                }
            }
        }
    }

    /**
     * TEST-53-FIX: Resolve the actual project directory on disk.
     *
     * Navigation passes projectId (a timestamp like "1690000000000") but the folder
     * on disk is named after the project name (e.g. "MyApp"). This helper:
     *   1. Checks if projects/$projectId exists directly (legacy/edge case)
     *   2. Looks up the project name from SharedPreferences ("projects" → "list" JSON)
     *   3. Falls back to scanning projects/ for a single subdirectory
     */
    private fun resolveProjectDir(context: Context, projectId: String, projectsBase: File): File? {
        // 1. Direct match (if someone named the folder with the timestamp)
        val direct = File(projectsBase, projectId)
        if (direct.exists() && direct.isDirectory) return direct

        // 2. Look up project name from SharedPreferences
        try {
            val prefs = context.getSharedPreferences("projects", Context.MODE_PRIVATE)
            val listStr = prefs.getString("list", null)
            if (listStr != null) {
                val arr = JSONArray(listStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    if (obj.optString("id") == projectId) {
                        val name = obj.optString("name")
                        if (name.isNotBlank()) {
                            val byName = File(projectsBase, name)
                            if (byName.exists() && byName.isDirectory) return byName
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 3. Fallback: if there's exactly one project folder, use it
        val subdirs = projectsBase.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && f.name != ".trash" }
        if (subdirs != null && subdirs.size == 1) return subdirs[0]

        return null
    }

    // ── Tar.gz helpers ──────────────────────────────────────────────────────────

    private fun createTarGz(sourceDir: File, destFile: File) {
        // RG04 (P2a): the hand-rolled ustar writer TRUNCATED entry names to 100
        // bytes (take(100)) — long paths silently corrupted the archive. Migrated to
        // commons-compress (same proven dialect as BackupManager): GNU longname mode
        // writes long paths correctly.
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { gz ->
            TarArchiveOutputStream(gz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_STAR)
                sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relPath = file.relativeTo(sourceDir).path
                    val entry = TarArchiveEntry(file, relPath)
                    entry.size = file.length()
                    tar.putArchiveEntry(entry)
                    FileInputStream(file).use { fis -> fis.copyTo(tar) }
                    tar.closeArchiveEntry()
                }
            }
        }
    }

    private fun extractTarGz(archiveFile: File, destDir: File) {
        // RG03 (P2a): the hand-rolled reader did raw File(destDir, name) from a
        // NETWORK header — zero containment (tar-entry traversal), plus an
        // unbounded ByteArray(size) allocation from the same untrusted header
        // (OOM on a malicious archive). Migrated to commons-compress streaming;
        // every entry destination resolves through the shared containment utility.
        GZIPInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { gz ->
            TarArchiveInputStream(gz).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    val destFile = CanonicalPaths.safeEntryDestination(destDir, entry.name)
                    if (destFile == null) {
                        // Fail closed: rejected entries are logged and skipped —
                        // advancing nextEntry skips their data.
                        Log.w("CloudBackupManager", "Rejected tar entry (escape attempt): ${entry.name}")
                    } else if (entry.isDirectory) {
                        destFile.mkdirs()
                    } else {
                        destFile.parentFile?.mkdirs()
                        destFile.outputStream().use { out -> tar.copyTo(out) }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    }
}
