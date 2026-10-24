package com.codespace.ide.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A3 (advisor ruling 2026-10-24): attach ANY file from OUTSIDE the project via
 * the system picker (ACTION_OPEN_DOCUMENT, MIME * / *). Requirements honored:
 *  - sanitized display names (no separators/traversal/control chars — the name
 *    is data for a filename, never a path);
 *  - copy into APP-PRIVATE staging only (filesDir/chat-staging, never shared
 *    storage, never persisted URI grants);
 *  - the 10 MB intake cap enforced WHILE STREAMING (no unbounded read);
 *  - a cancelled launch (null URI) never touches disk; a FAILED copy deletes
 *    the partial file (stagedCopy's catch);
 *  - classified AFTER import by the SAME AttachmentTypeSniffer every other
 *    attachment goes through (the overlay's attachmentWithSnapshot runs the
 *    verdict on the staged file); ZIP stays a read-only listing (existing
 *    SafeZipReader pipeline — never auto-opened, nested archives never opened);
 *  - CLEANUP POLICY: staged files live in private app storage and are swept
 *    (deleted) once older than 24h, at every import; while a chip references a
 *    staged file it stays readable so retry/send keeps working.
 */
object ChatStagedFileImport {
    private const val STAGING_DIR = "chat-staging"
    const val SWEEP_AGE_MS = 24L * 60 * 60 * 1000

    fun importFromUri(context: Context, uri: Uri): ChatAttachment {
        sweepStaging(context)
        val safe = sanitizeName(queryDisplayName(context, uri))
        val dir = File(context.filesDir, STAGING_DIR).apply { mkdirs() }
        val dest = try {
            val ins = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open the selected file")
            ins.use { stagedCopy(it, dir, safe) }
        } catch (e: Exception) {
            throw Exception("Could not attach '" + safe + "': " + (e.message ?: "import failed"))
        }
        return ChatAttachment(
            path = dest.absolutePath,
            relPath = dest.name,
            name = dest.name,
            kind = ChatAttachment.Kind.FILE,
        )
    }

    /** Cleanup policy sweep: private staged files older than [SWEEP_AGE_MS]. */
    fun sweepStaging(context: Context) {
        try {
            val dir = File(context.filesDir, STAGING_DIR)
            val cutoff = System.currentTimeMillis() - SWEEP_AGE_MS
            dir.listFiles()?.forEach { if (it.isFile && it.lastModified() < cutoff) it.delete() }
        } catch (_: Exception) { }
    }

    /**
     * Pure-JVM-testable import core: copy [ins] into [dir] under [safeName]
     * with the 10 MB intake cap enforced WHILE STREAMING. A failed (or
     * over-cap) copy deletes the partial file — nothing half-written is kept.
     */
    internal fun stagedCopy(ins: InputStream, dir: File, safeName: String): File {
        val dest = uniqueDest(dir, safeName)
        try {
            dest.outputStream().use { outs -> copyCapped(ins, outs, ChatAttachment.INTAKE_CAP_BYTES) }
            return dest
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    /** Streams with the cap enforced DURING the read — no unbounded buffering. */
    internal fun copyCapped(ins: InputStream, outs: OutputStream, capBytes: Long): Long {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > capBytes) throw Exception("File is larger than the 10 MB intake cap — not attached")
            outs.write(buf, 0, n)
        }
        return total
    }

    /** Filename sanitization: separators/traversal impossible by construction. */
    fun sanitizeName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').take(120)
        val sb = StringBuilder()
        for (ch in base) {
            val c = ch.code
            if (c < 32 || c == 127) continue
            when (ch) {
                '/', '\\', ':', '*', '?', '"', '<', '>', '|' -> continue
                else -> sb.append(ch)
            }
        }
        val out = sb.toString().trim()
        return if (out.isEmpty()) "imported-file" else out
    }

    private fun uniqueDest(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            f = File(dir, "$stem-$i$ext")
            if (!f.exists()) return f
            i++
        }
        throw Exception("Too many staged files with this name")
    }

    private fun queryDisplayName(context: Context, uri: Uri): String {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) ?: "" else ""
            } ?: ""
        } catch (_: Exception) { "" }
    }
}
