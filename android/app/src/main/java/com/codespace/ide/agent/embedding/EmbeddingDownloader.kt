package com.codespace.ide.agent.embedding

import com.codespace.ide.engine.TrainedEmbedding
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * PROJECT NINE 10.22 - fetch a model file, resume it if it stopped, and refuse it if it is wrong.
 *
 * The rules live in the engine (TrainedEmbedding) so they can be tested without a phone. This is
 * the part that touches the network and the disk.
 */
object EmbeddingDownloader {

    /**
     * Downloads [asset] into [dir], resuming from a partial file when one is there.
     *
     * Returns the verified file, or null when the download failed or the bytes did not match. A
     * partial file is deliberately left on disk so the next attempt resumes instead of restarting.
     */
    fun fetch(asset: TrainedEmbedding.Asset, dir: File, onProgress: (Int) -> Unit = {}): File? {
        if (!dir.exists() && !dir.mkdirs()) return null
        val target = File(dir, asset.name)

        // Already correct: nothing to do, and no network at all.
        if (TrainedEmbedding.stateOf(asset, target) == TrainedEmbedding.State.READY) return target

        // A file that is the wrong size or the wrong digest is thrown away, never appended to.
        if (TrainedEmbedding.stateOf(asset, target) == TrainedEmbedding.State.CORRUPT) {
            target.delete()
        }

        var attempt = 0
        while (attempt < 3) {
            attempt++
            val got = tryOne(asset, target, onProgress)
            if (got != null) return got
            // A failed attempt that left a damaged file must not be resumed from.
            if (TrainedEmbedding.stateOf(asset, target) == TrainedEmbedding.State.CORRUPT) target.delete()
        }
        return null
    }

    private fun tryOne(
        asset: TrainedEmbedding.Asset,
        target: File,
        onProgress: (Int) -> Unit,
    ): File? {
        val start = TrainedEmbedding.resumeOffset(asset, target)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(asset.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                if (start > 0L) setRequestProperty("Range", "bytes=" + start + "-")
            }
            val code = connection.responseCode
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) return null
            // The server ignored the range: start over rather than append to a full file.
            if (start > 0L && !resuming) target.delete()

            val append = resuming && target.exists()
            val expected = asset.bytes
            RandomAccessFile(target, "rw").use { out ->
                if (append) out.seek(start) else out.setLength(0)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(128 * 1024)
                    var written = out.length()
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val percent = ((written * 100) / expected).toInt().coerceIn(0, 100)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            return if (TrainedEmbedding.stateOf(asset, target) == TrainedEmbedding.State.READY) target else null
        } catch (t: Throwable) {
            return null
        } finally {
            connection?.disconnect()
        }
    }
}
