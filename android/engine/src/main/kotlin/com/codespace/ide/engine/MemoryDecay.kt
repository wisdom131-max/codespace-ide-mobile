package com.codespace.ide.engine

/**
 * PROJECT EIGHT 8.20 (catalog 41) - age should soften a memory, not delete it.
 *
 * The facts module ranked by relevance and standing but had no notion of AGE, so a note from two
 * years ago competed with one from yesterday. This is the missing weight: a bounded multiplier,
 * never a filter. A stale fact is quieter, not gone, because "forget what I told you in March" is
 * not something the app should decide on its own.
 *
 * Two rules keep it from doing harm:
 *  - Standing preferences and constraints are EXEMPT. A rule does not get less true with age.
 *  - Use resists decay. A fact the model keeps reaching for is evidently still load-bearing, so
 *    being retrieved lifts the factor back up.
 */
object MemoryDecay {

    /** Days after which a fact's weight halves. 90 days: long enough that active projects hold. */
    const val HALF_LIFE_DAYS: Double = 90.0

    /** Floor, so an ancient fact is still visible if it is the only thing that matches. */
    const val MIN_FACTOR: Double = 0.1

    /** Ceiling on the use boost, so a much-used fact cannot bury everything else. */
    const val MAX_USE_BOOST: Double = 1.5

    /** Categories that must never fade: they are rules, not observations. */
    private val EXEMPT = setOf("preference", "constraint", "convention")

    fun isExempt(categoryId: String): Boolean = categoryId.lowercase() in EXEMPT

    /**
     * @param createdAt when the fact was first written
     * @param lastUsedAt when retrieval last put it in a prompt (0 = never)
     * @param useCount how many times it has been retrieved
     * @return multiplier in [MIN_FACTOR, MAX_USE_BOOST]; 1.0 means "no change"
     */
    fun factor(
        createdAt: Long,
        lastUsedAt: Long,
        useCount: Int,
        now: Long,
    ): Double {
        // Recency is measured from the last time it was USED, not written: a fact still in play
        // should not age out just because it is old.
        val anchor = if (lastUsedAt > 0L) lastUsedAt else createdAt
        if (anchor <= 0L || now <= anchor) return 1.0
        val ageDays = (now - anchor).toDouble() / 86_400_000.0
        val decayed = Math.pow(0.5, ageDays / HALF_LIFE_DAYS)
        val boost = 1.0 + (useCount.coerceAtLeast(0) * 0.1).coerceAtMost(MAX_USE_BOOST - 1.0)
        return (decayed * boost).coerceIn(MIN_FACTOR, MAX_USE_BOOST)
    }

    /** Convenience: the factor that applies to this category, or 1.0 when the category is exempt. */
    fun factorFor(
        categoryId: String,
        createdAt: Long,
        lastUsedAt: Long,
        useCount: Int,
        now: Long,
    ): Double =
        if (isExempt(categoryId)) 1.0 else factor(createdAt, lastUsedAt, useCount, now)
}
