package com.codespace.ide.chat

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.util.zip.ZipFile

/**
 * C12 Stage 3 U3 (advisor GO 2026-10-20): ARCHIVE LISTING + ENTRY TEXT PREVIEW
 * for zip / tar / tar.gz.
 *
 *  - Entry cap: [SafeZipReader.MAX_ENTRIES] with an honest "showing X of Y" note.
 *  - NO entry name is EVER used as a filesystem path — names are display rows
 *    only; the preview reads the entry BY NAME from the already-open archive,
 *    in memory. Zip-slip is structurally impossible and the traversal/absolute
 *    name fixture pins it.
 *  - NESTED archives (a .zip inside the listing) appear as rows but are NEVER
 *    auto-opened — the user sees them like any other entry.
 *  - Malformed / truncated archives fall back to an honest refusal.
 */
object ArchiveEntryLister {

    data class EntryRow(
        /** DISPLAY ONLY — never a path. */
        val name: String,
        val sizeBytes: Long,
        val isDir: Boolean,
        /** True when this entry itself looks like an archive (listed, never auto-opened). */
        val nestedArchive: Boolean,
    )

    data class Listing(
        val rows: List<EntryRow>,
        val totalEntries: Int,
        val capped: Boolean,
        val malformed: Boolean = false,
        val encrypted: Boolean = false,
    )

    /** Default attachment-time listing: rows + the text that rides the send. */
    fun defaultListing(file: File): AttachmentIntake.ExtractedDoc {
        val listing = try {
            list(file)
        } catch (e: SafeZipReader.ZipLimitException) {
            return AttachmentIntake.ExtractedDoc(refusal = "archive limits exceeded (${e.what}) — showing file info only")
        } catch (e: SafeZipReader.ZipMalformedException) {
            return AttachmentIntake.ExtractedDoc(refusal = "not a readable archive (${e.message}) — showing file info only")
        } catch (e: SafeZipReader.ZipEncryptedException) {
            return AttachmentIntake.ExtractedDoc(refusal = "this archive is password-encrypted — not supported; showing file info only")
        } catch (e: Exception) {
            return AttachmentIntake.ExtractedDoc(refusal = "could not read the archive (${e.message ?: "unreadable"}) — showing file info only")
        }
        if (listing.malformed) {
            return AttachmentIntake.ExtractedDoc(refusal = "not a readable archive — showing file info only")
        }
        if (listing.encrypted) {
            return AttachmentIntake.ExtractedDoc(refusal = "this archive is password-encrypted — not supported; showing file info only")
        }
        val sb = DocumentTextExtractor.CapSink()
        val notes = ArrayList<String>()
        sb.line("[archive: ${file.name} — ${listing.totalEntries} entries]")
        for (r in listing.rows) {
            sb.line(if (r.isDir) "${r.name}/" else "${r.name}  (${r.sizeBytes} bytes)")
        }
        if (listing.capped) {
            notes.add("showing ${listing.rows.size} of ${listing.totalEntries} entries (cap ${SafeZipReader.MAX_ENTRIES}); nested archives are listed but never auto-opened")
        } else {
            notes.add("nested archives are listed but never auto-opened")
        }
        var text = sb.result()
        var truncated = false
        if (text.length > AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS) {
            text = text.take(AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS); truncated = true
        }
        val (red, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT)
        if (changed) notes.add("secrets redacted")
        if (truncated) notes.add("listing truncated to fit the preview cap")
        return AttachmentIntake.ExtractedDoc(text = red, notes = notes, truncated = truncated)
    }

    /** One entry's text preview — BY NAME, in memory, capped. Display names only. */
    fun previewEntry(file: File, entryName: String): AttachmentIntake.ExtractedDoc {
        return try {
            val lower = file.extension.lowercase()
            val isTar = lower == "tar" || lower == "tgz"
            val bytes: ByteArray = if (isTar) {
                readTarEntry(file, entryName) ?: return AttachmentIntake.ExtractedDoc(refusal = "entry not found in the archive")
            } else {
                val (b, hitCap) = SafeZipReader.readEntry(file, entryName)
                if (hitCap) return AttachmentIntake.ExtractedDoc(refusal = "entry is larger than the 5 MB preview cap")
                b
            }
            // binary sniff (s1-c family): NUL in the head = no text preview.
            val head = bytes.take(8192)
            if (head.contains(0.toByte())) {
                return AttachmentIntake.ExtractedDoc(refusal = "binary entry — no text preview")
            }
            var text = String(bytes, Charsets.UTF_8)
            var truncated = false
            if (text.length > AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS) {
                text = text.take(AttachmentIntake.MAX_EXTRACTED_SNAPSHOT_CHARS); truncated = true
            }
            val (red, changed) = AttachmentSecrets.redact(text, AttachmentSecrets.TIER_ATTACHMENT)
            val notes = ArrayList<String>()
            if (changed) notes.add("secrets redacted")
            if (truncated) notes.add("entry text truncated to fit the preview cap")
            AttachmentIntake.ExtractedDoc(text = red, notes = notes, truncated = truncated)
        } catch (e: SafeZipReader.ZipLimitException) {
            AttachmentIntake.ExtractedDoc(refusal = "entry limits exceeded (${e.what})")
        } catch (e: SafeZipReader.ZipMalformedException) {
            AttachmentIntake.ExtractedDoc(refusal = "not a readable archive")
        } catch (e: SafeZipReader.ZipEncryptedException) {
            AttachmentIntake.ExtractedDoc(refusal = "this archive is password-encrypted — not supported")
        } catch (e: Exception) {
            AttachmentIntake.ExtractedDoc(refusal = "could not preview the entry (${e.message ?: "unreadable"})")
        }
    }

