package com.codespace.ide.chat

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * C12 Stage 3 U2/U3 (advisor GO 2026-10-20): the ONE zip entry point for every
 * attachment extractor. Zip-bomb defense in one place, tested once, trusted
 * everywhere:
 *  - entry count cap (a zip with 100k entries never gets iterated),
 *  - per-entry DECOMPRESSED cap (5 MB) enforced by the byte-copying loop,
 *  - TOTAL decompressed cap across one operation (25 MB),
 *  - compression-RATIO guard (central-directory size vs compressed size,
 *    100:1 — aborts BEFORE decompressing a classic bomb),
 *  - encrypted-OOXML detection (the EncryptedPackage entry) and
 *    zip-encrypted entries (ZipFile throws on read; caught as a refusal).
 *
 * Zip-slip is structurally impossible here: NO entry name is ever used as a
 * filesystem path — names are display strings only; extraction streams bytes
 * in memory. (Advisor item 7; pinned by a fixture with "../" and absolute
 * entry names.)
 */
object SafeZipReader {

    const val MAX_ENTRIES = 500
    const val MAX_ENTRY_BYTES = 5L * 1024 * 1024        // per-entry decompressed cap
    const val MAX_TOTAL_BYTES = 25L * 1024 * 1024       // total per operation
    const val MAX_RATIO = 100                            // size/compressedSize guard

    /** Raised on any limit breach — callers translate to an honest refusal. */
    class ZipLimitException(val what: String) : Exception(what)
    /** The archive is malformed/truncated — ZipFile could not open it. */
    class ZipMalformedException(msg: String) : Exception(msg)
    /** The archive (or its entries) are password-protected. */
    class ZipEncryptedException : Exception("encrypted archive")

    /** Quick structural check: can the file even be opened as a zip? */
    fun isZip(file: File): Boolean = try {
        ZipFile(file).use { true }
    } catch (_: Exception) { false }

    /** True when the OOXML container is password-encrypted (EncryptedPackage). */
    fun isEncryptedOoxml(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().any { it.name == "EncryptedPackage" }
        }
    } catch (_: Exception) { false }

    /** List entries with the ratio guard pre-applied. Names are data, never paths. */
    fun listEntries(file: File): List<ZipEntry> {
        val zip = openZip(file)
        try {
            val entries = zip.entries().toList()
            if (entries.size > MAX_ENTRIES) throw ZipLimitException("more than $MAX_ENTRIES entries")
            var total = 0L
            for (e in entries) {
                ratioGuard(e)
                total += e.size.coerceAtLeast(0L)
            }
            if (total > MAX_TOTAL_BYTES) throw ZipLimitException("total decompressed size over cap")
            return entries
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
    }

    /** Read ONE entry, capped. Returns (bytes, hitEntryCap). Never trusts names. */
    fun readEntry(file: File, entryName: String, capBytes: Long = MAX_ENTRY_BYTES): Pair<ByteArray, Boolean> {
        val zip = openZip(file)
        try {
            val entry = zip.getEntry(entryName) ?: throw ZipMalformedException("entry not found: $entryName")
            return readEntryStream(zip, entry, capBytes)
        } finally {
            try { zip.close() } catch (_: Exception) {}
        }
    }

    /** Read one entry from an ALREADY-OPEN zip (avoids reopen per sheet/slide). */
    fun readEntryStream(zip: ZipFile, entry: ZipEntry, capBytes: Long = MAX_ENTRY_BYTES): Pair<ByteArray, Boolean> {
        ratioGuard(entry)
        val stream = try {
            zip.getInputStream(entry)
        } catch (e: Exception) {
            // java.util.zip throws on password-protected entries at read time.
            val m = e.message ?: ""
            if (m.contains("encrypt", ignoreCase = true)) throw ZipEncryptedException() else throw e
        }
        stream.use { ins ->
            val buf = ByteArray(64 * 1024)
            val out = java.io.ByteArrayOutputStream(minOf(entry.size.coerceAtLeast(0L), capBytes).toInt().coerceAtLeast(8192))
            var total = 0L
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                total += n
                if (total > capBytes) {
                    out.write(buf, 0, (capBytes - (total - n)).toInt().coerceAtLeast(0))
                    return out.toByteArray() to true
                }
                out.write(buf, 0, n)
            }
            return out.toByteArray() to false
        }
    }

    private fun ratioGuard(e: ZipEntry) {
        // Stored entries (compressedSize==0) skip the guard; SIZE -1 (unknown,
        // streaming zips) is handled by the copy loop's caps.
        val comp = e.compressedSize
        val size = e.size
        if (comp > 0 && size > comp * MAX_RATIO) {
            throw ZipLimitException("compression ratio over $MAX_RATIO:1 (zip bomb?)")
        }
    }

    private fun openZip(file: File): ZipFile = try {
        ZipFile(file)
    } catch (e: Exception) {
        throw ZipMalformedException("not a valid zip archive: ${e.message ?: ""}")
    }
}
