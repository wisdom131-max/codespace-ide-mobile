package com.codespace.ide.agent

import com.codespace.ide.util.ZipEngine
import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.RarFormat
import com.github.junrar.exception.RarException
import com.github.junrar.rarfile.FileHeader
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * P2.C - RAR, read through junrar.
 *
 * junrar is a pure-Java port of the UnRAR sources: no native library, so nothing to build per ABI, and
 * it parses RAR 1.4, 4.x and 5.x. What it may and may not be used for is fixed by the UnRAR licence -
 * it can handle RAR archives freely but must not be used to write a RAR-compatible archiver. This file
 * therefore only ever reads: there is no RAR creation anywhere in the app, by design and not by
 * oversight.
 *
 * Extraction runs through a counting sink rather than junrar's own extractors, for two reasons: the
 * limits stay in one place next to `ZipEngine`'s, and a stream that blows past them throws *while*
 * writing instead of after the damage is on disk.
 */
object RarEngine {

    /**
     * A RAR5 archive may declare a dictionary up to 4 GiB. junrar allocates that window when it
     * decodes, so it is capped here at 64 MiB: a phone-sized ceiling, and a refusal to open an archive
     * that genuinely needs more (rarer than it sounds - most RAR5 files use 32 MiB or less).
     */
    const val MAX_DICTIONARY_BYTES = 64L * 1024 * 1024

    fun variantLabel(format: RarFormat?): String = when (format) {
        RarFormat.RAR14 -> "RAR 1.4"
        RarFormat.RAR15 -> "RAR 4.x"
        RarFormat.RAR50 -> "RAR 5.x"
        else -> "RAR (unknown variant)"
    }

    private fun options(): ArchiveOptions = ArchiveOptions.builder()
        .maxDictionarySize(MAX_DICTIONARY_BYTES)
        .build()

    /**
     * Opens the archive with the dictionary ceiling applied. [block] runs with the archive open; a
     * failure to open becomes a sentence, never a stack trace escaping to the model.
     */
    fun <T> withArchive(file: File, block: (Archive) -> T): T? = try {
        Archive(file, options()).use { archive -> block(archive) }
    } catch (e: RarException) {
        null
    } catch (e: java.io.IOException) {
        null
    } catch (t: Throwable) {
        null
    }

    /** Why an archive could not be opened, in words the agent can act on. */
    fun openFailure(file: File): String = try {
        Archive(file, options()).use { archive ->
            // Opened fine; the caller only asks when it got nothing back, so report the shape.
            "The RAR archive opened but produced no readable entries (" + variantLabel(archive.format) + ")."
        }
    } catch (e: RarException) {
        "Could not read this RAR archive: " + (e.message ?: "the archive is damaged or uses a variant junrar cannot parse") + "."
    } catch (e: java.io.IOException) {
        "Could not read this RAR archive: " + (e.message ?: "I/O error") + "."
    } catch (t: Throwable) {
        "Could not read this RAR archive: " + (t.message ?: t.javaClass.simpleName) + "."
    }

    data class Entries(
        val variant: String,
        val items: List<ArchiveEngine.Item>,
        val total: Int,
        val encrypted: Boolean,
        val brokenHeaders: Int,
        val note: String?,
    )

    /** Bounded listing. Entry names are RAR's own, so unsafe paths are still flagged for the caller. */
    fun list(file: File, offset: Int, limit: Int): Entries = withArchive(file) { archive ->
        val variant = variantLabel(archive.format)
        val encrypted = runCatching { archive.isEncrypted() }.getOrDefault(false)
        val broken = archive.headerFailures.size
        val headers = archive.fileHeaders
        val cap = limit.coerceIn(1, ZipEngine.MAX_LIST)
        val items = ArrayList<ArchiveEngine.Item>()
        var total = 0
        for (header in headers) {
            if (!header.isFileHeader) continue
            total++
            if (total > ZipEngine.MAX_ENTRIES) break
            if (total <= offset) continue
            if (items.size >= cap) continue
            items.add(itemOf(header))
        }
        val note = when {
            broken > 0 -> "The archive has " + broken + " unreadable header(s); the listing may be incomplete."
            encrypted -> "The archive is password-protected; entry names are all that can be listed."
            headers.size > ZipEngine.MAX_ENTRIES -> "Entry limit reached; the list is partial."
            else -> null
        }
        Entries(variant, items, minOf(total, ZipEngine.MAX_ENTRIES), encrypted, broken, note)
    } ?: Entries("RAR", emptyList(), 0, false, 0, openFailure(file))

    private fun itemOf(header: FileHeader): ArchiveEngine.Item {
        val size = if (header.isUnpSizeUnknown) 0L else header.fullUnpackSize
        return ArchiveEngine.Item(header.fileName.replace('\\', '/'), size, header.isDirectory, false)
    }

