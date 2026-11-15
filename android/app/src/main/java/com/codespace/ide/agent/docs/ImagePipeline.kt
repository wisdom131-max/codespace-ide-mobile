package com.codespace.ide.agent.docs

/**
 * PROJECT 2.10 (2026-11-15): converting and shrinking an image safely.
 *
 * The brief asks for "safe conversion and resizing, with bounded loading". The dangerous part of
 * image work on a phone is not the encoder, it is the DECODE: a 12-megapixel photo allocates about
 * 48MB as ARGB_8888, and doing that because someone asked for a smaller copy is how an app dies.
 *
 * So the order is fixed and this file holds the pure half that can be checked without Android: read
 * the bounds only, choose a power-of-two sample size that lands ABOVE the target rather than below
 * it, then scale the remaining step exactly. Decoding, scaling and encoding happen in the tool.
 */
object ImagePipeline {

    /**
     * The largest power-of-two sub-sample that still leaves the long edge at or above [maxDimension].
     *
     * Power of two because the decoder only honours those (anything else silently rounds down, which
     * is how a "resize" quietly produces a blurrier image than asked). Never below the target, so the
     * exact scale afterwards only ever shrinks slightly; never infinite, so a nonsense request cannot
     * loop.
     */
    fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0 || maxDimension <= 0) return 1
        var sample = 1
        var longEdge = maxOf(width, height)
        while (longEdge / 2 >= maxDimension && sample < MAX_SAMPLE) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    /**
     * The size to scale the sampled bitmap to. Never upscales: asking for a 4,000px copy of a 200px
     * image must not invent pixels, it must return the original size and let the caller say so.
     */
    fun targetSize(width: Int, height: Int, maxDimension: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return Pair(0, 0)
        // A nonsense target means "no resize", not "no image": the caller's pixels are still real.
        if (maxDimension <= 0) return Pair(width, height)
        val longEdge = maxOf(width, height)
        if (longEdge <= maxDimension) return Pair(width, height)
        val scale = maxDimension.toDouble() / longEdge.toDouble()
        val w = Math.round(width * scale).toInt().coerceAtLeast(1)
        val h = Math.round(height * scale).toInt().coerceAtLeast(1)
        return Pair(w, h)
    }

    /** Moments JPEG smooths faces; 85 is the usual "looks the same, much smaller" setting. */
    fun clampQuality(quality: Int): Int = quality.coerceIn(1, 100)

    fun formatOf(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "png" -> "png"
        "webp" -> "webp"
        "jpg", "jpeg" -> "jpeg"
        else -> ""
    }

    private const val MAX_SAMPLE = 128
}
