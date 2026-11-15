package com.codespace.ide.agent.docs

/**
 * PROJECT 2.8 (2026-11-15): audio and video files can be described before they are opened.
 *
 * The brief asks for audio and video metadata: format, duration, and what the stream actually is.
 * Copilot had no way to answer "how long is this recording" without playing it, which is useless on
 * a phone and worse inside an agent turn.
 *
 * Every value here is read from the file's own header, never guessed from the extension, and the
 * result says whether a duration is EXACT (a container that states it), ESTIMATED (a frame header
 * plus file size) or ABSENT (nothing readable) - because a wrong duration silently ruins a plan.
 */
object MediaMeta {

    data class Info(
        val format: String,
        val durationMs: Long?,
        val exact: Boolean,
        val sampleRateHz: Int?,
        val channels: Int?,
        val detail: String,
    )

    fun of(bytes: ByteArray, name: String = ""): Info? {
        if (bytes.size < 12) return null
        return wav(bytes) ?: flac(bytes) ?: mp4(bytes) ?: mp3(bytes)
    }

    fun render(bytes: ByteArray, name: String = ""): String {
        val info = of(bytes, name) ?: return "Not an audio or video file this reader understands (wav, flac, mp4/m4a/mov, mp3)."
        val sb = StringBuilder()
        sb.append(info.format)
        when {
            info.durationMs != null && info.exact -> sb.append(", ").append(clock(info.durationMs)).append(" long")
            info.durationMs != null -> sb.append(", about ").append(clock(info.durationMs)).append(" long (estimated)")
            else -> sb.append(", duration not stated in the header")
        }
        info.sampleRateHz?.let { sb.append(", ").append(it).append(" Hz") }
        info.channels?.let { sb.append(", ").append(it).append(" channel(s)") }
        if (info.detail.isNotBlank()) sb.append(" — ").append(info.detail)
        return sb.toString()
    }

    // ── containers ───────────────────────────────────────────────────────────

    private fun wav(b: ByteArray): Info? {
        if (!ascii(b, 0, 4).equals("RIFF") || !ascii(b, 8, 4).equals("WAVE")) return null
        var pos = 12
        var channels: Int? = null
        var rate: Int? = null
        var bits: Int? = null
        var byteRate: Long? = null
        var dataSize: Long? = null
        while (pos + 8 <= b.size) {
            val id = ascii(b, pos, 4)
            val size = le32(b, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> if (body + 16 <= b.size) {
                    channels = le16(b, body + 2)
                    rate = le32(b, body + 4).toInt()
                    byteRate = le32(b, body + 8).toLong() and 0xffffffffL
                    bits = le16(b, body + 14)
                }
                "data" -> {
                    dataSize = size.toLong() and 0xffffffffL
                    if (dataSize == 0L || body + dataSize > b.size) {
                        dataSize = (b.size - body).toLong()
                    }
                }
            }
            if (size < 0) break
            pos = body + size + (size % 2)
            if (id == "data" && dataSize != null) break
        }
        val duration = if (dataSize != null && byteRate != null && byteRate!! > 0) {
            dataSize!! * 1000L / byteRate!!
        } else {
            null
        }
        return Info(
            format = "WAV" + (bits?.let { " (" + it + "-bit)" } ?: ""),
            durationMs = duration,
            exact = duration != null,
            sampleRateHz = rate,
            channels = channels,
            detail = "PCM container",
        )
    }

    private fun flac(b: ByteArray): Info? {
        if (!ascii(b, 0, 4).equals("fLaC")) return null
        // STREAMINFO: sample rate 20 bits, channels 3 bits, total samples 36 bits.
        val at = 8
        if (at + 34 > b.size) return Info("FLAC", null, false, null, null, "stream info not readable")
        val rate = ((b[at + 10].toInt() and 0xff) shl 12) or
            ((b[at + 11].toInt() and 0xff) shl 4) or
            ((b[at + 12].toInt() and 0xf0) shr 4)
        val channels = ((b[at + 12].toInt() and 0x0e) shr 1) + 1
        val totalSamples = ((b[at + 13].toLong() and 0x0f) shl 32) or
            ((b[at + 14].toLong() and 0xff) shl 24) or
            ((b[at + 15].toLong() and 0xff) shl 16) or
            ((b[at + 16].toLong() and 0xff) shl 8) or
            (b[at + 17].toLong() and 0xff)
        val duration = if (rate > 0) totalSamples * 1000L / rate else null
        return Info("FLAC", duration, duration != null, rate, channels, "lossless")
    }