    /**
     * Extract, honouring every rule `ZipEngine.extract` enforces: canonical containment, unsafe-path
     * and absolute-path refusal, no symlinks, entry and total ceilings, the ratio ceiling, and an
     * explicit overwrite decision. Links are not created here for the simple reason that the reader
     * writes bytes, never a filesystem object.
     */
    fun extract(
        file: File,
        root: File,
        overwrite: Boolean,
        only: List<String>,
    ): ArchiveEngine.Extract {
        val written = ArrayList<String>()
        val skipped = ArrayList<String>()
        var totalBytes = 0L
        var refused: String? = null
        val ok = withArchive(file) { archive ->
            if (archive.format == RarFormat.RAR14) {
                refused = "RAR 1.4 archives can be listed but not extracted by this build: the reader " +
                    "reports their headers only. Re-pack them as RAR 4.x or 5.x."
                return@withArchive false
            }
            if (runCatching { archive.isEncrypted() }.getOrDefault(false)) {
                refused = "This RAR archive is password-protected, and VN Code does not take archive " +
                    "passwords yet. It can be listed but not extracted."
                return@withArchive false
            }
            var count = 0
            for (header in archive.fileHeaders) {
                if (!header.isFileHeader) continue
                count++
                if (count > ZipEngine.MAX_ENTRIES) {
                    refused = "Entry limit reached; extraction stopped after " + written.size + " file(s)."
                    return@withArchive false
                }
                val name = header.fileName.replace('\\', '/')
                if (header.isEncrypted) { skipped.add(name + " (encrypted)"); continue }
                if (header.isDirectory) {
                    val dir = ZipEngine.safeTarget(root, name)
                    if (dir == null) skipped.add(name + " (unsafe path)") else dir.mkdirs()
                    continue
                }
                if (ZipEngine.unsafeReason(name) != null) { skipped.add(name + " (unsafe path)"); continue }
                if (only.isNotEmpty() && only.none { name == it || name.startsWith(it.trimEnd('/') + "/") }) continue
                val target = ZipEngine.safeTarget(root, name)
                if (target == null) { skipped.add(name + " (unsafe path)"); continue }
                val size = if (header.isUnpSizeUnknown) -1L else header.fullUnpackSize
                if (size > ZipEngine.MAX_ENTRY_BYTES) { skipped.add(name + " (too large)"); continue }
                if (size > 0 && totalBytes + size > ZipEngine.MAX_TOTAL_BYTES) {
                    refused = "Total size limit reached; extraction stopped after " + written.size + " file(s)."
                    return@withArchive false
                }
                // Not "?.let { continue }": a break/continue across an inline lambda is experimental
                // in this Kotlin version and fails the compile outright.
                val ratio = ratioRefusal(header)
                if (ratio != null) { skipped.add(name + " (" + ratio + ")"); continue }
                if (target.exists() && !overwrite) { skipped.add(name + " (exists)"); continue }
                target.parentFile?.mkdirs()
                val sink = CountingSink(FileOutputStream(target), ZipEngine.MAX_ENTRY_BYTES)
                try {
                    sink.use { out -> archive.extractFile(header, out) }
                } catch (e: RarException) {
                    target.delete()
                    skipped.add(name + " (unsupported or damaged: " + (e.message ?: "RAR error") + ")")
                    continue
                } catch (e: IllegalStateException) {
                    target.delete()
                    refused = name + " grew past the per-file limit; extraction stopped."
                    return@withArchive false
                }
                totalBytes += sink.count
                written.add(name)
            }
            true
        }
        if (ok == null) {
            return ArchiveEngine.Extract(
                ArchiveEngine.Kind.RAR, written, skipped,
                "Could not read this RAR archive: " + (openFailure(file)),
            )
        }
        return ArchiveEngine.Extract(ArchiveEngine.Kind.RAR, written, skipped, refused)
    }

    private fun ratioRefusal(header: FileHeader): String? {
        val packed = header.fullPackSize
        val unpacked = if (header.isUnpSizeUnknown) 0L else header.fullUnpackSize
        if (packed <= 0 || unpacked <= 0) return null
        if (unpacked < ZipEngine.RATIO_FLOOR_BYTES) return null
        val ratio = unpacked / packed
        return if (ratio > ZipEngine.MAX_RATIO) "expands " + ratio + "x, past the bomb ceiling" else null
    }

    /** Counts what actually reaches the disk, and stops the entry the moment it exceeds its ceiling. */
    private class CountingSink(
        private val out: OutputStream,
        private val max: Long,
    ) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count++
            if (count > max) throw IllegalStateException("entry exceeds the size limit")
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            count += len
            if (count > max) throw IllegalStateException("entry exceeds the size limit")
            out.write(b, off, len)
        }

        override fun close() {
            out.close()
        }
    }
}
