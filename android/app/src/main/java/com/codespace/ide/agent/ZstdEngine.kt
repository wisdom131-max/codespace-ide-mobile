package com.codespace.ide.agent

import com.github.luben.zstd.ZstdInputStreamNoFinalizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * P2.C - Zstandard, streaming, with the window bounded.
 *
 * Zstandard is a compressor, not an archive: one `.zst` file holds one payload, and `.tar.zst` is a
 * tar that happens to be compressed. So this decodes a stream and lets [ArchiveEngine] decide whether
 * the payload is a tar (real entry list, selective extraction) or a single file (one entry, no
 * listing to speak of). It never claims entries a `.zst` does not have.
 *
 * The decoder is `zstd-jni`, already on this build's classpath. Two things matter for this app:
 *
 *  - **Android natives.** The plain jar carries desktop `.so` files only; the `.aar` variant carries
 *    `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64`. The Gradle coordinate therefore asks for the aar,
 *    and the native library is loaded from the APK like any other.
 *  - **Window ceiling.** A zstd frame declares its own window size, and a hostile frame can declare a
 *    huge one. [MAX_WINDOW_LOG] caps it at 64 MiB through `setLongMax`, so a malicious stream is
 *    refused by the decoder instead of exhausting a phone's heap.
 */
object ZstdEngine {

    /** 2^26 bytes = 64 MiB. A frame needing a bigger window is refused rather than allowed to allocate. */
    const val MAX_WINDOW_LOG = 26

    /** A zstd stream, streaming: read, don't buffer the file. */
    fun stream(file: File): InputStream {
        val zstd = ZstdInputStreamNoFinalizer(BufferedInputStream(FileInputStream(file), 64 * 1024))
        // Continuous: keep going across concatenated frames instead of stopping at the first one,
        // which is what `zstd -c a b > out.zst` produces.
        zstd.setContinuous(true)
        zstd.setLongMax(MAX_WINDOW_LOG)
        return zstd
    }

    /**
     * True when the decompressed payload is a tar. Reads 512 bytes and no more: a tar header lives at
     * offset 257, so there is nothing to gain from decoding further just to answer this.
     */
    fun payloadIsTar(file: File): Boolean = try {
        stream(file).use { input ->
            val head = ByteArray(512)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n <= 0) break
                read += n
            }
            read > 262 && String(head, 257, 5, Charsets.US_ASCII) == "ustar"
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Decompress one whole stream into [target]. Bounded on the way out ([max] bytes) and on the way in
     * (the ratio ceiling), so a zip-bomb-shaped stream stops with a reason instead of filling storage.
     */
    fun decompressTo(file: File, target: File, max: Long): ZstdEngine.Result = try {
        target.parentFile?.mkdirs()
        var written = 0L
        // The decoder is opened first: if it throws, no output stream is left dangling.
        stream(file).use { input ->
            java.io.BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    written += n
                    if (written > max) throw IllegalStateException("the decompressed file exceeds the size limit")
                    out.write(buf, 0, n)
                }
            }
        }
        Result(written, null)
    } catch (t: Throwable) {
        target.delete()
        Result(0, "Zstandard decode failed: " + (t.message ?: t.javaClass.simpleName))
    }

    data class Result(val bytes: Long, val failure: String?)

    /** The name a single-stream `.zst` takes on extraction: the file without its last extension. */
    fun singleEntryName(file: File): String = file.name.substringBeforeLast('.', file.name)
}
