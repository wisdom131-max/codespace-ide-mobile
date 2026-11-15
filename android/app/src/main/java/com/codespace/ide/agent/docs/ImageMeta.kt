package com.codespace.ide.agent.docs

/**
 * PROJECT 2.9 (2026-11-15): an image can be described without decoding it.
 *
 * `view_image` already asks Android for the bounds, which is fine for one screenshot and ruinous for
 * a folder of 40MB photos on a phone: a decode allocates the bitmap even when the only question is
 * "how big is this". Every value here comes from the file's OWN header bytes - PNG IHDR, GIF logical
 * screen descriptor, the JPEG start-of-frame marker, WebP's VP8/VP8L/VP8X headers, the BMP info
 * header - so describing a hundred images costs one bounded read each and no bitmaps at all.
 *
 * JPEG orientation is read from the EXIF application segment, because a phone photo that is stored
 * landscape with an orientation tag will look sideways in a report, and a size check that ignores
 * that reports the wrong dimensions to a human.
 */
object ImageMeta {

    data class Info(
        val format: String,
        val width: Int,
        val height: Int,
        val orientation: Int,
        val detail: String,
    ) {
        /**
         * True when the stored pixels are rotated relative to how the picture should be shown. The
         * width and height in every other header are the STORED ones, which is why this is surfaced
         * instead of silently swapping them.
         */
        fun isRotated(): Boolean = orientation in 5..8
    }

    fun of(b: ByteArray): Info? {
        if (b.size < 12) return null
        return png(b) ?: gif(b) ?: webp(b) ?: bmp(b) ?: jpeg(b)
    }

    fun render(info: Info): String {
        val shown = if (info.isRotated()) info.height.toString() + "x" + info.width else info.width.toString() + "x" + info.height
        val sb = StringBuilder()
        sb.append(info.format).append(", ").append(shown).append(" as displayed")
        if (info.isRotated()) {
            sb.append(" (stored ").append(info.width).append("x").append(info.height)
                .append(", EXIF orientation ").append(info.orientation).append(")")
        }
        if (info.detail.isNotBlank()) sb.append(" — ").append(info.detail)
        return sb.toString()
    }

    // ── formats ──────────────────────────────────────────────────────────────

