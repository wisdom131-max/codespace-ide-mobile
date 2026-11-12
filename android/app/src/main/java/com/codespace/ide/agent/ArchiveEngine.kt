package com.codespace.ide.agent

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import com.codespace.ide.util.ZipEngine

/**
 * P2.B - one archive engine for the formats this build can actually read.
 *
 * `ZipEngine` already did ZIP properly, with the security rules that matter (Slip, bombs, ratio,
 * symlinks, canonical containment). This generalises the same rules to the other formats the project
 * already ships a library for, and REFUSES the ones it does not, with a reason. Claiming 7z or RAR
 * support without the code behind it is worse than saying no.
 *
 * Supported here: ZIP (including APK, JAR and AAB, which are ZIPs), TAR, TAR.GZ/TGZ, GZ, BZ2, XZ, 7Z,
 * and - as of P2.C - RAR (1.4, 4.x and 5.x through junrar) and Zstandard, both as a bare `.zst` stream
 * and as `.tar.zst`. A format this build cannot read is still named as unsupported rather than guessed
 * at; today that is none of the common ones, so [unsupportedReason] answers null for everything [SUPPORTED].
 */
object ArchiveEngine {

    enum class Kind { ZIP, TAR, TAR_GZ, TAR_ZST, GZ, BZ2, XZ, SEVEN_Z, RAR, ZST, UNKNOWN }

    /** The formats this build can open. Everything else is named as unsupported. */
    val SUPPORTED = setOf(
        Kind.ZIP, Kind.TAR, Kind.TAR_GZ, Kind.TAR_ZST,
        Kind.GZ, Kind.BZ2, Kind.XZ, Kind.SEVEN_Z,
        Kind.RAR, Kind.ZST,
    )

    fun supported(kind: Kind): Boolean = kind in SUPPORTED

    fun unsupportedReason(kind: Kind): String? = when (kind) {
        Kind.UNKNOWN -> "Not a recognised archive: the file signature matches no supported format."
        else -> null
    }

    data class Item(
        val name: String,
        val size: Long,
        val isDirectory: Boolean,
        val isSymlink: Boolean,
    )

    data class Listing(
        val kind: Kind,
        val items: List<Item>,
        /** Total entries in the archive, which may exceed [items] when the list was bounded. */
        val total: Int,
        val note: String? = null,
    )

    data class Extract(
        val kind: Kind,
        val written: List<String>,
        val skipped: List<String>,
        val failure: String? = null,
    ) {
        val ok: Boolean get() = failure == null
    }

    /** Signature sniffing, not the file extension: a renamed archive is still an archive. */
    fun kindOf(file: File): Kind {
        val head = ByteArray(512)
        val read = try {
            FileInputStream(file).use { it.read(head) }
        } catch (e: Exception) {
            return Kind.UNKNOWN
        }
        if (read < 4) return Kind.UNKNOWN
        fun at(i: Int) = head[i].toInt() and 0xff
        return when {
            at(0) == 0x50 && at(1) == 0x4b && (at(2) == 0x03 || at(2) == 0x05) -> Kind.ZIP
            at(0) == 0x52 && at(1) == 0x61 && at(2) == 0x72 && at(3) == 0x21 -> Kind.RAR
            at(0) == 0x1f && at(1) == 0x8b -> Kind.GZ
            at(0) == 0x42 && at(1) == 0x5a && at(2) == 0x68 -> Kind.BZ2
            at(0) == 0xfd && at(1) == 0x37 && at(2) == 0x7a && at(3) == 0x58 -> Kind.XZ
            at(0) == 0x37 && at(1) == 0x7a && at(2) == 0xbc && at(3) == 0xaf -> Kind.SEVEN_Z
            at(0) == 0x28 && at(1) == 0xb5 && at(2) == 0x2f && at(3) == 0xfd -> Kind.ZST
            read > 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar" -> Kind.TAR
            else -> Kind.UNKNOWN
        }
    }

    /**
     * The kind, refined by looking at the payload where the signature alone is not enough: a `.zst`
     * holds one stream, and whether that stream is a tar decides if there is an entry list at all.
     * Costs 512 decompressed bytes, never a whole file.
     */
    fun resolvedKind(file: File): Kind {
        val kind = kindOf(file)
        return if (kind == Kind.ZST && ZstdEngine.payloadIsTar(file)) Kind.TAR_ZST else kind
    }

