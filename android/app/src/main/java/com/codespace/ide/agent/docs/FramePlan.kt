package com.codespace.ide.agent.docs

/**
 * PROJECT 2.11 (2026-11-15): still frames can be taken out of a video, at planned times.
 *
 * Grabbing "a few frames" sounds trivial and is not: taking them all at 0ms gives the same black
 * first frame every time, taking them evenly while ignoring the duration gives duplicates, and
 * asking for 500 of them on a phone fills internal storage. The planning half is pure and lives
 * here so it can be checked; the decoder half lives in the tool and uses the platform retriever.
 */
object FramePlan {

    /**
     * Evenly spaced instants across the whole video, never at the very end.
     *
     * The last frame of a container is routinely past the final decodable picture, so the plan stops
     * one step short and a short video never yields two identical instants.
     */
    fun timestamps(durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0 || count <= 0) return emptyList()
        val n = count.coerceAtMost(MAX_FRAMES)
        if (durationMs < n * 100L) return listOf(durationMs / 2)
        val step = durationMs / (n + 1)
        return (1..n).map { step * it }
    }

    /** A name that cannot collide with the video itself and stays in the same folder. */
    fun frameName(baseName: String, index: Int, extension: String): String {
        val stem = baseName.substringBeforeLast('.', baseName).ifBlank { "frame" }
        return stem + "-frame" + (index + 1).toString().padStart(2, '0') + "." + extension
    }

    fun clampCount(count: Int): Int = count.coerceIn(1, MAX_FRAMES)

    private const val MAX_FRAMES = 60
}