    private fun mp4(b: ByteArray): Info? {
        val head = ascii(b, 4, 4)
        if (head != "ftyp") return null
        val brand = ascii(b, 8, 4).trim()
        val mvhd = indexOf(b, "mvhd".toByteArray())
        if (mvhd < 0) return Info("MP4/M4A (" + brand + ")", null, false, null, null, "no movie header")
        val body = mvhd + 4
        if (body + 20 > b.size) return Info("MP4/M4A (" + brand + ")", null, false, null, null, "header truncated")
        val version = b[body].toInt() and 0xff
        return if (version == 1) {
            val timescale = be32(b, body + 20)
            val duration = be64(b, body + 24)
            val ms = if (timescale > 0) duration * 1000 / timescale else 0L
            Info("MP4/M4A (" + brand + ")", ms.takeIf { it > 0 }, ms > 0, null, null, "timescale " + timescale)
        } else {
            val timescale = be32(b, body + 12)
            val duration = be32(b, body + 16).toLong() and 0xffffffffL
            val ms = if (timescale > 0) duration * 1000 / timescale else 0L
            Info("MP4/M4A (" + brand + ")", ms.takeIf { it > 0 }, ms > 0, null, null, "timescale " + timescale)
        }
    }

    /**
     * MP3 states no duration anywhere, so it is derived from the first frame's bitrate and the file
     * size. That is an estimate and is labelled as one - a VBR file will be wrong.
     */
    private fun mp3(b: ByteArray): Info? {
        var pos = 0
        if (ascii(b, 0, 3).equals("ID3")) {
            val size = ((b[6].toInt() and 0x7f) shl 21) or ((b[7].toInt() and 0x7f) shl 14) or
                ((b[8].toInt() and 0x7f) shl 7) or (b[9].toInt() and 0x7f)
            pos = 10 + size
        }
        while (pos + 4 <= b.size && pos < MAX_SCAN) {
            val h = ((b[pos].toInt() and 0xff) shl 24) or ((b[pos + 1].toInt() and 0xff) shl 16) or
                ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            if ((h and 0xffe00000.toInt()) == 0xffe00000.toInt()) {
                val versionBits = (h shr 19) and 3
                val layerBits = (h shr 17) and 3
                val bitrateIndex = (h shr 12) and 0xf
                val rateIndex = (h shr 10) and 3
                if (layerBits != 0 && bitrateIndex != 0 && bitrateIndex != 15 && rateIndex != 3) {
                    val v1 = versionBits == 3
                    val rates = intArrayOf(44100, 48000, 32000)
                    val rate = if (v1) rates[rateIndex] else rates[rateIndex] / 2
                    val kbps = if (layerBits == 1) {
                        if (v1) 32 + 32 * (bitrateIndex - 1) else 8 + 8 * (bitrateIndex - 1)
                    } else {
                        if (v1) MP3_V1_KBP[bitrateIndex - 1] else MP3_V2_KBP[bitrateIndex - 1]
                    }
                    val bytesPerSecond = kbps.toLong() * 1000L / 8L
                    val ms = if (bytesPerSecond > 0) (b.size - pos) * 1000L / bytesPerSecond else 0L
                    return Info(
                        format = "MP3",
                        durationMs = ms.takeIf { it > 0 },
                        exact = false,
                        sampleRateHz = rate,
                        channels = if (((h shr 6) and 3) == 3) 1 else 2,
                        detail = kbps.toString() + " kbps nominal",
                    )
                }
            }
            pos++
        }
        return null
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    fun clock(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) h.toString() + "h " + m + "m " + s + "s" else m.toString() + "m " + s + "s"
    }

    private fun ascii(b: ByteArray, at: Int, len: Int): String {
        if (at + len > b.size) return ""
        return String(b, at, len, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun be32(b: ByteArray, at: Int): Long {
        if (at + 4 > b.size) return 0
        return ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
    }

    private fun be64(b: ByteArray, at: Int): Long {
        if (at + 8 > b.size) return 0
        return (be32(b, at) shl 32) or be32(b, at + 4)
    }

    private fun indexOf(b: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..b.size - needle.size) {
            for (j in needle.indices) if (b[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private const val MAX_SCAN = 256 * 1024

    private val MP3_V1_KBP = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_V2_KBP = intArrayOf(8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
}