    /** List entries, bounded, with the total reported so a truncated list is never mistaken for all. */
    fun list(file: File, offset: Int = 0, limit: Int = ZipEngine.DEFAULT_LIST): Listing {
        val kind = resolvedKind(file)
        unsupportedReason(kind)?.let { return Listing(kind, emptyList(), 0, it) }
        if (kind == Kind.ZIP) {
            return when (val out = ZipEngine.list(file, offset, limit)) {
                is ZipEngine.ListOutcome.Ok -> Listing(
                    kind,
                    out.entries.map { Item(it.name, it.size, it.isDirectory, false) },
                    out.total,
                    out.suspicious.firstOrNull()?.detail,
                )
                is ZipEngine.ListOutcome.Failed -> Listing(kind, emptyList(), 0, out.reason)
            }
        }
        if (kind == Kind.RAR) {
            val entries = RarEngine.list(file, offset, limit)
            return Listing(kind, entries.items, entries.total, entries.note)
        }
        val cap = limit.coerceIn(1, ZipEngine.MAX_LIST)
        val items = ArrayList<Item>()
        var total = 0
        var problem: String? = null
        try {
            if (kind == Kind.SEVEN_Z) {
                // Previously this returned an empty list and the caller reported "the archive is empty",
                // which was untrue for every 7z. Enumerate for real; the ceiling still applies.
                SevenZFile(file).use { z ->
                    for (e in z.entries) {
                        total++
                        if (total > ZipEngine.MAX_ENTRIES) { problem = "Entry limit reached; the list is partial."; break }
                        if (total <= offset) continue
                        if (items.size < cap) items.add(Item(e.name, e.size, e.isDirectory, false))
                    }
                }
            } else if (kind == Kind.GZ || kind == Kind.BZ2 || kind == Kind.XZ) {
                // A plain compressor wraps ONE file; the archive name is the only entry. When the
                // payload turns out to be a tar, say that instead of offering a nameless blob - the
                // behaviour P2.B shipped, restored here after a rewrite of this block dropped it.
                val one = File(file.name.substringBeforeLast('.', file.name))
                if (kindOfGuessIsTar(file, kind)) {
                    total = 0
                    problem = "The compressed payload is a tar archive: open it as .tar.gz for full listing."
                } else {
                    total = 1
                    if (offset <= 0) items.add(Item(one.name, file.length(), false, false))
                }
            } else if (kind == Kind.ZST) {
                // A bare .zst is one stream: one entry, named after the file. (.tar.zst never reaches
                // here - resolvedKind() reports TAR_ZST and it is walked as a tar above.)
                total = 1
                if (offset <= 0) items.add(Item(ZstdEngine.singleEntryName(file), file.length(), false, false))
            } else {
                open(file, kind).use { stream ->
                    val tar = stream as? TarArchiveInputStream ?: return@use
                    while (true) {
                        val e = tar.nextEntry ?: break
                        total++
                        if (total > ZipEngine.MAX_ENTRIES) { problem = "Entry limit reached; the list is partial."; break }
                        if (total <= offset) continue
                        if (items.size < cap) items.add(Item(e.name, e.size, e.isDirectory, e.isSymbolicLink))
                    }
                }
            }
        } catch (t: Throwable) {
            problem = "Could not read the archive (" + (t.message ?: t.javaClass.simpleName) + ")."
        }
        return Listing(kind, items, total, problem)
    }

