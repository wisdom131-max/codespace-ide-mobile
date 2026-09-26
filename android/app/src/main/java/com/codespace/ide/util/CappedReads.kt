package com.codespace.ide.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * VG05 (2026-09-26): ONE shared capped read for every binary viewer. The same formats
 * were capped inconsistently before (Elf 128MB via ElfParser, Dex 64MB via
 * DexViewerDialog) while Smali/Network/AndroidRuntime readBytes()'d whole files
 * uncapped — a ~1GB dex/so/pcap from Downloads OOM'd a 3GB device.
 *
 * CAP VALUE: 128MB — the codebase's existing shared-parser precedent (ElfParser), so
 * no viewer is silently tightened, and it is the SAME value across all four affected
 * viewers (Smali, Disassembly-via-ElfParser, Network, AndroidRuntime). AxmlDecoder
 * delegates too with its own tighter 8MB manifest cap.
 */
object CappedReads {
    /** The one consistent binary-viewer cap. */
    const val MAX_BYTES: Long = 128L * 1024 * 1024

    /**
     * Streams up to [maxBytes] from [input] — the cap is enforced DURING the copy, not
     * after (a lying content length can never OOM the process). Throws
     * [FileTooLargeException] beyond the cap so callers fail honestly.
     */
    fun read(input: InputStream, maxBytes: Long = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream(minOf(maxBytes, 1L shl 20).toInt())
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw FileTooLargeException("stream", total, maxBytes)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** File overload — same cap discipline, enforced during the stream copy. */
    fun read(file: java.io.File, maxBytes: Long = MAX_BYTES): ByteArray =
        file.inputStream().use { read(it, maxBytes) }
}

/** Honest failure for over-cap reads — an IOException, so existing viewer catches work. */
class FileTooLargeException(val path: String, val actual: Long, val cap: Long) :
    IOException("Too large to open in this viewer: $path needs at least ${actual / (1024 * 1024)}MB (cap ${cap / (1024 * 1024)}MB)")
