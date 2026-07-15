package com.codespace.ide.project

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

/**
 * P16-B: Cloud Backup Manager
 * Backup/restore projects to the Railway backend as tar.gz archives.
 * Endpoints (all require Bearer auth):
 *   POST /api/backup/upload   — multipart, field 'archive', returns {"id":"..."}
 *   GET  /api/backup/list     — returns [{id,name,size,created_at}]
 *   GET  /api/backup/:id/download — returns tar.gz stream
 */
object CloudBackupManager {

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
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val projectDir = File(context.filesDir, "projects/$projectId")
            if (!projectDir.exists()) error("Project directory not found: ${projectDir.absolutePath}")

            val archiveFile = File(context.cacheDir, "backup_${projectId}_${System.currentTimeMillis()}.tar.gz")
            try {
                createTarGz(projectDir, archiveFile)

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

    // ── Tar.gz helpers ──────────────────────────────────────────────────────────

    private fun createTarGz(sourceDir: File, destFile: File) {
        // Simple tar-like: write each file as a header + data block (POSIX ustar subset)
        GZIPOutputStream(BufferedOutputStream(FileOutputStream(destFile))).use { gz ->
            sourceDir.walkTopDown().filter { it.isFile }.forEach { file ->
                val relPath = file.relativeTo(sourceDir).path
                writeTarEntry(gz, file, relPath)
            }
            // End-of-archive: two 512-byte zero blocks
            gz.write(ByteArray(1024))
        }
    }

    private fun writeTarEntry(out: java.io.OutputStream, file: File, name: String) {
        val nameBytes = name.toByteArray(Charsets.US_ASCII).take(100)
        val header = ByteArray(512)
        nameBytes.forEachIndexed { i, b -> header[i] = b }
        val size = file.length()
        // size in octal at offset 124, 12 bytes
        val sizeOctal = size.toString(8).padStart(11, '0') + ' '
        sizeOctal.toByteArray().forEachIndexed { i, b -> header[124 + i] = b }
        // file type: '0' = regular
        header[156] = '0'.code.toByte()
        // checksum at offset 148
        var checksum = 0; header.forEach { b -> checksum += (b.toInt() and 0xFF) }
        val chkOctal = checksum.toString(8).padStart(6, '0') + '\u0000' + ' '
        chkOctal.toByteArray().forEachIndexed { i, b -> header[148 + i] = b }
        out.write(header)
        FileInputStream(file).use { fis ->
            val buf = ByteArray(4096); var read: Int
            var written = 0L
            while (fis.read(buf).also { read = it } != -1) { out.write(buf, 0, read); written += read }
            // Pad to 512-byte boundary
            val pad = ((512 - (written % 512)) % 512).toInt()
            if (pad > 0) out.write(ByteArray(pad))
        }
    }

    private fun extractTarGz(archiveFile: File, destDir: File) {
        GZIPInputStream(BufferedInputStream(FileInputStream(archiveFile))).use { gz ->
            val header = ByteArray(512)
            while (true) {
                val read = gz.read(header); if (read < 512) break
                val name = header.copyOf(100).toString(Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) break
                val sizeStr = header.copyOfRange(124, 136).toString(Charsets.US_ASCII).trim().trimEnd('\u0000')
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val destFile = File(destDir, name)
                destFile.parentFile?.mkdirs()
                if (size > 0) {
                    val data = ByteArray(size.toInt())
                    var totalRead = 0
                    while (totalRead < size) {
                        val r = gz.read(data, totalRead, (size - totalRead).toInt())
                        if (r < 0) break
                        totalRead += r
                    }
                    destFile.writeBytes(data)
                    // Skip padding
                    val pad = ((512 - (size % 512)) % 512).toInt()
                    if (pad > 0) gz.skip(pad.toLong())
                }
            }
        }
    }
}
