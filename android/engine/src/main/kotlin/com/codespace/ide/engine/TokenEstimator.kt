package com.codespace.ide.engine

object TokenEstimator {
    private const val CHARS_PER_TOKEN = 3.5
    fun estimate(chars: Int): Int = if (chars <= 0) 0 else Math.ceil(chars / CHARS_PER_TOKEN).toInt()
    fun fits(chars: Int, contextTokens: Int, reserveTokens: Int = 512): Boolean =
        contextTokens > 0 && estimate(chars) + reserveTokens <= contextTokens
}
