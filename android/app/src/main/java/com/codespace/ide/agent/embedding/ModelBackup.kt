package com.codespace.ide.agent.embedding

import android.content.Context
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * PROJECT NINE 10.20 (catalog 45) - the trained model rides the owner's own backup.
 *
 * The model is 110MB. Android's auto-backup allowance is 25MB for the whole app, so the
 * platform backup can never carry it; this uses the same cloud backup the projects already
 * use, under its own name, so a restore after a reinstall does not depend on the network
 * staying up long enough to re-download.
 *
 * Self-contained on purpose: it does not touch the working project-backup code.
 */
object ModelBackup {

    private const val TIMEOUT_READ = 600_000

    fun archiveName(modelId: String): String = "model-$modelId.tar.gz"

    suspend fun upload(
        context: Context,
        backendUrl: String,
        authToken: String,
        modelId: String,
    ): Result<String> = runCatching {
        require(backendUrl.isNotBlank()) { "No backend configured" }
        require(authToken.isNotBlank()) { "Not signed in" }
        // The model's home is shared storage so it survives an uninstall; back up whichever
        // folder actually holds the files, not a hardcoded one that may be empty.
        val dir = ModelHome.searchOrder(context)
            .firstOrNull { d -> d.isDirectory && !d.listFiles().isNullOrEmpty() }
            ?: File(context.filesDir, "models")
        if (!dir.isDirectory || dir.listFiles().isNullOrEmpty()) error("No model files to back up")
        val archive = File(context.cacheDir, "model_" + System.currentTimeMillis() + ".tar.gz")
        try {
            tarGz(dir, archive)
            uploadArchive(backendUrl, authToken, archive, archiveName(modelId))
        } finally {
            archive.delete()
        }
    }

    /** True when an archive was found and unpacked. False means there is none to restore. */
    suspend fun restore(
        context: Context,
        backendUrl: String,
        authToken: String,
        modelId: String,
    ): Result<Boolean> = runCatching {
        require(backendUrl.isNotBlank()) { "No backend configured" }
        require(authToken.isNotBlank()) { "Not signed in" }
        val wanted = archiveName(modelId)
        val id = findArchiveId(backendUrl, authToken, wanted) ?: return@runCatching false
        val archive = File(context.cacheDir, "model_restore_" + System.currentTimeMillis() + ".tar.gz")
        try {
            download(backendUrl, authToken, id, archive)
            // Restore into the writable home, which is the shared folder when it is usable.
            val dest = ModelHome.writableHome(context)
            dest.mkdirs()
            untarGz(archive, dest)
            true
        } finally {
            archive.delete()
        }
    }

    private fun findArchiveId(backendUrl: String, authToken: String, name: String): String? {
        val conn = (URL("$backendUrl/api/backup/list").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $authToken")
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: "[]"
        conn.disconnect()
        if (code !in 200..299) return null
        val arr = JSONArray(body)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") == name) return o.optString("id")
        }
        return null
    }

    private fun download(backendUrl: String, authToken: String, id: String, dest: File) {
        val conn = (URL("$backendUrl/api/backup/$id/download").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $authToken")
            connectTimeout = 30_000
            readTimeout = TIMEOUT_READ
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            error("Model download failed ($code)")
        }
        conn.inputStream.use { ins -> FileOutputStream(dest).use { out -> ins.copyTo(out) } }
        conn.disconnect()
    }

    private fun uploadArchive(backendUrl: String, authToken: String, archive: File, name: String): String {
        val boundary = "----Boundary" + System.currentTimeMillis()
        val conn = (URL("$backendUrl/api/backup/upload").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Authorization", "Bearer $authToken")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            connectTimeout = 30_000
            readTimeout = TIMEOUT_READ
        }
        conn.outputStream.use { out ->
            val bos = BufferedOutputStream(out)
            val crlf = "\r\n"
            bos.write(("--" + boundary + crlf).toByteArray())
            bos.write(("Content-Disposition: form-data; name=\"archive\"; filename=\"" + name + "\"" + crlf).toByteArray())
            bos.write(("Content-Type: application/gzip" + crlf + crlf).toByteArray())
            FileInputStream(archive).use { fis -> fis.copyTo(bos) }
            bos.write((crlf + "--" + boundary + "--" + crlf).toByteArray())
            bos.flush()
        }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        if (code !in 200..299) error("Model upload failed ($code): $body")
        return JSONObject(body).getString("id")
    }

    private fun tarGz(sourceDir: File, destFile: File) {
        TarArchiveOutputStream(GzipCompressorOutputStream(FileOutputStream(destFile))).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            sourceDir.walkTopDown().filter { it.isFile }.forEach { f ->
                val entry = TarArchiveEntry(f, f.relativeTo(sourceDir).path)
                tar.putArchiveEntry(entry)
                FileInputStream(f).use { it.copyTo(tar) }
                tar.closeArchiveEntry()
            }
        }
    }

    private fun untarGz(archive: File, destDir: File) {
        TarArchiveInputStream(GzipCompressorInputStream(FileInputStream(archive))).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val target = File(destDir, entry.name)
                val canonical = target.canonicalFile
                if (!canonical.path.startsWith(destDir.canonicalFile.path)) {
                    error("Refused an archive entry outside the model folder")
                }
                if (entry.isDirectory) {
                    canonical.mkdirs()
                } else {
                    canonical.parentFile?.mkdirs()
                    FileOutputStream(canonical).use { out -> tar.copyTo(out) }
                }
                entry = tar.nextEntry
            }
        }
    }
}