    /**
     * Extract, with every rule `ZipEngine.extract` enforces: entry and byte ceilings, ratio ceiling,
     * no symlinks, canonical containment under [root], and an explicit overwrite decision.
     */
    fun extract(file: File, root: File, overwrite: Boolean = false, only: List<String> = emptyList()): Extract {
        val kind = resolvedKind(file)
        unsupportedReason(kind)?.let { return Extract(kind, emptyList(), emptyList(), it) }
        if (kind == Kind.ZIP) {
            val out = ZipEngine.extract(file, root, overwrite)
            return Extract(
                kind = kind,
                written = List(out.files) { "file" },
                skipped = out.skipped.map { it.detail } + out.failed.map { it.detail },
                failure = if (out.failed.isEmpty()) null else out.failed.first().detail,
            )
        }
        if (kind == Kind.RAR) return RarEngine.extract(file, root, overwrite, only)
        val written = ArrayList<String>()
        val skipped = ArrayList<String>()
        var totalBytes = 0L
        try {
            if (kind == Kind.ZST) {
                // One stream, one file - unless it is a .tar.zst, which never reaches this branch
                // because resolvedKind() reports it as TAR_ZST and it is walked as a tar below.
                val target = File(root, ZstdEngine.singleEntryName(file))
                if (!contains(root, target)) return Extract(kind, written, skipped, "Refused: output escapes the destination.")
                if (target.exists() && !overwrite) return Extract(kind, written, skipped, "Refused: " + target.name + " already exists (use force).")
                val out = ZstdEngine.decompressTo(file, target, ZipEngine.MAX_ENTRY_BYTES)
                out.failure?.let { return Extract(kind, written, skipped, it) }
                written.add(target.name)
                return Extract(kind, written, skipped)
            }
            if (kind == Kind.GZ || kind == Kind.BZ2 || kind == Kind.XZ) {
                val target = File(root, file.name.substringBeforeLast('.', file.name))
                if (!contains(root, target)) return Extract(kind, written, skipped, "Refused: output escapes the destination.")
                if (target.exists() && !overwrite) return Extract(kind, written, skipped, "Refused: " + target.name + " already exists (use force).")
                val read = copyBounded(decompress(file, kind), target, ZipEngine.MAX_ENTRY_BYTES)
                totalBytes += read
                written.add(target.name)
                return Extract(kind, written, skipped)
            }
            val stream = open(file, kind)
            stream.use { s ->
                val tar = s as? TarArchiveInputStream
                    ?: return Extract(kind, written, skipped, "This archive has no entry list to extract.")
                var count = 0
                while (true) {
                    val e = tar.nextEntry ?: break
                    count++
                    if (count > ZipEngine.MAX_ENTRIES) return Extract(kind, written, skipped, "Entry limit reached; extraction stopped.")
                    val name = e.name.replace('\\', '/')
                    if (e.isSymbolicLink || e.isLink) { skipped.add(name + " (link)"); continue }
                    if (ZipEngine.unsafeReason(name) != null) { skipped.add(name + " (unsafe path)"); continue }
                    if (only.isNotEmpty() && only.none { name == it || name.startsWith(it.trimEnd('/') + "/") }) continue
                    val target = ZipEngine.safeTarget(root, name)
                        ?: return Extract(kind, written, skipped, "Refused: " + name + " escapes the destination.")
                    if (e.isDirectory) { target.mkdirs(); continue }
                    if (target.exists() && !overwrite) { skipped.add(name + " (exists)"); continue }
                    if (e.size > ZipEngine.MAX_ENTRY_BYTES) { skipped.add(name + " (too large)"); continue }
                    if (totalBytes + e.size > ZipEngine.MAX_TOTAL_BYTES) return Extract(kind, written, skipped, "Total size limit reached; extraction stopped.")
                    totalBytes += copyBounded(tar, target, ZipEngine.MAX_ENTRY_BYTES)
                    written.add(name)
                }
            }
        } catch (t: Throwable) {
            return Extract(kind, written, skipped, "Extraction failed: " + (t.message ?: t.javaClass.simpleName))
        }
        return Extract(kind, written, skipped)
    }

    // ------------------------------------------------------------------ internals

    private fun contains(root: File, target: File): Boolean = try {
        val r = root.canonicalPath + File.separator
        target.canonicalPath.startsWith(r)
    } catch (e: Exception) {
        false
    }

    /** True when a single-file compressor is wrapping a tar. Reads 512 bytes, never the whole file. */
    private fun kindOfGuessIsTar(file: File, kind: Kind): Boolean = try {
        val head = ByteArray(512)
        decompress(file, kind).use { it.read(head) }
        String(head, 257, 5, Charsets.US_ASCII) == "ustar"
    } catch (e: Exception) {
        false
    }

    private fun decompress(file: File, kind: Kind): InputStream = when (kind) {
        Kind.ZST, Kind.TAR_ZST -> ZstdEngine.stream(file)
        Kind.BZ2 -> BZip2CompressorInputStream(buffered(file), true)
        Kind.XZ -> XZCompressorInputStream(buffered(file))
        else -> GzipCompressorInputStream(buffered(file), true)
    }

    private fun buffered(file: File) = BufferedInputStream(FileInputStream(file), 64 * 1024)

    private fun open(file: File, kind: Kind): java.io.Closeable = when (kind) {
        Kind.TAR -> TarArchiveInputStream(buffered(file))
        Kind.TAR_ZST -> TarArchiveInputStream(ZstdEngine.stream(file))
        Kind.TAR_GZ -> TarArchiveInputStream(GzipCompressorInputStream(buffered(file), true))
        Kind.GZ, Kind.BZ2, Kind.XZ -> decompress(file, kind)
        Kind.SEVEN_Z -> SevenZFile(file)
        else -> throw IllegalStateException("unsupported: " + kind)
    }

    private fun copyBounded(input: InputStream, target: File, max: Long): Long {
        target.parentFile?.mkdirs()
        var written = 0L
        FileOutputStream(target).use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                written += n
                if (written > max) throw IllegalStateException("entry exceeds the size limit")
                out.write(buf, 0, n)
            }
        }
        return written
    }
}