    // ── listing implementations ────────────────────────────────────────────

    fun list(file: File): Listing = when (file.extension.lowercase()) {
        "zip", "apk", "jar", "docx", "xlsx", "pptx", "docm", "xlsm",
        "odt", "ods", "odp" -> listZip(file)
        "gz", "tgz", "tar" -> listTar(file)
        else -> listZip(file)   // magic-driven fallback (zip family)
    }

    private fun listZip(file: File): Listing {
        return try {
            val entries = SafeZipReader.listEntries(file)
            val total = entries.size
            val rows = entries
                .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                .take(SafeZipReader.MAX_ENTRIES)
                .map { e ->
                    EntryRow(
                        name = e.name,
                        sizeBytes = e.size.coerceAtLeast(0),
                        isDir = e.isDirectory,
                        nestedArchive = e.name.endsWith(".zip") || e.name.endsWith(".tar") ||
                            e.name.endsWith(".gz") || e.name.endsWith(".tgz") || e.name.endsWith(".jar"),
                    )
                }
            Listing(rows, total, capped = total > rows.size)
        } catch (e: SafeZipReader.ZipLimitException) {
            // Entry-count or size overrun: still show the cap's worth, honestly.
            rawZipListing(file)
        } catch (e: SafeZipReader.ZipMalformedException) {
            Listing(emptyList(), 0, false, malformed = true)
        } catch (e: SafeZipReader.ZipEncryptedException) {
            Listing(emptyList(), 0, false, encrypted = true)
        }
    }

    /** Fallback raw listing when limits are exceeded (count + first rows, capped). */
    private fun rawZipListing(file: File): Listing {
        return try {
            val zip = ZipFile(file)
            try {
                val all = zip.entries().toList()
                val rows = all.take(SafeZipReader.MAX_ENTRIES).map { e ->
                    EntryRow(e.name, e.size.coerceAtLeast(0), e.isDirectory,
                        e.name.endsWith(".zip") || e.name.endsWith(".tar") || e.name.endsWith(".gz"))
                }
                Listing(rows, all.size, capped = true)
            } finally {
                try { zip.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Listing(emptyList(), 0, false, malformed = true)
        }
    }

    private fun listTar(file: File): Listing {
        return try {
            val rows = ArrayList<EntryRow>()
            var total = 0
            val isGz = file.extension.lowercase() == "gz" || file.extension.lowercase() == "tgz"
            file.inputStream().let { fis ->
                val wrapped = if (isGz) GzipCompressorInputStream(fis) else fis
                TarArchiveInputStream(wrapped).use { tar ->
                    var entry: TarArchiveEntry? = tar.nextTarEntry
                    while (entry != null) {
                        total++
                        if (rows.size < SafeZipReader.MAX_ENTRIES) {
                            val n = entry.name
                            rows.add(EntryRow(
                                name = n,
                                sizeBytes = entry.size.coerceAtLeast(0),
                                isDir = entry.isDirectory,
                                nestedArchive = n.endsWith(".zip") || n.endsWith(".tar") || n.endsWith(".gz"),
                            ))
                        }
                        if (entry.size > SafeZipReader.MAX_ENTRY_BYTES * 4) {
                            return Listing(rows, maxOf(total, rows.size), capped = true)
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
            Listing(rows, total, capped = total > rows.size)
        } catch (e: Exception) {
            Listing(emptyList(), 0, false, malformed = true)
        }
    }

    private fun readTarEntry(file: File, entryName: String): ByteArray? {
        val isGz = file.extension.lowercase() == "gz" || file.extension.lowercase() == "tgz"
        file.inputStream().let { fis ->
            val wrapped = if (isGz) GzipCompressorInputStream(fis) else fis
            TarArchiveInputStream(wrapped).use { tar ->
                var entry: TarArchiveEntry? = tar.nextTarEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        if (entry.size > SafeZipReader.MAX_ENTRY_BYTES) {
                            throw SafeZipReader.ZipLimitException("entry over 5 MB")
                        }
                        val buf = ByteArray(entry.size.toInt())
                        var read = 0
                        while (read < buf.size) {
                            val k = tar.read(buf, read, buf.size - read)
                            if (k < 0) break
                            read += k
                        }
                        return buf.copyOf(read)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        return null
    }
}