    private fun png(b: ByteArray): Info? {
        val sig = intArrayOf(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (b.size < 26) return null
        for (i in sig.indices) if ((b[i].toInt() and 0xff) != sig[i]) return null
        if (ascii(b, 12, 4) != "IHDR") return null
        val w = be32(b, 16).toInt()
        val h = be32(b, 20).toInt()
        val depth = b[24].toInt() and 0xff
        val colour = when (b[25].toInt() and 0xff) {
            0 -> "greyscale"
            2 -> "truecolour"
            3 -> "palette"
            4 -> "greyscale + alpha"
            6 -> "truecolour + alpha"
            else -> "colour type " + (b[25].toInt() and 0xff)
        }
        return Info("PNG", w, h, 1, depth.toString() + "-bit " + colour)
    }

    private fun gif(b: ByteArray): Info? {
        val head = ascii(b, 0, 6)
        if (head != "GIF87a" && head != "GIF89a") return null
        if (b.size < 10) return null
        val w = le16(b, 6)
        val h = le16(b, 8)
        return Info("GIF", w, h, 1, "animated container")
    }

    private fun webp(b: ByteArray): Info? {
        if (ascii(b, 0, 4) != "RIFF" || ascii(b, 8, 4) != "WEBP") return null
        val kind = ascii(b, 12, 4)
        return when (kind) {
            "VP8X" -> {
                if (b.size < 30) return null
                val w = 1 + le24(b, 24)
                val h = 1 + le24(b, 27)
                Info("WebP", w, h, 1, "extended (alpha or animation)")
            }
            "VP8L" -> {
                if (b.size < 25) return null
                val bits = (b[21].toInt() and 0xff) or ((b[22].toInt() and 0xff) shl 8) or
                    ((b[23].toInt() and 0xff) shl 16) or ((b[24].toInt() and 0xff) shl 24)
                Info("WebP", (bits and 0x3fff) + 1, ((bits shr 14) and 0x3fff) + 1, 1, "lossless")
            }
            "VP8 " -> {
                if (b.size < 30) return null
                val w = le16(b, 26) and 0x3fff
                val h = le16(b, 28) and 0x3fff
                Info("WebP", w, h, 1, "lossy")
            }
            else -> null
        }
    }

    private fun bmp(b: ByteArray): Info? {
        if (ascii(b, 0, 2) != "BM" || b.size < 26) return null
        val w = le32(b, 18)
        val h = le32(b, 22)
        return Info("BMP", w, kotlin.math.abs(h), 1, if (h < 0) "top-down rows" else "")
    }

    private fun jpeg(b: ByteArray): Info? {
        if ((b[0].toInt() and 0xff) != 0xff || (b[1].toInt() and 0xff) != 0xd8) return null
        var orientation = 1
        var pos = 2
        while (pos + 4 <= b.size) {
            if ((b[pos].toInt() and 0xff) != 0xff) {
                pos++
                continue
            }
            val marker = b[pos + 1].toInt() and 0xff
            if (marker == 0xd8 || marker == 0x01 || marker in 0xd0..0xd7) {
                pos += 2
                continue
            }
            val length = ((b[pos + 2].toInt() and 0xff) shl 8) or (b[pos + 3].toInt() and 0xff)
            if (length < 2) break
            if (marker == 0xe1 && pos + length <= b.size) {
                val exif = exifOrientation(b, pos + 4, length - 2)
                if (exif > 0) orientation = exif
            }
            if (SOF_MARKERS.contains(marker) && pos + 9 <= b.size) {
                val h = ((b[pos + 5].toInt() and 0xff) shl 8) or (b[pos + 6].toInt() and 0xff)
                val w = ((b[pos + 7].toInt() and 0xff) shl 8) or (b[pos + 8].toInt() and 0xff)
                val components = b[pos + 9].toInt() and 0xff
                return Info("JPEG", w, h, orientation, components.toString() + " component(s)")
            }
            pos += 2 + length
        }
        return null
    }

    /** EXIF orientation lives in IFD0 tag 0x0112 of the TIFF block inside the APP1 segment. */
    private fun exifOrientation(b: ByteArray, start: Int, length: Int): Int {
        if (start + 14 > b.size) return 0
        if (ascii(b, start, 6) != "Exif\u0000\u0000") return 0
        val tiff = start + 6
        if (tiff + 8 > b.size) return 0
        val little = (b[tiff].toInt() and 0xff) == 0x49
        val ifdOffset = if (little) le32At(b, tiff + 4) else be32(b, tiff + 4).toInt()
        val ifd = tiff + ifdOffset
        if (ifd < 0 || ifd + 2 > b.size) return 0
        val count = if (little) le16(b, ifd) else ((b[ifd].toInt() and 0xff) shl 8) or (b[ifd + 1].toInt() and 0xff)
        if (count <= 0 || count > 512) return 0
        for (i in 0 until count) {
            val entry = ifd + 2 + i * 12
            if (entry + 12 > b.size) return 0
            val tag = if (little) le16(b, entry) else ((b[entry].toInt() and 0xff) shl 8) or (b[entry + 1].toInt() and 0xff)
            if (tag == 0x0112) {
                return if (little) le16(b, entry + 8) else ((b[entry + 8].toInt() and 0xff) shl 8) or
                    (b[entry + 9].toInt() and 0xff)
            }
        }
        return 0
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ascii(b: ByteArray, at: Int, len: Int): String {
        if (at + len > b.size) return ""
        return String(b, at, len, Charsets.US_ASCII)
    }

    private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)

    private fun le32At(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun le32(b: ByteArray, at: Int): Int = le32At(b, at)

    private fun le24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16)

    private fun be32(b: ByteArray, at: Int): Long {
        if (at + 4 > b.size) return 0
        return ((b[at].toLong() and 0xff) shl 24) or ((b[at + 1].toLong() and 0xff) shl 16) or
            ((b[at + 2].toLong() and 0xff) shl 8) or (b[at + 3].toLong() and 0xff)
    }

    private val SOF_MARKERS = setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)
}
