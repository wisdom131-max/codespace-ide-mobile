package com.codespace.ide.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PROJECT EIGHT 8.6 — the budget must differ between a million-token model and a small one.
 *
 * The defect this pins: the old allocator computed a window-scaled figure and then clamped it with a
 * default ceiling of 48,000, so every model above ~20k tokens of window got the same budget. A test
 * that only checked "the budget is a positive number" would have passed against that.
 */
class PayloadBudgetTest {

    private fun cap(tokens: Int) = ModelCapability(
        id = "m",
        contextTokens = tokens,
        supportsImages = false,
        supportsPdf = false,
        supportsReasoning = false,
    )

    @Test
    fun `a large window gets more than the old flat 48k`() {
        val budget = ModelCatalog.payloadBudgetChars(cap(1_000_000))
        assertTrue("must exceed the old 48k ceiling, was $budget", budget > 48_000)
    }

    @Test
    fun `a bigger window is never given a smaller budget`() {
        val small = ModelCatalog.payloadBudgetChars(cap(131_072))
        val large = ModelCatalog.payloadBudgetChars(cap(1_000_000))
        assertTrue("large=$large small=$small", large >= small)
    }

    @Test
    fun `no model is allowed an unbounded send`() {
        // Ten million tokens must still respect the per-send safety ceiling: the window says what is
        // possible, not what is sane to stream from a phone.
        assertEquals(ModelCatalog.SAFETY_MAX_CHARS, ModelCatalog.payloadBudgetChars(cap(10_000_000)))
    }

    @Test
    fun `an unknown model still gets a usable budget`() {
        assertEquals(ModelCatalog.SAFETY_MAX_CHARS, ModelCatalog.payloadBudgetChars(null))
        assertEquals(ModelCatalog.SAFETY_MAX_CHARS, ModelCatalog.payloadBudgetChars(cap(0)))
    }

    @Test
    fun `an explicit override wins in both directions`() {
        assertEquals(20_000, ModelCatalog.payloadBudgetChars(cap(1_000_000), 20_000))
        assertEquals(200_000, ModelCatalog.payloadBudgetChars(cap(131_072), 200_000))
    }

    @Test
    fun `the budget never falls below a usable floor`() {
        assertTrue(ModelCatalog.payloadBudgetChars(cap(1_000)) >= ModelCatalog.MIN_PAYLOAD_CHARS)
        assertTrue(ModelCatalog.payloadBudgetChars(null, 1) >= ModelCatalog.MIN_PAYLOAD_CHARS)
    }
}
